import { describe, expect, it } from 'vitest';
import { createClient } from '../src/http';
import { createSession } from '../src/session';

function client(status: number, code: string, message: string, fieldErrors = {}) {
  return createClient({
    baseUrl: '/api/v1',
    session: createSession({ get: () => null, set: () => {}, remove: () => {} }),
    transport: async () => ({
      status,
      body: { code, message, requestId: 'trace-zh', data: { fieldErrors } },
    }),
  });
}

describe('Chinese API error display', () => {
  it.each([
    [
      503,
      'WITHDRAWAL_NOT_READY',
      'Receipt identity or transfer channel is not ready',
      '收款身份或转账渠道尚未就绪，请核对收款绑定状态或联系客服',
    ],
    [403, 'DISTRIBUTOR_DISABLED', 'Distributor is disabled', '分销档案已停用，请联系客服'],
  ] as const)(
    'explains distribution error %s/%s without losing its contract',
    async (status, code, message, display) => {
      await expect(
        client(status, code, message).request('/distribution/receipt-profile', {
          method: 'POST',
          body: {},
        }),
      ).rejects.toMatchObject({ code, status, requestId: 'trace-zh', message: display });
    },
  );
  it('distinguishes missing distributor qualification from a missing API route', async () => {
    await expect(
      client(404, 'DISTRIBUTOR_NOT_ENABLED', 'Distributor profile does not exist').request(
        '/distribution/summary',
      ),
    ).rejects.toMatchObject({
      code: 'DISTRIBUTOR_NOT_ENABLED',
      status: 404,
      message: '当前账号尚未开通分销资格，请联系客服',
    });
  });
  it('explains an unserviceable shipping region before the generic conflict fallback', async () => {
    await expect(
      client(409, 'SHIPPING_UNAVAILABLE', 'No enabled shipping rule for region').request(
        '/orders/preview',
        { method: 'POST', body: { productId: 'book1', quantity: 2, addressId: 'a1' } },
      ),
    ).rejects.toMatchObject({
      code: 'SHIPPING_UNAVAILABLE',
      status: 409,
      requestId: 'trace-zh',
      message: '当前收货地区暂不支持配送，请更换地址或联系客服',
    });
  });
  it('preserves a Chinese shipping explanation without changing its error contract', async () => {
    await expect(
      client(409, 'SHIPPING_UNAVAILABLE', '该地区暂停配送，请联系客服').request('/orders/preview'),
    ).rejects.toMatchObject({
      code: 'SHIPPING_UNAVAILABLE',
      status: 409,
      requestId: 'trace-zh',
      message: '该地区暂停配送，请联系客服',
    });
  });
  it('explains the missing course configuration while preserving the validation contract', async () => {
    await expect(
      client(400, 'VALIDATION_ERROR', 'Course terms are required').request(
        '/admin/products/7/publish',
      ),
    ).rejects.toMatchObject({
      code: 'VALIDATION_ERROR',
      status: 400,
      requestId: 'trace-zh',
      message: '课程商品尚未关联课程配置，请到课程管理关联该商品并保存有效期后再上架',
    });
  });
  it('translates English failures while preserving the machine contract', async () => {
    await expect(
      client(403, 'FORBIDDEN', 'Permission denied').request('/products'),
    ).rejects.toMatchObject({
      code: 'FORBIDDEN',
      status: 403,
      requestId: 'trace-zh',
      message: '暂无操作权限',
    });
    await expect(
      client(409, 'VERSION_CONFLICT', 'Stale version').request('/products'),
    ).rejects.toMatchObject({ message: '数据已更新，请刷新后重试' });
  });
  it('keeps Chinese business explanations and localizes validation fields', async () => {
    await expect(
      client(400, 'VALIDATION_ERROR', '请先选择课程', {
        title: 'must not be blank',
        price: '价格不能为负数',
      }).request('/products'),
    ).rejects.toMatchObject({
      message: '请先选择课程',
      fieldErrors: { title: '不能为空', price: '价格不能为负数' },
    });
  });
  it('uses a useful Chinese fallback for unknown English server failures', async () => {
    await expect(
      client(500, 'NEW_ERROR', 'Unexpected database failure').request('/products'),
    ).rejects.toMatchObject({ code: 'NEW_ERROR', message: '服务暂时不可用，请稍后重试' });
  });
});
