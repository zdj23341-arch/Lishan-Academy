/** Modern China business wall time (Asia/Shanghai, UTC+08:00), without Intl. */
function shanghaiInstant(value: string | null | undefined): Date | null {
  // An instant must carry its own offset; never infer the device's timezone.
  if (!value || !/(?:Z|[+-]\d{2}:\d{2})$/.test(value)) return null;
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return null;
  const date = new Date(timestamp + 8 * 60 * 60 * 1000);
  return Number.isFinite(date.getTime()) ? date : null;
}
const pad = (value: number) => String(value).padStart(2, '0');

export function formatShanghaiDateTime(value: string | null | undefined): string {
  const date = shanghaiInstant(value);
  if (!date) return '—';
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())} ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}`;
}
