import type { AdminAsset } from '../content/admin-types';
import { validateUpload } from '../content/upload';
import { normalizeContentBlocks } from '../content/validation';
import type {
  AdminCourse,
  AdminLearningAsset,
  CourseWrite,
  ChapterWrite,
  PaperWrite,
  MembershipPlanWrite,
} from './admin-types';

export type LearningFieldErrors = Record<string, string>;
function titleErrors(title?: string): LearningFieldErrors {
  return !title?.trim() || title.trim().length > 100 ? { title: '请输入1–100字标题' } : {};
}
export function validateCourse(input: Partial<CourseWrite>): LearningFieldErrors {
  const errors = titleErrors(input.title);
  if (!input.productId?.trim()) errors.productId = '请选择课程商品';
  if (input.validityType === 'DAYS') {
    if (!Number.isSafeInteger(input.validityDays) || input.validityDays! < 1)
      errors.validityDays = '观看有效期须为正整数天数';
  } else if (input.validityType === 'PERMANENT') {
    if (input.validityDays !== null) errors.validityDays = '永久有效课程不填写天数';
  } else errors.validityType = '请选择观看有效期类型';
  try {
    normalizeContentBlocks(input.contentBlocks ?? []);
  } catch {
    errors.contentBlocks = '公开介绍格式无效';
  }
  return errors;
}
export function isReadyLearningAsset(
  asset: AdminAsset | undefined,
  purpose: 'COURSE_VIDEO' | 'PAPER_ATTACHMENT',
): boolean {
  if (!asset || asset.status !== 'READY' || asset.purpose !== purpose) return false;
  try {
    validateUpload(asset);
    return true;
  } catch {
    return false;
  }
}
export function validateChapter(
  input: Partial<ChapterWrite>,
  asset?: AdminLearningAsset,
): LearningFieldErrors {
  const errors = titleErrors(input.title);
  if (typeof input.isTrial !== 'boolean') errors.isTrial = '请选择是否试看';
  if (!input.videoAssetId?.trim()) errors.videoAssetId = '请绑定已就绪视频';
  else if (
    asset &&
    (!isReadyLearningAsset(asset, 'COURSE_VIDEO') || asset.id !== input.videoAssetId)
  )
    errors.videoAssetId = '视频尚未就绪';
  if (
    !Number.isInteger(input.durationSeconds) ||
    input.durationSeconds! < 1 ||
    input.durationSeconds! > 86400
  )
    errors.durationSeconds = '请输入实际视频时长（1–86400秒）';
  return errors;
}
export function validateCoursePublish(course: AdminCourse): LearningFieldErrors {
  const errors = validateCourse(course);
  if (!course.chapters.length) errors.chapters = '至少配置一个已就绪章节';
  for (const chapter of course.chapters) {
    if (
      !isReadyLearningAsset(chapter.video, 'COURSE_VIDEO') ||
      Object.keys(validateChapter(chapter, chapter.video)).length
    )
      errors.chapters = '请完善章节并绑定已就绪且有时长的视频';
  }
  return errors;
}
export function validatePaper(
  input: Partial<PaperWrite>,
  assets?: AdminLearningAsset[],
): LearningFieldErrors {
  const errors = titleErrors(input.title);
  for (const [key, label, max] of [
    ['schoolStage', '学段', 40],
    ['grade', '年级', 20],
    ['subject', '学科', 40],
  ] as const) {
    if (!input[key]?.trim()) errors[key] = `请选择${label}`;
    else if (input[key]!.length > max) errors[key] = `${label}不能超过${max}字`;
  }
  if (
    input.year !== null &&
    input.year !== undefined &&
    (!/^\d{4}$/.test(String(input.year)) || Number(input.year) < 1900 || Number(input.year) > 2200)
  )
    errors.year = '年份须为1900–2200之间的四位整数或留空';
  if ((input.summary?.length ?? 0) > 1000) errors.summary = '内容介绍不能超过1000字';
  if ((input.paperType?.length ?? 0) > 40) errors.paperType = '试卷类型不能超过40字';
  if (!['DRAFT', 'PUBLISHED', 'UNPUBLISHED'].includes(input.status ?? ''))
    errors.status = '请选择发布状态';
  if (
    !Array.isArray(input.attachmentIds) ||
    !input.attachmentIds.length ||
    input.attachmentIds.some((id) => typeof id !== 'string' || !id.trim()) ||
    new Set(input.attachmentIds).size !== input.attachmentIds.length
  )
    errors.attachmentIds = '至少绑定一个有效且不重复的文件';
  else if (input.attachmentIds.length > 30) errors.attachmentIds = '原始附件最多绑定30份';
  else if (
    assets &&
    input.attachmentIds.some(
      (id) =>
        !isReadyLearningAsset(
          assets.find((asset) => asset.id === id),
          'PAPER_ATTACHMENT',
        ),
    )
  )
    errors.attachmentIds = '试卷附件必须是已就绪的Word、PDF或图片';
  for (const id of input.attachmentIds ?? []) {
    const metadata =
      input.attachmentMetadata?.find((a) => a.assetId === id) ??
      input.attachments?.find((a) => 'assetId' in a && a.assetId === id);
    if (metadata && 'title' in metadata && (!metadata.title.trim() || metadata.title.length > 200))
      errors.attachmentIds = '附件名称须为1–200字，请重新绑定符合要求的文件';
  }
  try {
    const blocks = normalizeContentBlocks(input.contentBlocks ?? []);
    if (blocks.length > 100) errors.contentBlocks = '公开样张最多100段';
    else if (blocks.some((block) => block.type === 'TEXT' && block.text.length > 10000))
      errors.contentBlocks = '公开样张每段文字不能超过10000字';
  } catch {
    errors.contentBlocks = '公开样张格式无效';
  }
  return errors;
}
export function validateMembershipPlan(input: Partial<MembershipPlanWrite>): LearningFieldErrors {
  const errors: LearningFieldErrors = {};
  if (!input.productId?.trim()) errors.productId = '会员商品编号不能为空';
  if (input.durationMonths !== 6 && input.durationMonths !== 12)
    errors.durationMonths = '会员周期仅支持6或12个月';
  if (typeof input.enabled !== 'boolean') errors.enabled = '请选择启用状态';
  return errors;
}
