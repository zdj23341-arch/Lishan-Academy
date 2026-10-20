import { ApiError, type ExportTask } from '@jxsc/shared';

export async function fetchPrivateExport(
  task: ExportTask,
  context: { apiBaseUrl: string; accessToken: string },
): Promise<Blob> {
  if (
    task.status !== 'SUCCEEDED' ||
    !task.downloadUrl ||
    !task.expiresAt ||
    !Number.isFinite(Date.parse(task.expiresAt)) ||
    Date.parse(task.expiresAt) <= Date.now()
  )
    throw new Error('下载链接无效或已过期，请重新生成任务');
  const base = new URL(context.apiBaseUrl, window.location.href);
  const url = new URL(task.downloadUrl, base);
  const expectedPath = `${base.pathname.replace(/\/+$/, '')}/admin/exports/${encodeURIComponent(task.id)}/content`;
  if (
    !['https:', 'http:'].includes(url.protocol) ||
    url.origin !== base.origin ||
    url.pathname !== expectedPath ||
    url.search ||
    url.hash ||
    url.username ||
    url.password
  )
    throw new Error('导出地址与当前 API 不一致，已取消下载');
  if (!context.accessToken) throw new Error('请重新登录后下载');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(url.href, {
      headers: { Authorization: `Bearer ${context.accessToken}` },
      redirect: 'error',
      credentials: 'omit',
      signal: controller.signal,
    });
    if (!response.ok)
      throw new ApiError(
        response.status === 401
          ? 'UNAUTHORIZED'
          : response.status === 403
            ? 'FORBIDDEN'
            : 'EXPORT_DOWNLOAD_FAILED',
        `下载失败（HTTP ${response.status}），请刷新任务或重新登录`,
        null,
        {},
        response.status,
      );
    return await response.blob();
  } finally {
    clearTimeout(timer);
  }
}
