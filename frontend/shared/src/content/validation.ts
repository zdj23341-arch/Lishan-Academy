import { ApiError } from '../http';
import { parseYuan } from '../money';
import { targetToRoute } from './routing';
import type { ContentBlockInput, HomeDraft, ProductForm, ProductWrite } from './admin-types';
export function normalizeContentBlocks(input: unknown): ContentBlockInput[] {
  const invalid = () =>
    new ApiError(
      'VALIDATION_ERROR',
      '图文仅支持文字或已上传图片',
      null,
      { contentBlocks: '请填写文字或选择已上传图片' },
      422,
    );
  if (!Array.isArray(input)) throw invalid();
  return input.map((block: unknown) => {
    if (!block || typeof block !== 'object') throw invalid();
    const value = block as Record<string, unknown>;
    if (value.type === 'TEXT' && typeof value.text === 'string' && value.text.trim())
      return { type: 'TEXT', text: value.text.trim() };
    if (value.type === 'IMAGE' && typeof value.assetId === 'string' && value.assetId.trim()) {
      if (value.alt !== undefined && typeof value.alt !== 'string') throw invalid();
      return {
        type: 'IMAGE',
        assetId: value.assetId.trim(),
        ...(typeof value.alt === 'string' ? { alt: value.alt.trim() } : {}),
      };
    }
    throw invalid();
  });
}
/** The strict backend DTO accepts type, text and assetId; editor-only notes stay local. */
export function contentBlocksForWrite(input: unknown): ContentBlockInput[] {
  return normalizeContentBlocks(input).map((block) =>
    block.type === 'IMAGE' ? { type: 'IMAGE', assetId: block.assetId } : block,
  );
}
export function moveItem<T>(items: T[], from: number, to: number): T[] {
  if (![from, to].every((index) => Number.isInteger(index) && index >= 0 && index < items.length))
    throw new Error('排序位置无效');
  const copy = [...items];
  const [item] = copy.splice(from, 1);
  copy.splice(to, 0, item!);
  return copy;
}
export function productWrite(form: ProductForm): ProductWrite {
  const fields: Record<string, string> = {};
  if (!form.title.trim() || form.title.trim().length > 100) fields.title = '标题应为1–100字';
  let priceFen = 0;
  let originalPriceFen: number | null = null;
  try {
    priceFen = parseYuan(form.price);
    if (priceFen < 1 || priceFen > 100000000) fields.price = '价格须在0.01–1000000元之间';
  } catch {
    fields.price = '请输入正确价格';
  }
  if (form.originalPrice.trim())
    try {
      originalPriceFen = parseYuan(form.originalPrice);
      if (originalPriceFen < priceFen || originalPriceFen > 100000000)
        fields.originalPrice = '原价不得低于售价或超过金额上限';
    } catch {
      fields.originalPrice = '原价格式不正确';
    }
  if (!form.coverAssetId) fields.coverAssetId = '请选择已上传封面';
  if (!form.categoryIds.length) fields.categoryIds = '请选择分类';
  if (!form.contentBlocks.length) fields.contentBlocks = '请填写介绍';
  if (
    form.type === 'BOOK' &&
    (!Number.isInteger(form.quantityLimit) || form.quantityLimit < 1 || form.quantityLimit > 99)
  )
    fields.quantityLimit = '限购数量应为1–99';
  if (form.type === 'BOOK' && !form.shippingSummary.trim())
    fields.shippingSummary = '请填写配送说明';
  if (form.type === 'MEMBERSHIP' && ![6, 12].includes(form.durationMonths))
    fields.durationMonths = '会员期限应为6或12个月';
  if (Object.keys(fields).length)
    throw new ApiError('VALIDATION_ERROR', '请修正表单字段', null, fields);
  const result: ProductWrite = {
    version: form.version,
    type: form.type,
    title: form.title.trim(),
    subtitle: form.subtitle.trim() || null,
    coverAssetId: form.coverAssetId,
    priceFen,
    originalPriceFen,
    sortNo: form.sortNo,
    categoryIds: [...form.categoryIds],
    contentBlocks: normalizeContentBlocks(form.contentBlocks),
  };
  if (form.type === 'BOOK')
    result.book = {
      quantityLimit: form.quantityLimit,
      shippingSummary: form.shippingSummary.trim(),
    };
  if (form.type === 'MEMBERSHIP') result.membership = { durationMonths: form.durationMonths };
  return result;
}
export function validateHomeDraft(draft: HomeDraft, publishing = false): Record<string, string> {
  const fields: Record<string, string> = {};
  for (const group of ['banners', 'shortcuts'] as const) {
    const ids = new Set<string>();
    draft[group].forEach((item, index) => {
      const label = 'title' in item ? item.title : item.label;
      if (!item.id || ids.has(item.id)) fields[`${group}.${index}.id`] = '标识不能为空或重复';
      ids.add(item.id);
      if (!publishing || !item.enabled) return;
      if (!label.trim() || !item.assetId) fields[`${group}.${index}`] = '名称和图片必填';
      try {
        targetToRoute(item.target);
      } catch {
        fields[`${group}.${index}.target`] = '请选择有效的内部目标';
      }
    });
  }
  const ids = new Set<string>();
  draft.sections.forEach((item, index) => {
    if (!item.id || ids.has(item.id)) fields[`sections.${index}.id`] = '模块标识不能为空或重复';
    ids.add(item.id);
    const automatic = item.selectionMode === 'AUTO';
    if (automatic && !['BOOK_GRID', 'COURSE_GRID', 'PAPER_GRID'].includes(item.type))
      fields[`sections.${index}.selectionMode`] = '此栏目只支持手动精选';
    if (
      automatic &&
      (!Number.isInteger(item.displayLimit ?? 4) ||
        (item.displayLimit ?? 4) < 1 ||
        (item.displayLimit ?? 4) > 50)
    )
      fields[`sections.${index}.displayLimit`] = '展示数量应为1–50的整数';
    if (!publishing || !item.enabled) return;
    if (!item.title.trim()) fields[`sections.${index}.title`] = '模块名称必填';
    if (
      !automatic &&
      ['PRODUCT_GRID', 'BOOK_GRID', 'COURSE_GRID'].includes(item.type) &&
      !item.productIds.length
    )
      fields[`sections.${index}.productIds`] = '请选择商品';
    if (!automatic && item.type === 'PAPER_GRID' && !(item.paperIds?.length ?? 0))
      fields[`sections.${index}.paperIds`] = '请选择试卷';
    if (item.type === 'ARTICLE_CARD' && !item.articleId)
      fields[`sections.${index}.articleId`] = '请选择图文';
    if (item.moreTarget)
      try {
        targetToRoute(item.moreTarget);
      } catch {
        fields[`sections.${index}.moreTarget`] = '查看更多目标无效';
      }
  });
  return fields;
}
export function validateHomePublish(draft: HomeDraft): Record<string, string> {
  const fields = validateHomeDraft(draft, true);
  if (draft.banners.filter((item) => item.enabled).length !== 3)
    fields.banners = '发布需要3张启用轮播';
  const enabledShortcuts = draft.shortcuts.filter((item) => item.enabled).length;
  if (enabledShortcuts !== 6 && enabledShortcuts !== 8) fields.shortcuts = '发布需要6或8个启用入口';
  return fields;
}
