import type { AdminAsset, ContentBlockInput } from '../content/admin-types';

/** Editor projections composed from the course/paper, product and file contracts. */
export interface AdminLearningAsset extends AdminAsset {
  /** Server media metadata. Never use course validity as a duration. */
  durationSeconds?: number;
}
export interface CourseWrite {
  productId: string;
  version: number;
  title: string;
  validityType: 'DAYS' | 'PERMANENT';
  validityDays: number | null;
  contentBlocks: ContentBlockInput[];
  teacherName?: string | null;
  productVersion?: number;
}
export interface ChapterWrite {
  version: number;
  title: string;
  videoAssetId: string;
  isTrial: boolean;
  sortNo?: number;
  durationSeconds?: number;
  enabled?: boolean;
}
export interface AdminChapter extends ChapterWrite {
  id: string;
  courseId: string;
  durationSeconds: number;
  video?: AdminLearningAsset;
  enabled?: boolean;
}
export interface AdminCourse extends CourseWrite {
  id: string;
  status: 'DRAFT' | 'ON_SALE' | 'OFF_SALE';
  chapters: AdminChapter[];
}
export interface ChapterOrderWrite {
  chapters: string[];
  version: number;
}
export interface PaperAttachmentInput {
  assetId: string;
  title: string;
  sortNo?: number;
}
export interface PaperWrite {
  version: number;
  title: string;
  stage?: string;
  schoolStage: string;
  grade: string;
  subject: string;
  year: string | null;
  paperType?: string | null;
  summary: string;
  coverAssetId?: string | null;
  /** Public sample only; original attachments must not be placed here. */
  contentBlocks: ContentBlockInput[];
  attachments?: PaperAttachmentInput[] | AdminLearningAsset[];
  attachmentMetadata?: PaperAttachmentInput[];
  attachmentIds: string[];
  visibility: 'PUBLIC' | 'HIDDEN';
  status: 'DRAFT' | 'PUBLISHED' | 'UNPUBLISHED';
}
export interface AdminPaper extends PaperWrite {
  id: string;
  attachments: AdminLearningAsset[];
}
export interface MembershipPlanWrite {
  productId: string;
  version: number;
  durationMonths: 6 | 12;
  priceFen: number;
  enabled: boolean;
  description: string;
}
export interface AdminMembershipPlan extends MembershipPlanWrite {
  id: string;
  status: 'ENABLED' | 'DISABLED';
  level: string;
}
export interface AdminMembershipRecord {
  id: string;
  userId: string;
  level: string;
  status: string;
  version: number;
  startsAt: string | null;
  expiresAt: string | null;
}
export interface AdminLearningQuery {
  keyword?: string;
  status?: string;
  schoolStage?: string;
  grade?: string;
  subject?: string;
  year?: string | number;
  stage?: string;
  paperType?: string;
  visibility?: string;
  page?: number;
  pageSize?: number;
}
export interface AdminMembershipRecordQuery {
  userId?: string;
  status?: 'NONE' | 'ACTIVE' | 'EXPIRED' | 'PENDING';
  orderId?: string;
  state?: string;
  kind?: string;
  page?: number;
  pageSize?: number;
}
