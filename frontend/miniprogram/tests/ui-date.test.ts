import { expect, it } from 'vitest';
import { displayDate } from '../src/presentation/date';
it('shows the China calendar date across UTC midnight and handles missing dates', () => {
  expect(displayDate('2026-10-09T23:30:00Z')).toBe('2026.10.10');
  expect(displayDate(null)).toBe('—');
  expect(displayDate('not-a-date')).toBe('—');
});
