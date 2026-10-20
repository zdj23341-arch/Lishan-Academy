import { client } from './client';

/** Mirrors the server's PaperDownload read model; the server is the only source of truth. */
export interface DownloadRecord {
  paperId: string;
  paperTitle: string;
  paperStatus: string;
  fileId: string;
  fileName: string;
  extension: string;
  mime: string;
  bytes: number;
  action: 'PREVIEW' | 'DOWNLOAD';
  lastAt: string;
  times: number;
  canDownload: boolean;
  unavailableReason: string | null;
}

export const downloadApi = {
  recent: (limit = 20) => client.request<DownloadRecord[]>('/downloads', { query: { limit } }),
};
