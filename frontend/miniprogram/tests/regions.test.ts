import { describe, expect, it } from 'vitest';
import { regionChildren, regionLabel, selectRegion } from '../src/utils/regions';

describe('address administrative regions', () => {
  it('returns codes and restricts child options to their selected parent', () => {
    expect(regionChildren('province')).toContainEqual({ code: '110000', name: '北京市' });
    expect(regionChildren('city', '110000')).toEqual([{ code: '110100', name: '北京市' }]);
    expect(regionChildren('district', '110100')).toContainEqual({ code: '110108', name: '海淀区' });
    expect(regionChildren('district', '')).toEqual([]);
  });
  it('clears descendants when a parent changes and never invents a default address', () => {
    expect(selectRegion(['110000', '110100', '110108'], 0, '310000')).toEqual(['310000', '', '']);
    expect(selectRegion(['110000', '110100', '110108'], 0, '110000')).toEqual([
      '110000',
      '110100',
      '110108',
    ]);
    expect(regionLabel(['110000', '110100', '110108'])).toBe('北京市 北京市 海淀区');
    expect(regionLabel(['990000', '', ''])).toBe('990000');
  });
});
