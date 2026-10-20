import type { HomeView, ProductType, Target, AssetSummary } from '../contracts';
export type ContentBlockInput =
  { type: 'TEXT'; text: string } | { type: 'IMAGE'; assetId: string; alt?: string };
export type AssetPurpose =
  'PUBLIC_IMAGE' | 'PAPER_ATTACHMENT' | 'COURSE_VIDEO' | 'AFTERSALE_EVIDENCE';
export interface UploadInput {
  name: string;
  mime: string;
  bytes: number;
  purpose: AssetPurpose;
}
export interface UploadTicket {
  id: string;
  uploadUrl: string;
  uploadToken: string;
  expiresAt: string;
}
export interface AdminAsset extends AssetSummary {
  version: number;
  purpose: AssetPurpose;
  status: 'PENDING' | 'UPLOADED' | 'READY' | 'REJECTED' | 'DISABLED';
  scanStatus: string;
  publicUrl: string | null;
  sha256?: string;
}
export interface AdminCategory {
  id: string;
  version: number;
  name: string;
  type: ProductType;
  parentId: string | null;
  sortNo: number;
  enabled: boolean;
}
export type CategoryWrite = Omit<AdminCategory, 'id'>;
export interface ProductWrite {
  version: number;
  type: ProductType;
  title: string;
  subtitle: string | null;
  coverAssetId: string | null;
  priceFen: number;
  originalPriceFen: number | null;
  sortNo: number;
  categoryIds: string[];
  contentBlocks: ContentBlockInput[];
  book?: { quantityLimit: number; shippingSummary: string };
  membership?: { durationMonths: 6 | 12 };
}
export interface AdminProduct extends ProductWrite {
  id: string;
  status: 'DRAFT' | 'ON_SALE' | 'OFF_SALE';
  typeEditable?: boolean;
}
export interface HomeBannerInput {
  id: string;
  title: string;
  assetId: string;
  sortNo: number;
  enabled: boolean;
  target: Target;
}
export interface HomeShortcutInput {
  id: string;
  label: string;
  assetId: string;
  sortNo: number;
  enabled: boolean;
  target: Target;
}
export interface HomeSectionInput {
  id: string;
  type: HomeView['sections'][number]['type'];
  title: string;
  sortNo: number;
  enabled: boolean;
  moreTarget: Target | null;
  productIds: string[];
  articleId: string | null;
  paperIds?: string[];
  selectionMode?: 'MANUAL' | 'AUTO';
  displayLimit?: number;
  pinnedIds?: string[];
}
export interface HomeDraft {
  revision: number;
  banners: HomeBannerInput[];
  shortcuts: HomeShortcutInput[];
  sections: HomeSectionInput[];
}
export interface ProductForm {
  version: number;
  type: ProductType;
  title: string;
  subtitle: string;
  price: string;
  originalPrice: string;
  coverAssetId: string;
  categoryIds: string[];
  contentBlocks: ContentBlockInput[];
  quantityLimit: number;
  shippingSummary: string;
  durationMonths: 6 | 12;
  sortNo: number;
}
export interface AdminArticle {
  id: string;
  version: number;
  title: string;
  coverAssetId: string | null;
  contentBlocks: ContentBlockInput[];
  status: 'DRAFT' | 'PUBLISHED' | 'UNPUBLISHED';
  authorName?: string | null;
  categoryName?: string | null;
  publishedAt?: string | null;
  updatedAt?: string | null;
}
export type ArticleWrite = Omit<AdminArticle, 'id' | 'publishedAt' | 'updatedAt'>;
