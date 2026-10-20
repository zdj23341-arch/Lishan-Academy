import { expect, it, vi } from 'vitest';
import { ApiError } from '../src/http';
import { createWorkflowRecovery, type WorkflowCommand } from '../src/learning/course-workflow';
const command: WorkflowCommand = {
  action: 'SAVE',
  courseId: null,
  body: {
    operationKey: '11111111-1111-4111-8111-111111111111',
    product: {
      version: 0,
      type: 'COURSE',
      title: '原课程',
      subtitle: null,
      coverAssetId: null,
      priceFen: 100,
      originalPriceFen: null,
      sortNo: 0,
      categoryIds: [],
      contentBlocks: [],
    },
    course: { version: 0, validityType: 'PERMANENT', validityDays: null },
  },
};
const validResult = {
  stage: 'DRAFT',
  product: { ...command.body.product, id: '101', version: 1, status: 'DRAFT' },
  course: { id: '91', productId: '101', version: 1, validityType: 'PERMANENT', validityDays: null },
  chapters: [],
};
it('persists the original command before sending and recovers a committed create without another write', async () => {
  let stored: WorkflowCommand | null = null;
  const result = validResult;
  const api = {
    get: vi.fn(),
    execute: vi.fn(async () => {
      expect(stored).toEqual(command);
      throw new ApiError('NETWORK_ERROR', 'offline');
    }),
    operation: vi.fn(async () => ({
      operationKey: command.body.operationKey,
      action: 'CREATE',
      status: 'COMMITTED' as const,
      courseId: '91',
      result,
    })),
  };
  const journal = {
    get: () => stored,
    set: (value: WorkflowCommand) => {
      stored = value;
    },
    clear: () => {
      stored = null;
    },
  };
  const recovery = createWorkflowRecovery(api as never, journal);
  await expect(recovery.submit(command)).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
  await expect(createWorkflowRecovery(api as never, journal).recover()).resolves.toEqual(result);
  expect(api.execute).toHaveBeenCalledTimes(1);
  expect(stored).toBeNull();
});
it('queries first and replays the identical payload and key when no receipt exists', async () => {
  let stored: WorkflowCommand | null = structuredClone(command);
  const calls: string[] = [];
  const api = {
    get: vi.fn(),
    operation: vi.fn(async () => {
      calls.push('query');
      throw new ApiError('NOT_FOUND', 'not visible', null, {}, 404);
    }),
    execute: vi.fn(async (body) => {
      calls.push('write');
      expect(body).toEqual(command);
      return validResult;
    }),
  };
  await createWorkflowRecovery(api as never, {
    get: () => stored,
    set: () => {},
    clear: () => {
      stored = null;
    },
  }).recover();
  expect(calls).toEqual(['query', 'write']);
  expect(stored).toBeNull();
});

it('retains the exact request when a success response is missing its course identity', async () => {
  let stored: WorkflowCommand | null = null;
  const api = {
    get: vi.fn(),
    execute: vi.fn(async () => ({ stage: 'DRAFT' })),
    operation: vi.fn(),
  };
  await expect(
    createWorkflowRecovery(api as never, {
      get: () => stored,
      set: (value) => {
        stored = value;
      },
      clear: () => {
        stored = null;
      },
    }).submit(command),
  ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  expect(stored).toEqual(command);
});
