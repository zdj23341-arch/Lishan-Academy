// Regenerate committed PNG icons with @resvg/resvg-js installed in a tooling directory.
// Usage: node tools/rasterize-icons.cjs <path-to-@resvg/resvg-js>
const fs = require('node:fs');
const path = require('node:path');
const { Resvg } = require(process.argv[2] || '@resvg/resvg-js');
const directory = path.resolve(__dirname, '../miniprogram/src/static/icons');
for (const name of fs.readdirSync(directory).filter((name) => name.endsWith('.svg'))) {
  const source = fs.readFileSync(path.join(directory, name), 'utf8');
  const image = new Resvg(source, { fitTo: { mode: 'width', value: 96 } }).render().asPng();
  fs.writeFileSync(path.join(directory, name.replace(/\.svg$/, '.png')), image);
}
console.log('Raster icons regenerated from committed SVG sources.');
