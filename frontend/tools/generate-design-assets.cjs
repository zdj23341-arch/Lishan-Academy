/* Reproducible offline assets. Dependencies are supplied from a tooling directory,
   so the application runtime and lockfiles remain unchanged. */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
const toolRoot = process.argv[2];
if (!toolRoot) throw new Error('Usage: node tools/generate-design-assets.cjs <tool-node_modules>');
const { Resvg } = require(path.join(toolRoot, '@resvg/resvg-js'));
const lucide = path.join(toolRoot, 'lucide-static');
const source = path.join(root, 'assets/design/lishan-v1');
const outputs = ['admin/src/assets/design', 'miniprogram/src/static/design'].map((p) =>
  path.join(root, p),
);
const proposal = fs.readFileSync(
  path.join(source, 'design-source.html'),
  'utf8',
);
const icons = new Set([
  'house',
  'book-open',
  'book-marked',
  'files',
  'clipboard-list',
  'user-round',
  'layout-dashboard',
  'package',
  'layers',
  'file-text',
  'folder-open',
  'video',
  'crown',
  'users',
  'truck',
  'warehouse',
  'headset',
  'wallet',
  'coins',
  'chart-column',
  'settings',
  'shield-check',
  'download',
  'chevron-right',
  'chevron-left',
  'search',
  'refresh-cw',
  'rotate-ccw',
  'circle-alert',
  'inbox',
  'lock-keyhole',
  'loader-circle',
  'image',
  'check',
  'map-pin',
  'clock',
  'play',
  'graduation-cap',
  'notebook-pen',
  'library',
  'upload',
  'circle-help',
  'arrow-left',
  'arrow-right',
  'x',
  'log-out',
  'menu',
  'calendar',
  'share-2',
  'credit-card',
]);
for (const match of proposal.matchAll(/(?:icon|ic)\(['"]([a-z][a-z0-9-]*)['"]/g))
  icons.add(match[1]);
const tones = {
  neutral: '#617088',
  blue: '#2855b7',
  success: '#197460',
  warning: '#976014',
  danger: '#c04436',
};
const records = [];
function write(relative, bytes, kind, width, height) {
  const filename = path.join(source, relative);
  fs.mkdirSync(path.dirname(filename), { recursive: true });
  fs.writeFileSync(filename, bytes);
  for (const out of outputs) {
    if (out.includes('miniprogram') && relative.endsWith('.svg')) continue;
    if (out.includes('admin') && relative.startsWith('icons/') && relative.endsWith('.png'))
      continue;
    fs.mkdirSync(path.dirname(path.join(out, relative)), { recursive: true });
    fs.writeFileSync(path.join(out, relative), bytes);
  }
  records.push({
    path: relative,
    kind,
    width,
    height,
    sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
  });
}
for (const name of [...icons].sort()) {
  const filename = path.join(lucide, 'icons', name + '.svg');
  if (!fs.existsSync(filename)) throw new Error('Unknown Lucide icon: ' + name);
  const raw = fs.readFileSync(filename, 'utf8');
  for (const [tone, color] of Object.entries(tones)) {
    const svg = raw.replaceAll('currentColor', color);
    const svgPath = path.join(source, `icons/${name}-${tone}.svg`);
    const pngPath = path.join(source, `icons/${name}-${tone}.png`);
    const cachedPng =
      fs.existsSync(svgPath) && fs.existsSync(pngPath) && fs.readFileSync(svgPath, 'utf8') === svg
        ? fs.readFileSync(pngPath)
        : null;
    write(`icons/${name}-${tone}.svg`, svg, 'icon-source', 24, 24);
    write(
      `icons/${name}-${tone}.png`,
      cachedPng ||
        new Resvg(svg, { fitTo: { mode: 'width', value: 96 }, font: { loadSystemFonts: false } })
          .render()
          .asPng(),
      'icon',
      96,
      96,
    );
  }
}
for (const [name, color, ink, first, second] of [
  ['blue', '#294c7f', '#ffffff', '阅读', '方法'],
  ['jade', '#3b7169', '#ffffff', '作文', '章法'],
  ['sand', '#866648', '#ffffff', '阅读', '训练'],
  ['paper', '#f7f4ed', '#324057', '语文', '资料'],
  ['poetry', '#866648', '#ffffff', '古诗', '意境'],
  ['membership-half', '#866648', '#ffffff', '试卷', '会员'],
  ['membership-year', '#294c7f', '#ffffff', '试卷', '会员'],
]) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="900" height="1200" viewBox="0 0 900 1200"><rect width="900" height="1200" fill="${color}"/><path d="M44 0v1200" stroke="${ink}" opacity=".22" stroke-width="3"/><text x="82" y="176" fill="${ink}" opacity=".74" font-family="Microsoft YaHei" font-size="42">力山学堂 · 语文学习</text><g fill="${ink}" font-family="SimSun" font-size="138" font-weight="600" letter-spacing="10"><text x="82" y="506">${first}</text><text x="82" y="700">${second}</text></g><text x="82" y="1035" fill="${ink}" opacity=".74" font-family="Microsoft YaHei" font-size="38">读懂文本 · 写出思考</text></svg>`;
  write(`covers/${name}.svg`, svg, 'editable-cover', 900, 1200);
  write(`covers/${name}.png`, new Resvg(svg).render().asPng(), 'cover', 900, 1200);
}
const hero = `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="600"><rect width="1200" height="600" rx="40" fill="#e9effa"/><text x="70" y="117" fill="#2855b7" font-family="Microsoft YaHei" font-size="38" letter-spacing="6">力山学堂 · 阅读专题</text><g fill="#18283e" font-family="SimSun" font-size="90" font-weight="600" letter-spacing="4"><text x="70" y="253">读懂文本，</text><text x="70" y="371">也读懂表达。</text></g><rect x="70" y="426" width="280" height="111" rx="22" fill="#edf3ff"/><text x="118" y="497" fill="#2855b7" font-family="Microsoft YaHei" font-size="42">查看专题</text><g transform="translate(858 110) rotate(7)"><rect x="15" y="18" width="240" height="346" rx="12" fill="#cbd8ec"/><rect width="240" height="346" rx="12" fill="#294c7f"/><path d="M20 0v346" stroke="#ffffff" opacity=".25"/><text x="44" y="143" fill="#fff" font-family="SimSun" font-size="64" font-weight="600">阅读</text><text x="44" y="231" fill="#fff" font-family="SimSun" font-size="64" font-weight="600">方法</text></g></svg>`;
write('banners/reading.svg', hero, 'editable-banner', 1200, 600);
write('banners/reading.png', new Resvg(hero).render().asPng(), 'banner', 1200, 600);
for (const [name, svg] of [
  [
    'writing',
    hero
      .replaceAll('阅读专题', '写作专题')
      .replace('读懂文本，', '写下思考，')
      .replace('也读懂表达。', '也写出章法。')
      .replace('>阅读</text>', '>作文</text>')
      .replace('>方法</text>', '>章法</text>')
      .replaceAll('#e9effa', '#eaf7f0')
      .replaceAll('#edf3ff', '#eaf7f0')
      .replaceAll('#2855b7', '#197460')
      .replaceAll('#294c7f', '#3b7169')
      .replaceAll('#cbd8ec', '#c7dfd7'),
  ],
  [
    'books',
    hero
      .replace('阅读专题', '语文图书')
      .replace('>方法</text>', '>训练</text>')
      .replaceAll('#294c7f', '#866648'),
  ],
]) {
  write(`banners/${name}.svg`, svg, 'editable-banner', 1200, 600);
  write(`banners/${name}.png`, new Resvg(svg).render().asPng(), 'banner', 1200, 600);
}
fs.copyFileSync(path.join(lucide, 'LICENSE'), path.join(source, 'LUCIDE-LICENSE'));
fs.writeFileSync(
  path.join(source, 'manifest.json'),
  JSON.stringify(
    {
      version: 'lishan-v1',
      lucideVersion: '1.54.0',
      generator: 'frontend/tools/generate-design-assets.cjs',
      realImagesFirst: true,
      icons: [...icons].sort(),
      records,
    },
    null,
    2,
  ) + '\n',
);
console.log(
  `Generated ${icons.size} icons, seven covers and three banners (${records.length} source files; platform-specific SVG/PNG copies).`,
);
