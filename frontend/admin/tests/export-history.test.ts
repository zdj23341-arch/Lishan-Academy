import { describe, expect, it } from 'vitest';
import { createExportHistory } from '../src/api/export-history';

describe('export history privacy', () => {
  function setup() {
    const values = new Map<string, string>();
    return { values, storage: { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => { values.set(key, value); } } };
  }
  it('isolates accounts and environments and stores only task metadata', () => {
    const { values, storage } = setup();
    const a = createExportHistory(storage, ['prod', 'real', 'https://api/a', 'a']);
    a.save({ id: '17', type: 'ORDERS', status: 'SUCCEEDED', downloadUrl: '/private', expiresAt: '2099-01-01T00:00:00Z' });
    expect(a.list()).toHaveLength(1);
    expect(createExportHistory(storage, ['prod', 'real', 'https://api/a', 'b']).list()).toEqual([]);
    expect(createExportHistory(storage, ['test', 'real', 'https://api/a', 'a']).list()).toEqual([]);
    expect([...values.values()].join()).not.toContain('/private');
  });
  it('does not trust malformed or unscoped stored records', () => {
    const { values, storage } = setup();
    values.set('jxsc_admin_export_history', '[{"id":"someone-else"}]');
    const history = createExportHistory(storage, ['test', 'real', '/api', 'a']);
    expect(history.list()).toEqual([]);
    history.save({ id: '1', type: 'ORDERS', status: 'REQUESTED', downloadUrl: null, expiresAt: null });
    const key = [...values.keys()].find((value) => value !== 'jxsc_admin_export_history')!;
    values.set(key, '{"id":"bad"}');
    expect(history.list()).toEqual([]);
    values.set(key, '[{"id":17,"type":"ORDERS","status":"REQUESTED"}]');
    expect(history.list()).toEqual([]);
  });
});
