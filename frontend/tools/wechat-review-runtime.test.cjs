const { test } = require('node:test');
const assert = require('node:assert/strict');
const { ensureReviewSession } = require('./wechat-review-runtime.cjs');
const { readRuntimeBuildId } = require('./wechat-review-runtime.cjs');
const { WebSocketServer } = require('ws');
const { waitForRuntimeConfigurations } = require('./wechat-review-runtime.cjs');

test('rejects stale component registrations even when the script candidate is current', async () => {
  const expected = {
    'pages/order/list.json': { usingComponents: { 'ui-state': '../../components/ui/UiState' } },
  };
  await assert.rejects(
    async () =>
      waitForRuntimeConfigurations(expected, 0, async () => ({
        'pages/order/list.json': { usingComponents: {} },
      })),
    /组件配置.*pages\/order\/list.json/,
  );
  assert.equal(await waitForRuntimeConfigurations(expected, 0, async () => expected), 1);
});

test('reuses a matching candidate instead of launching a duplicate on its occupied port', async () => {
  let launches = 0;
  const result = await ensureReviewSession({
    expectedId: 'review-existing',
    read: async () => 'review-existing',
    launch: async () => {
      launches++;
    },
  });
  assert.equal(launches, 0);
  assert.equal(result, 'reuse');
});
test('refuses to open over an unverified occupied project', async () => {
  let launches = 0;
  await assert.rejects(
    ensureReviewSession({
      expectedId: 'review-existing',
      read: async () => 'another-project',
      launch: async () => {
        launches++;
      },
    }),
    /运行标识/,
  );
  assert.equal(launches, 0);
});
test('opens exactly once when the automation port is unused', async () => {
  let launches = 0;
  const result = await ensureReviewSession({
    expectedId: null,
    read: async () => null,
    launch: async () => {
      launches++;
    },
  });
  assert.equal(result, 'launch');
  assert.equal(launches, 1);
});
test('propagates a non-responsive occupied port and does not launch a second project', async () => {
  let launches = 0;
  await assert.rejects(
    ensureReviewSession({
      expectedId: 'review-existing',
      read: async () => {
        throw Error('protocol timeout');
      },
      launch: async () => {
        launches++;
      },
    }),
    /timeout/,
  );
  assert.equal(launches, 0);
});
test('reads the actual automation protocol response rather than equating an open port with the candidate', async () => {
  const server = new WebSocketServer({ port: 0, host: '127.0.0.1' });
  await new Promise((resolve) => server.once('listening', resolve));
  server.on('connection', (socket) =>
    socket.on('message', (message) => {
      const request = JSON.parse(message);
      assert.equal(request.method, 'App.callFunction');
      socket.send(
        JSON.stringify({ id: request.id, result: { result: 'review-protocol-candidate' } }),
      );
    }),
  );
  try {
    assert.equal(await readRuntimeBuildId(server.address().port), 'review-protocol-candidate');
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
