import { ApiError, type ApiClient } from '../http';
import { hasPermission } from '../contracts';
import type { AdminProduct, ProductWrite } from '../content/admin-types';
import type { AdminChapter, ChapterWrite } from './admin-types';
export interface CourseWorkflow {
  stage: string;
  product: AdminProduct;
  course: {
    id: string;
    version: number;
    productId: string;
    validityType: 'DAYS' | 'PERMANENT';
    validityDays: number | null;
    teacherName?: string | null;
    durationMinutes?: number;
  };
  chapters: AdminChapter[];
}
export interface WorkflowWrite {
  operationKey: string;
  product: ProductWrite;
  course: Omit<CourseWorkflow['course'], 'id' | 'productId'>;
}
export interface WorkflowPublish {
  operationKey: string;
  productVersion: number;
  courseVersion: number;
  reason: string;
}
export interface WorkflowChapter {
  operationKey: string;
  chapter: ChapterWrite;
}
export type WorkflowCommand =
  | { action: 'SAVE'; courseId: string | null; body: WorkflowWrite }
  | { action: 'PUBLISH'; courseId: string; body: WorkflowPublish }
  | { action: 'CHAPTER_CREATE'; courseId: string; body: WorkflowChapter };
export type WorkflowResult = CourseWorkflow | { chapter: AdminChapter; workflow: CourseWorkflow };
function validateResult(result: WorkflowResult): WorkflowResult {
  const value = result && 'workflow' in result ? result.workflow : result;
  if (
    !value ||
    typeof value.course?.id !== 'string' ||
    !value.course.id ||
    typeof value.product?.id !== 'string' ||
    value.product.type !== 'COURSE' ||
    value.course.productId !== value.product.id ||
    !Number.isInteger(value.course.version) ||
    !Number.isInteger(value.product.version) ||
    !Array.isArray(value.chapters)
  )
    throw new ApiError('INVALID_RESPONSE', '课程结果不完整，请查询原操作结果');
  return result;
}
export function createCourseWorkflowApi(client: ApiClient, permissions: () => string[]) {
  function path(id: string) {
    if (!id.trim() || id === '.' || id === '..') throw new Error('课程编号无效');
    return encodeURIComponent(id);
  }
  async function call<T>(url: string, options?: Parameters<ApiClient['request']>[1]) {
    if (!hasPermission(permissions(), 'CONTENT_EDIT'))
      throw new ApiError('FORBIDDEN', '没有内容管理权限');
    return client.request<T>(url, options);
  }
  return {
    get: (id: string) => call<CourseWorkflow>(`/admin/course-workflows/${path(id)}`),
    operation: (key: string) =>
      call<{
        operationKey: string;
        action: string;
        status: 'COMMITTED';
        courseId: string;
        result: WorkflowResult;
      }>(`/admin/course-workflows/operations/${path(key)}`),
    execute: (command: WorkflowCommand) => {
      const root = '/admin/course-workflows';
      if (command.action === 'SAVE')
        return call<WorkflowResult>(command.courseId ? `${root}/${path(command.courseId)}` : root, {
          method: command.courseId ? 'PUT' : 'POST',
          body: command.body,
        });
      return call<WorkflowResult>(
        `${root}/${path(command.courseId)}/${command.action === 'PUBLISH' ? 'publish' : 'chapters'}`,
        { method: 'POST', body: command.body },
      );
    },
  };
}

export interface WorkflowJournal {
  get(): WorkflowCommand | null;
  set(command: WorkflowCommand): void;
  clear(): void;
}
/** Persist the exact payload before sending; unknown outcomes only replay their original key. */
export function createWorkflowRecovery(
  api: ReturnType<typeof createCourseWorkflowApi>,
  journal: WorkflowJournal,
) {
  let active: Promise<WorkflowResult> | null = null;
  const locked = (action: () => Promise<WorkflowResult>) => {
    if (active) return active;
    active = action().finally(() => {
      active = null;
    });
    return active;
  };
  async function send(command: WorkflowCommand) {
    try {
      const result = validateResult(await api.execute(command));
      journal.clear();
      return result;
    } catch (cause) {
      if (
        cause instanceof ApiError &&
        cause.status >= 400 &&
        cause.status < 500 &&
        ![408, 429].includes(cause.status) &&
        !['IDEMPOTENCY_CONFLICT', 'INVALID_RESPONSE'].includes(cause.code)
      )
        journal.clear();
      throw cause;
    }
  }
  return {
    submit(command: WorkflowCommand) {
      return locked(async () => {
        if (journal.get()) throw new Error('原操作结果未确认，请先恢复原操作');
        const snapshot = JSON.parse(JSON.stringify(command)) as WorkflowCommand;
        journal.set(snapshot);
        return send(snapshot);
      });
    },
    recover() {
      return locked(async () => {
        const command = journal.get();
        if (!command) throw new Error('没有待恢复操作');
        try {
          const receipt = await api.operation(command.body.operationKey);
          const expected =
            command.action === 'SAVE' ? (command.courseId ? 'SAVE' : 'CREATE') : command.action;
          if (
            receipt.status !== 'COMMITTED' ||
            receipt.action !== expected ||
            receipt.operationKey !== command.body.operationKey ||
            (command.courseId && receipt.courseId !== command.courseId)
          )
            throw new Error('原操作回执不匹配，请核对');
          validateResult(receipt.result);
          journal.clear();
          return receipt.result;
        } catch (cause) {
          if (cause instanceof ApiError && cause.status === 404) return send(command);
          throw cause;
        }
      });
    },
  };
}
