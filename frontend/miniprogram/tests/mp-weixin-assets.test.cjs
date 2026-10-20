const assert = require('node:assert/strict');
const { existsSync, readFileSync, readdirSync } = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const output = path.resolve('dist/build/mp-weixin');

function javascriptFiles(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const entryPath = path.join(directory, entry.name);
    return entry.isDirectory()
      ? javascriptFiles(entryPath)
      : entry.name.endsWith('.js')
        ? [entryPath]
        : [];
  });
}

test('微信 bundle uses the approved home banner without an unregistered assets module', () => {
  const carousel = readFileSync(path.join(output, 'components/HomeCarousel.js'), 'utf8');
  const bundle = javascriptFiles(output)
    .map((file) => readFileSync(file, 'utf8'))
    .join('\n');

  assert.doesNotMatch(bundle, /common\/assets\.js/);
  assert.match(carousel, /\/static\/design\/banners\/home-three-business\.png/);
  assert.doesNotMatch(carousel, /\/static\/design\/banners\/(books|reading|writing)\.png/);
  assert.ok(
    existsSync(path.join(output, 'static/design/banners/home-three-business.png')),
    'the approved home banner is bundled',
  );
});
