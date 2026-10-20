const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { stampCandidate } = require('./wechat-review-candidate.cjs');

test('invalidates component registrations without changing JSON semantics or project settings', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'jxsc-json-candidate-test-'));
  try {
    fs.mkdirSync(path.join(root, 'pages'), { recursive: true });
    fs.mkdirSync(path.join(root, 'components'), { recursive: true });
    fs.writeFileSync(path.join(root, 'app.js'), 'globalThis.started = true;');
    const registrations = {
      'app.json': { pages: ['pages/order'], window: { navigationBarTitleText: '学堂' } },
      'pages/order.json': { usingComponents: { 'ui-state': '../components/UiState' } },
      'components/UiState.json': { component: true, usingComponents: {} },
    };
    for (const [name, value] of Object.entries(registrations)) {
      fs.writeFileSync(path.join(root, name), JSON.stringify(value));
    }
    const settings = '{"setting":{"compileHotReLoad":false}}';
    fs.writeFileSync(path.join(root, 'project.private.config.json'), settings);
    const first = stampCandidate(root, 'review-json-1');
    const previous = Object.fromEntries(
      Object.keys(registrations).map((name) => [
        name,
        fs.readFileSync(path.join(root, name), 'utf8'),
      ]),
    );
    const second = stampCandidate(root, 'review-json-2');
    for (const [name, value] of Object.entries(registrations)) {
      const current = fs.readFileSync(path.join(root, name), 'utf8');
      assert.notEqual(
        current,
        previous[name],
        `JSON registration ${name} must invalidate on each build`,
      );
      assert.deepEqual(JSON.parse(current), value);
    }
    assert.deepEqual(
      second.configurations.map((item) => item.path).sort(),
      Object.keys(registrations).sort(),
    );
    assert.equal(first.configurations[0].originalSha256, second.configurations[0].originalSha256);
    assert.equal(fs.readFileSync(path.join(root, 'project.private.config.json'), 'utf8'), settings);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('invalidates every candidate script and exposes one matching runtime build ID without touching storage', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'jxsc-candidate-test-'));
  try {
    fs.mkdirSync(path.join(root, 'pages'), { recursive: true });
    fs.writeFileSync(path.join(root, 'app.js'), 'globalThis.started = true;');
    fs.writeFileSync(path.join(root, 'pages/index.js'), 'module.exports = 42;');
    fs.writeFileSync(path.join(root, 'pages/index.wxml'), '<view>首页</view>');
    const result = stampCandidate(root, 'review-1');
    assert.equal(result.buildId, 'review-1');
    assert.equal(result.scripts.length, 2);
    const context = {};
    vm.runInNewContext(fs.readFileSync(path.join(root, 'app.js'), 'utf8'), context);
    assert.equal(context.__JXSC_REVIEW_BUILD_ID__, 'review-1');
    assert.equal(context.started, true);
    const index = { module: { exports: null } };
    vm.runInNewContext(fs.readFileSync(path.join(root, 'pages/index.js'), 'utf8'), index);
    assert.equal(index.module.exports, 42);
    assert.equal(
      fs
        .readFileSync(path.join(root, 'pages/index.wxml'), 'utf8')
        .replace(/\n<!-- JXSC_REVIEW_STAMP_BEGIN[\s\S]*?JXSC_REVIEW_STAMP_END -->\n?$/, ''),
      '<view>首页</view>',
    );
    stampCandidate(root, 'review-2');
    const next = fs.readFileSync(path.join(root, 'app.js'), 'utf8');
    assert.equal((next.match(/__JXSC_REVIEW_BUILD_ID__/g) || []).length, 1);
    const nextContext = {};
    vm.runInNewContext(next, nextContext);
    assert.equal(nextContext.__JXSC_REVIEW_BUILD_ID__, 'review-2');
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('invalidates stale WXML and WXSS as well as scripts, retaining their content and replacing prior stamps', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'jxsc-view-candidate-test-'));
  try {
    fs.mkdirSync(path.join(root, 'pages'), { recursive: true });
    fs.writeFileSync(path.join(root, 'app.js'), 'globalThis.started = true;');
    const template = '<view class="home-header">首页</view>';
    const style = '.home-header{position:sticky;top:0}';
    fs.writeFileSync(path.join(root, 'pages/index.wxml'), template);
    fs.writeFileSync(path.join(root, 'pages/index.wxss'), style);
    stampCandidate(root, 'review-view-1');
    assert.match(fs.readFileSync(path.join(root, 'pages/index.wxml'), 'utf8'), /review-view-1/);
    assert.match(fs.readFileSync(path.join(root, 'pages/index.wxss'), 'utf8'), /review-view-1/);
    stampCandidate(root, 'review-view-2');
    const nextTemplate = fs.readFileSync(path.join(root, 'pages/index.wxml'), 'utf8');
    const nextStyle = fs.readFileSync(path.join(root, 'pages/index.wxss'), 'utf8');
    assert.equal(
      nextTemplate.replace(
        /\n<!-- JXSC_REVIEW_STAMP_BEGIN[\s\S]*?JXSC_REVIEW_STAMP_END -->\n?$/,
        '',
      ),
      template,
    );
    assert.equal(
      nextStyle.replace(/\n\/\* JXSC_REVIEW_STAMP_BEGIN[\s\S]*?JXSC_REVIEW_STAMP_END \*\/\n?$/, ''),
      style,
    );
    assert.equal((nextTemplate.match(/JXSC_REVIEW_STAMP_BEGIN/g) || []).length, 1);
    assert.equal((nextStyle.match(/JXSC_REVIEW_STAMP_BEGIN/g) || []).length, 1);
    assert.doesNotMatch(nextTemplate + nextStyle, /review-view-1/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
