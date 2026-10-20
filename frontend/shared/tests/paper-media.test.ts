import { afterAll, beforeAll, expect, it } from 'vitest';
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { paperFileMiddleware } from '../../dev-tools/paper-files';
import { devPaperSamples } from '../src/dev/paper-assets';

const files = devPaperSamples.map((sample) => ({
  ...sample,
  bytes: readFileSync(fileURLToPath(new URL(`../../dev-assets${sample.path}`, import.meta.url))),
}));
const handler = paperFileMiddleware(files);
const server = createServer((req, res) =>
  handler(req, res, () => {
    res.writeHead(404);
    res.end();
  }),
);
let base: string;
beforeAll(async () => {
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('Test port unavailable');
  base = `http://127.0.0.1:${address.port}`;
});
afterAll(async () => {
  await new Promise<void>((resolve, reject) =>
    server.close((error) => (error ? reject(error) : resolve())),
  );
});

it('serves every original file with matching MIME and exact catalog byte size', async () => {
  for (const [index, file] of files.entries()) {
    expect(file.bytes.length).toBe(devPaperSamples[index]!.bytes);
    const response = await fetch(`${base}${file.path}`);
    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toBe(file.mime);
    expect(response.headers.get('cache-control')).toContain('no-store');
    expect(Buffer.from(await response.arrayBuffer()).equals(file.bytes)).toBe(true);
    const head = await fetch(`${base}${file.path}`, { method: 'HEAD' });
    expect(head.headers.get('content-length')).toBe(String(file.bytes.length));
    expect(await head.text()).toBe('');
  }
  expect(files[0]!.bytes.subarray(0, 2).toString()).toBe('PK');
  expect(files[1]!.bytes.subarray(0, 5).toString()).toBe('%PDF-');
  expect(files[2]!.bytes.subarray(1, 4).toString()).toBe('PNG');
});

it('serves no arbitrary paths or write operations', async () => {
  expect((await fetch(`${base}/dev-files/paper-sample.pdf`, { method: 'POST' })).status).toBe(405);
  expect((await fetch(`${base}/dev-files/secret.pdf`)).status).toBe(404);
  expect((await fetch(`${base}/dev-files/%2e%2e/package.json`)).status).toBe(404);
});
