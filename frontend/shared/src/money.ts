export function parseYuan(value: string): number {
  const normalized = value.trim();
  if (!/^\d+(?:\.\d{1,2})?$/.test(normalized)) throw new Error('请输入最多两位小数的非负金额');
  const [whole = '0', fraction = ''] = normalized.split('.');
  const result = Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
  if (!Number.isSafeInteger(result)) throw new Error('金额超出支持范围');
  return result;
}
export function formatFen(value: number): string {
  if (!Number.isSafeInteger(value)) throw new Error('金额必须是安全整数分');
  const absolute = Math.abs(value);
  return `${value < 0 ? '-' : ''}${Math.floor(absolute / 100)}.${String(absolute % 100).padStart(2, '0')}`;
}
export function parsePercent(value: string): number {
  const bps = parseYuan(value);
  if (bps > 10000) throw new Error('比例必须在0–100%之间');
  return bps;
}
