import { expect, it } from 'vitest';
import { auditSummary } from '../src/api/audit-summary';
it('renders real public changes without exposing names, tokens, nested objects or arbitrary strings', () => {
  const summary = auditSummary({ before: { status: 'ACTIVE', token: 'secret', realName: '张三' }, after: { status: 'DISABLED', token: 'secret2', password: 'password', nested: { secret: true } } });
  expect(summary).toBe('status: ACTIVE → DISABLED');
  expect(auditSummary({ before: null, after: { status: 'secret-token' } })).not.toContain('secret-token');
});
it('shows nested finance limits and distributor rule changes without dumping private snapshots', () => {
  expect(auditSummary({ before: { version: 1, limits: { minFen: 100 } }, after: { version: 2, limits: { minFen: 200 } } })).toContain('limits.minFen: 100 → 200');
  const summary = auditSummary({ before: { rules: [{ distributorId: '42', rateBps: 1000, enabled: true }] }, after: { rules: [{ distributorId: '42', rateBps: 2000, enabled: false, secret: 'private' }] } });
  expect(summary).toContain('rules[42].rateBps: 1000 → 2000');
  expect(summary).toContain('rules[42].enabled: true → false');
  expect(summary).not.toContain('private');
});
