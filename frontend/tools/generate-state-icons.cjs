// Regenerate only the frozen state icon; never rewrites banners/covers/admin assets.
// Usage: node tools/generate-state-icons.cjs <tool-node_modules>
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
if (!process.argv[2]) throw new Error('Supply the tooling node_modules directory');
const { Resvg } = require(path.join(path.resolve(process.argv[2]), '@resvg/resvg-js'));
const source = path.join(root, 'assets/design/lishan-v1');
const manifestPath = path.join(source, 'manifest.json');
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const body =
  '<path d="M12 20h.01"/><path d="M8.5 16.429a5 5 0 0 1 7 0"/><path d="M5 12.859a10 10 0 0 1 5.17-2.69"/><path d="M19 12.859a10 10 0 0 0-2.007-1.523"/><path d="M2 8.82a15 15 0 0 1 4.177-2.643"/><path d="M22 8.82a15 15 0 0 0-11.288-3.764"/><path d="m2 2 20 20"/>';
for (const [tone, color] of Object.entries({
  neutral: '#617088',
  blue: '#2855b7',
  success: '#197460',
  warning: '#976014',
  danger: '#c04436',
})) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="${color}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${body}</svg>`;
  const png = new Resvg(svg, {
    fitTo: { mode: 'width', value: 96 },
    font: { loadSystemFonts: false },
  })
    .render()
    .asPng();
  for (const [ext, bytes, kind, size] of [
    ['svg', svg, 'icon-source', 24],
    ['png', png, 'icon', 96],
  ]) {
    const relative = `icons/wifi-off-${tone}.${ext}`;
    fs.writeFileSync(path.join(source, relative), bytes);
    if (ext === 'png')
      fs.writeFileSync(path.join(root, 'miniprogram/src/static/design', relative), bytes);
    manifest.records = manifest.records.filter((record) => record.path !== relative);
    manifest.records.push({
      path: relative,
      kind,
      width: size,
      height: size,
      sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
    });
  }
}
if (!manifest.icons.includes('wifi-off')) manifest.icons.push('wifi-off');
fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
console.log('Generated frozen wifi-off SVG sources and mini PNGs; existing artwork preserved.');
