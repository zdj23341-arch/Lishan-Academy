import { expect, it } from 'vitest';
import { prepareCommerceScenarios } from '../src/dev/commerce-scenarios';
import { createDevCatalogStore } from '../src/dev/catalog';
import { createDevCommerceHandler } from '../src/dev/commerce';
import { createDevTransport } from '../src/dev/transport';
import type { Transport, TransportRequest } from '../src/http';

const environment = { mode: 'test', provider: 'mock' };
function fixture() {
  const catalog = createDevCatalogStore();
  return createDevTransport(catalog, createDevCommerceHandler(catalog));
}
async function read(transport: Transport, path: string) {
  const response = await transport({ url: `/api/v1${path}`, method: 'GET', headers: {} });
  return (response.body as { data: Record<string, unknown> }).data;
}
it('composes three paid books with distinct shipment/return/refund states and no refunded money', async () => {
  const transport = fixture();
  const result = await prepareCommerceScenarios(transport, environment);
  expect(await read(transport, `/orders/${result.shipmentOrderId}`)).toMatchObject({
    orderStatus: 'PAID',
    fulfillmentStatus: 'PENDING_SHIPMENT',
    refundedAmountFen: 0,
  });
  expect(await read(transport, `/orders/${result.returnOrderId}`)).toMatchObject({
    fulfillmentStatus: 'SHIPPED',
    refundedAmountFen: 0,
  });
  expect(await read(transport, `/aftersales/${result.returnCaseId}`)).toMatchObject({
    type: 'RETURN_REFUND',
    status: 'REQUESTED',
  });
  expect(await read(transport, `/orders/${result.refundOrderId}`)).toMatchObject({
    fulfillmentStatus: 'PENDING_SHIPMENT',
    refundedAmountFen: 0,
  });
  expect(await read(transport, `/aftersales/${result.refundCaseId}`)).toMatchObject({
    type: 'REFUND_ONLY',
    status: 'REQUESTED',
  });
});
it('shares concurrent work and retains completed results per transport', async () => {
  const transport = fixture();
  const first = prepareCommerceScenarios(transport, environment);
  expect(prepareCommerceScenarios(transport, environment)).toBe(first);
  const result = await first;
  expect(await prepareCommerceScenarios(transport, environment)).toEqual(result);
  expect(await read(transport, '/orders')).toMatchObject({ total: 3 });
  expect(await read(transport, '/addresses')).toMatchObject({ total: 1 });
  expect(await read(fixture(), '/orders')).toMatchObject({ total: 0 });
});
it('resumes a committed create timeout using the exact immutable request and key without duplicates', async () => {
  const inner = fixture();
  const creates: TransportRequest[] = [];
  let timeout = true;
  const transport: Transport = async (request) => {
    const response = await inner(request);
    if (request.url === '/api/v1/orders' && request.method === 'POST') {
      creates.push(request);
      if (timeout) {
        timeout = false;
        throw new Error('DEV timeout');
      }
    }
    return response;
  };
  await expect(prepareCommerceScenarios(transport, environment)).rejects.toThrow('DEV timeout');
  await prepareCommerceScenarios(transport, environment);
  expect(creates[1]).toBe(creates[0]);
  expect(Object.isFrozen(creates[0]?.body)).toBe(true);
  expect(Object.isFrozen(creates[0]?.headers)).toBe(true);
  expect(await read(inner, '/orders')).toMatchObject({ total: 3 });
});
it.each([
  { mode: 'production', provider: 'mock' },
  { mode: 'development', provider: 'http' },
])('blocks dispatch for $mode/$provider', async (environment) => {
  const transport = fixture();
  await expect(prepareCommerceScenarios(transport, environment)).rejects.toThrow();
  expect(await read(transport, '/orders')).toMatchObject({ total: 0 });
  expect(await read(transport, '/addresses')).toMatchObject({ total: 0 });
});
