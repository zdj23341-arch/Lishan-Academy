import { areaList } from '@vant/area-data';

export type RegionCodes = [string, string, string];
export function regionChildren(level: 'province' | 'city' | 'district', parent = '') {
  const table =
    level === 'province'
      ? areaList.province_list
      : level === 'city'
        ? areaList.city_list
        : areaList.county_list;
  if (level !== 'province' && !/^\d{6}$/.test(parent)) return [];
  return Object.entries(table)
    .filter(
      ([code]) =>
        level === 'province' ||
        code.slice(0, level === 'city' ? 2 : 4) === parent.slice(0, level === 'city' ? 2 : 4),
    )
    .map(([code, name]) => ({ code, name }));
}
export function selectRegion(current: RegionCodes, level: 0 | 1 | 2, code: string): RegionCodes {
  const next: RegionCodes = [...current];
  if (next[level] === code) return next;
  next[level] = code;
  for (let index = level + 1; index < 3; index++) next[index] = '';
  return next;
}
export function regionLabel(codes: RegionCodes) {
  const tables = [areaList.province_list, areaList.city_list, areaList.county_list];
  return codes
    .map((code, index) => tables[index]?.[code] ?? code)
    .filter(Boolean)
    .join(' ');
}
