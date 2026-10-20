const net = require('node:net');
const { isDeepStrictEqual } = require('node:util');

async function ensureReviewSession({ expectedId, read, launch }) {
  const current = await read();
  if (current !== null) {
    if (!expectedId || current !== expectedId) {
      throw new Error('9420端口已占用，但运行标识不属于当前验收候选；停止打开，避免重复项目');
    }
    return 'reuse';
  }
  await launch();
  return 'launch';
}

function isListening(port) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: '127.0.0.1', port });
    socket.setTimeout(2000);
    socket.once('connect', () => {
      socket.destroy();
      resolve(true);
    });
    socket.once('error', (error) => {
      socket.destroy();
      if (error.code === 'ECONNREFUSED') resolve(false);
      else reject(error);
    });
    socket.once('timeout', () => {
      socket.destroy();
      reject(new Error('自动化端口连接超时'));
    });
  });
}

async function readRuntimeFunction(functionDeclaration, args, port = 9420) {
  if (!(await isListening(port))) return null;
  if (typeof WebSocket === 'undefined') throw new Error('运行验收命令需Node.js 22或更高版本');
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}`);
    const id = 'jxsc-read-build-id';
    let settled = false;
    const timer = setTimeout(() => finish(new Error('自动化端口已占用但运行标识读取超时')), 4000);
    function finish(error, value) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      ws.close();
      if (error) reject(error);
      else resolve(value);
    }
    ws.addEventListener('open', () =>
      ws.send(
        JSON.stringify({
          id,
          method: 'App.callFunction',
          params: { functionDeclaration, args },
        }),
      ),
    );
    ws.addEventListener('message', (event) => {
      try {
        const reply = JSON.parse(event.data);
        if (reply.id !== id) return;
        if (reply.error) finish(new Error(reply.error.message));
        else finish(null, reply.result?.result ?? 'unmarked-project');
      } catch (error) {
        finish(error);
      }
    });
    ws.addEventListener('error', () => finish(new Error('自动化端口已占用但WebSocket协议不可用')));
  });
}

async function readRuntimeBuildId(port = 9420) {
  return readRuntimeFunction('() => globalThis.__JXSC_REVIEW_BUILD_ID__', [], port);
}

async function readRuntimeConfigurations(paths, port = 9420) {
  return readRuntimeFunction(
    `(paths) => {
      const code = typeof __wxAppCode__ !== 'undefined' ? __wxAppCode__ : globalThis.__wxAppCode__;
      return Object.fromEntries(paths.map(path => [path, code?.[path] ?? null]));
    }`,
    [paths],
    port,
  );
}

async function waitForRuntimeConfigurations(
  expected,
  timeout = 30000,
  read = readRuntimeConfigurations,
) {
  const paths = Object.keys(expected);
  const deadline = Date.now() + timeout;
  let mismatches = paths;
  let errorMessage = '';
  do {
    try {
      const actual = await read(paths);
      mismatches = paths.filter((path) => !isDeepStrictEqual(actual?.[path], expected[path]));
      if (!mismatches.length) return paths.length;
    } catch (error) {
      errorMessage = error.message;
    }
    if (Date.now() >= deadline) break;
    await new Promise((resolve) => setTimeout(resolve, 500));
  } while (Date.now() <= deadline);
  throw new Error(
    `微信组件配置尚未更新：${mismatches.join(', ')}${errorMessage ? '; ' + errorMessage : ''}。本次不算验收构建成功`,
  );
}

async function waitForRuntimeBuildId(expectedId, timeout = 30000) {
  const deadline = Date.now() + timeout;
  let actual;
  do {
    try {
      actual = await readRuntimeBuildId();
    } catch (error) {
      actual = error.message;
    }
    if (actual === expectedId) return actual;
    await new Promise((resolve) => setTimeout(resolve, 500));
  } while (Date.now() < deadline);
  throw new Error(
    `候选文件已生成但微信尚未运行本次标识 ${expectedId}；实际：${actual}。本次不算验收构建成功`,
  );
}
module.exports = {
  ensureReviewSession,
  readRuntimeBuildId,
  waitForRuntimeBuildId,
  readRuntimeConfigurations,
  waitForRuntimeConfigurations,
};
