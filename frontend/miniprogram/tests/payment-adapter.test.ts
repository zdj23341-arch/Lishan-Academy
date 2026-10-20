import { expect, it, vi } from 'vitest';
import { createPlatformPaymentAdapter } from '../src/api/payment';
vi.mock('../src/api/environment', () => ({ environment: { mode: 'test' } }));
const params = {
  timeStamp: '1790000000',
  nonceStr: 'n',
  package: 'prepay_id=test',
  signType: 'RSA',
  paySign: 'signed',
  untrustedExtra: 'discard',
};
it('passes only validated WeChat fields and returns the native cancellation unchanged', async () => {
  const request = vi.fn(async () => 'cancel' as const);
  const adapter = createPlatformPaymentAdapter('production', request);
  expect(await adapter({ provider: 'WECHAT_PAY', paymentId: 'p', state: 'PENDING', params })).toBe(
    'cancel',
  );
  expect(request).toHaveBeenCalledWith({
    timeStamp: params.timeStamp,
    nonceStr: 'n',
    package: params.package,
    signType: 'RSA',
    paySign: 'signed',
  });
});
it('never calls native payment for malformed params, unknown channels or simulated production responses', async () => {
  const request = vi.fn();
  const adapter = createPlatformPaymentAdapter('production', request);
  for (const response of [
    { provider: 'DEV', params },
    { provider: 'WECHAT_PAY', simulated: true, params },
    { provider: 'WECHAT_PAY', params: { ...params, signType: 'MD5' } },
  ])
    await expect(adapter({ ...response, paymentId: 'p', state: 'PENDING' })).rejects.toThrow();
  expect(request).not.toHaveBeenCalled();
});
it('development prepay does not impersonate a successful native payment', async () => {
  const request = vi.fn();
  expect(
    await createPlatformPaymentAdapter(
      'test',
      request,
    )({ provider: 'DEV', paymentId: 'p', state: 'PENDING', params: { simulated: true } }),
  ).toBe('cancel');
  expect(request).not.toHaveBeenCalled();
});
