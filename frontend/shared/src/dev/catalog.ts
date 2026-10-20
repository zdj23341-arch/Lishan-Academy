import type { HomeView } from '../contracts';
import type { ArticleDetail, Category, PaperDetail, ProductDetail } from '../content/types';
import { devPaperSamples } from './paper-assets';
export function createDevCatalogStore() {
  const base = {
    coverUrl: null,
    originalPriceFen: null,
    status: 'ON_SALE' as const,
    subtitle: null,
    version: 1,
    contentBlocks: [
      { type: 'TEXT' as const, text: '开发示例 · 公开内容；价格和权限以服务端为准。' },
    ],
    allowedActions: ['BUY' as const],
  };
  const products: ProductDetail[] = [
    {
      ...base,
      id: 'DEV-course',
      type: 'COURSE',
      title: '开发示例 · 多章节语文课程',
      priceFen: 9900,
      course: {
        courseId: 'DEV-course',
        validityType: 'DAYS',
        validityDays: 365,
        durationMinutes: 125,
        entitlement: 'NONE',
        chaptersSummary: 2,
      },
      allowedActions: ['BUY', 'TRIAL'],
    },
    {
      ...base,
      id: 'DEV-book',
      type: 'BOOK',
      title: '开发示例 · 作文训练册',
      priceFen: 3990,
      originalPriceFen: 4900,
      book: { availableStock: 3, quantityLimit: 5, shippingSummary: '运费以下单预览为准' },
    },
    {
      ...base,
      id: 'DEV-member-6',
      type: 'MEMBERSHIP',
      title: '开发示例 · 半年试卷会员',
      priceFen: 5900,
      membership: { durationMonths: 6 },
    },
    {
      ...base,
      id: 'DEV-member-12',
      type: 'MEMBERSHIP',
      title: '开发示例 · 一年试卷会员',
      priceFen: 9900,
      membership: { durationMonths: 12 },
    },
    {
      ...base,
      id: 'DEV-course-off',
      type: 'COURSE',
      title: '开发示例 · 已下架但已购课程',
      priceFen: 9900,
      status: 'OFF_SALE',
      allowedActions: ['VIEW_CONTENT'],
      course: {
        courseId: 'DEV-course-off',
        validityType: 'PERMANENT',
        validityDays: null,
        durationMinutes: 60,
        entitlement: 'ACTIVE',
        chaptersSummary: 1,
      },
    },
    {
      ...base,
      id: 'DEV-book-empty',
      type: 'BOOK',
      title: '开发示例 · 暂无库存图书',
      priceFen: 2000,
      book: { availableStock: 0, quantityLimit: 2, shippingSummary: '暂无库存' },
    },
  ];
  const papers: PaperDetail[] = ['6', '7', '8', '9'].map((grade, index) => ({
    id: index ? `DEV-paper-${grade}` : 'DEV-paper',
    title: `开发示例 · ${grade}年级语文试卷`,
    stage: grade === '6' ? 'PRIMARY' : 'JUNIOR',
    grade,
    schoolStage: grade === '6' ? 'PRIMARY' : 'JUNIOR',
    year: '2026',
    subject: 'CHINESE',
    paperType: index % 2 ? 'FINAL_EXAM' : 'PRACTICE',
    accessCondition: 'ACTIVE_MEMBER',
    resourceStatus: 'READY',
    summary: '公开摘要；试卷会员与课程权益分别校验。',
    contentBlocks: base.contentBlocks,
    attachments: devPaperSamples.map(({ extension, mime, bytes }, i) => ({
      id: `DEV-file-${index}-${i}`,
      name: `开发示例 · 试卷.${extension.toLowerCase()}`,
      extension,
      mime,
      bytes,
      canPreview: false,
      canDownload: false,
    })),
  }));
  const articles: ArticleDetail[] = [
    {
      id: 'DEV-guide',
      title: '开发示例 · 课程评价与学习指南',
      coverUrl: null,
      contentBlocks: [
        { type: 'TEXT', text: '公开阅读不需要登录。课程购买不赠送试卷会员，会员不解锁付费课程。' },
        ...Array.from({ length: 6 }, (_, i) => ({
          type: 'TEXT' as const,
          text: `第${i + 1}段：教学资料说明。购买、播放、下载将在后续阶段接入，当前不产生交易或文件授权。`,
        })),
      ],
    },
  ];
  const home: HomeView = {
    revision: 1,
    banners: products.slice(0, 3).map((p, i) => ({
      id: `DEV-banner-${i}`,
      title: p.title,
      imageUrl:
        [
          '/static/design/banners/books.png',
          '/static/design/banners/writing.png',
          '/static/design/banners/reading.png',
        ][i] ?? '/static/design/banners/books.png',
      target: { type: 'PRODUCT', id: p.id, filters: {} },
    })),
    shortcuts: ['视频课程', '作文书籍', '会员试卷', '我的学习', '会员中心', '订单物流'].map(
      (label, i): HomeView['shortcuts'][number] => ({
        id: `DEV-shortcut-${i}`,
        label,
        iconUrl: '',
        target:
          i === 0
            ? { type: 'PRODUCT_LIST', id: null, filters: { type: 'COURSE' } }
            : i === 1
              ? { type: 'PRODUCT_LIST', id: null, filters: { type: 'BOOK' } }
              : i === 2
                ? { type: 'PAPER_LIST', id: null, filters: {} }
                : i === 3
                  ? { type: 'LEARNING', id: null, filters: {} }
                  : i === 4
                    ? { type: 'MEMBERSHIP', id: null, filters: {} }
                    : { type: 'ORDER_LIST', id: null, filters: {} },
      }),
    ),
    sections: [
      {
        id: 'DEV-books',
        type: 'BOOK_GRID',
        title: '语文精选图书',
        moreTarget: { type: 'PRODUCT_LIST', id: null, filters: { type: 'BOOK' } },
        products: [products[1]!],
        article: null,
      },
      {
        id: 'DEV-courses',
        type: 'COURSE_GRID',
        title: '热门视频课程',
        moreTarget: { type: 'PRODUCT_LIST', id: null, filters: { type: 'COURSE' } },
        products: [products[0]!],
        article: null,
      },
      {
        id: 'DEV-papers',
        type: 'PAPER_GRID',
        title: '会员试卷下载',
        moreTarget: { type: 'PAPER_LIST', id: null, filters: {} },
        products: [],
        papers: papers.slice(0, 2).map((paper) => ({
          id: paper.id,
          title: paper.title,
          coverUrl: paper.coverUrl ?? null,
          stage: paper.stage,
          schoolStage: paper.schoolStage,
          grade: paper.grade,
          year: paper.year,
          attachmentFormats: paper.attachments.map((file) => file.extension),
          accessCondition: paper.accessCondition,
          resourceStatus: paper.resourceStatus,
          subject: paper.subject,
          paperType: paper.paperType,
          summary: paper.summary,
        })),
        article: null,
      },
    ],
  };
  const categories: Category[] = [{ id: 'DEV-chinese', name: '语文', type: 'BOOK' }];
  const productCategoryIds: Record<string, string[]> = Object.fromEntries(
    products.map((product) => [product.id, product.type === 'BOOK' ? ['DEV-chinese'] : []]),
  );
  return { home, products, papers, articles, categories, productCategoryIds };
}
export type DevCatalogStore = ReturnType<typeof createDevCatalogStore>;
