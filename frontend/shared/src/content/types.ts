import type { AssetSummary, ContentBlock, ProductCard, ProductType } from '../contracts';
export type ProductSort = 'DEFAULT' | 'PRICE_ASC' | 'PRICE_DESC' | 'NEWEST';
export interface ProductFilters {
  keyword?: string;
  type?: ProductType;
  categoryId?: string;
  sort?: ProductSort;
  page?: number;
  pageSize?: number;
}
export interface PaperFilters {
  keyword?: string;
  grade?: string;
  /** Current backend classification field. */
  schoolStage?: string;
  /** Legacy backend alias retained for gradual migration. */
  stage?: string;
  year?: string;
  subject?: string;
  /** Current backend classification field. */
  paperType?: string;
  /** Legacy backend alias retained for gradual migration. */
  type?: string;
  page?: number;
  pageSize?: number;
}
export interface Category {
  id: string;
  name: string;
  type: ProductType;
}
export interface CourseInfo {
  courseId: string;
  validityType: 'PERMANENT' | 'DAYS';
  validityDays: number | null;
  entitlement: 'NONE' | 'ACTIVE' | 'EXPIRED';
  chaptersSummary: number;
  durationMinutes?: number | null;
}
export interface BookInfo {
  availableStock: number;
  quantityLimit: number;
  shippingSummary: string;
}
type DetailBase = Omit<ProductCard, 'type'> & {
  version: number;
  contentBlocks: ContentBlock[];
  allowedActions: ('BUY' | 'LEARN' | 'VIEW_CONTENT' | 'TRIAL')[];
};
export type ProductDetail = DetailBase &
  (
    | { type: 'COURSE'; course: CourseInfo; book?: never; membership?: never }
    | { type: 'BOOK'; book: BookInfo; course?: never; membership?: never }
    | { type: 'MEMBERSHIP'; membership: { durationMonths: 6 | 12 }; course?: never; book?: never }
  );
export interface PaperCard {
  id: string;
  title: string;
  grade: string;
  stage?: string | null;
  schoolStage?: string | null;
  year: string | number | null;
  subject: string;
  paperType?: string | null;
  summary?: string | null;
  coverUrl?: string | null;
  attachmentFormats?: string[];
  accessCondition?: string | null;
  resourceStatus?: string | null;
  /** Optional list metadata and current-account permissions; never includes an authorization URL.
   * Missing summaries must be shown as unknown. Detail responses require this same shape.
   */
  attachments?: (AssetSummary & { canPreview: boolean; canDownload: boolean })[];
}
export interface PaperDetail extends PaperCard {
  contentBlocks: ContentBlock[];
  attachments: (AssetSummary & {
    title?: string | null;
    canPreview: boolean;
    canDownload: boolean;
  })[];
}
export interface ArticleDetail {
  id: string;
  title: string;
  coverUrl: string | null;
  contentBlocks: ContentBlock[];
  authorName?: string | null;
  categoryName?: string | null;
  publishedAt?: string | null;
  updatedAt?: string | null;
}
