const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

function stampCandidate(root, buildId) {
  if (!/^[a-zA-Z0-9_-]+$/.test(buildId)) throw new Error('Invalid review build ID');
  if (!fs.existsSync(path.join(root, 'app.js'))) throw new Error('Missing candidate app.js');
  const scripts = [];
  const templates = [];
  const styles = [];
  const configurations = [];
  function visit(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const filename = path.join(directory, entry.name);
      const relative = path.relative(root, filename).replaceAll('\\', '/');
      const isConfiguration =
        entry.isFile() &&
        (relative === 'app.json' || /^(pages|components)\/.*\.json$/.test(relative));
      if (entry.isDirectory()) visit(filename);
      else if (isConfiguration) {
        const original = fs.readFileSync(filename, 'utf8').trimEnd();
        // JSON permits trailing whitespace. Encode the ID there so the IDE reloads
        // registrations without adding unsupported configuration properties.
        const stamp = Array.from(Buffer.from(buildId))
          .map((byte) =>
            byte.toString(2).padStart(8, '0').replaceAll('0', ' ').replaceAll('1', '\t'),
          )
          .join('');
        const updated = `${original}\n${stamp}\n`;
        JSON.parse(updated);
        fs.writeFileSync(filename, updated);
        configurations.push({
          path: relative,
          originalSha256: crypto.createHash('sha256').update(original).digest('hex'),
          sha256: crypto.createHash('sha256').update(updated).digest('hex'),
        });
      } else if (entry.isFile() && /\.(js|wxml|wxss)$/.test(entry.name)) {
        const isTemplate = entry.name.endsWith('.wxml');
        const original = fs
          .readFileSync(filename, 'utf8')
          .replace(
            /\n(?:\/\* JXSC_REVIEW_STAMP_BEGIN[\s\S]*?JXSC_REVIEW_STAMP_END \*\/|<!-- JXSC_REVIEW_STAMP_BEGIN[\s\S]*?JXSC_REVIEW_STAMP_END -->)\n?$/,
            '',
          );
        const marker =
          relative === 'app.js'
            ? `globalThis.__JXSC_REVIEW_BUILD_ID__ = ${JSON.stringify(buildId)};\n`
            : '';
        const updated = isTemplate
          ? `${original}\n<!-- JXSC_REVIEW_STAMP_BEGIN ${buildId} JXSC_REVIEW_STAMP_END -->\n`
          : entry.name.endsWith('.js')
            ? `${original}\n/* JXSC_REVIEW_STAMP_BEGIN */\n// ${buildId}\n${marker}/* JXSC_REVIEW_STAMP_END */\n`
            : `${original}\n/* JXSC_REVIEW_STAMP_BEGIN ${buildId} JXSC_REVIEW_STAMP_END */\n`;
        fs.writeFileSync(filename, updated);
        const records = isTemplate ? templates : entry.name.endsWith('.wxss') ? styles : scripts;
        records.push({
          path: relative,
          originalSha256: crypto.createHash('sha256').update(original).digest('hex'),
          sha256: crypto.createHash('sha256').update(updated).digest('hex'),
        });
      }
    }
  }
  visit(root);
  const result = { buildId, scripts, templates, styles, configurations };
  fs.writeFileSync(path.join(root, 'review-build.json'), JSON.stringify(result, null, 2) + '\n');
  return result;
}
module.exports = { stampCandidate };
