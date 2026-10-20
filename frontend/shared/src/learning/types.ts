import type { AssetSummary } from '../contracts';

export type FileAction = 'PREVIEW' | 'DOWNLOAD';
export interface FileAuthorization {
  file: AssetSummary;
  url: string;
  expiresAt: string;
}

export interface ChapterView {
  id: string;
  title: string;
  durationSeconds: number;
  isTrial: boolean;
  canPlay: boolean;
  lastPositionSeconds: number;
  completed: boolean;
}
export interface CourseView {
  id: string;
  productId: string;
  title: string;
  entitlement: 'NONE' | 'ACTIVE' | 'EXPIRED';
  expiresAt: string | null;
  allowedActions: string[];
  chapters: ChapterView[];
  completedChapters?: number;
  totalChapters?: number;
  status?: 'NONE' | 'ACTIVE' | 'PENDING' | 'EXPIRED';
  resume?: CourseResume | null;
}
export interface CourseResume {
  chapterId: string;
  chapterTitle: string;
  positionSeconds: number;
  lastLearnedAt: string;
}
export interface PlayAuthorization {
  url: string;
  expiresAt: string;
  resumeSeconds: number;
}
export interface CourseProgress {
  positionSeconds: number;
  completed: boolean;
}
export interface CourseFilters {
  status?: 'IN_PROGRESS' | 'COMPLETED' | 'EXPIRED';
  page?: number;
  pageSize?: number;
}
