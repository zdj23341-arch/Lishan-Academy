import { afterAll, beforeAll, expect, it } from 'vitest';
import { createServer } from 'node:http';
import { courseMediaMiddleware } from '../../dev-tools/course-media';

const bytes = Buffer.from('original-test-video-bytes');
const middleware = courseMediaMiddleware(bytes);
const server = createServer((req, res) =>
  middleware(req, res, () => {
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
  await new Promise<void>((resolve, reject) => server.close((e) => (e ? reject(e) : resolve())));
});

it('serves only the exact sample as video, with HEAD and byte ranges for seeking', async () => {
  const head = await fetch(`${base}/course-sample.mp4`, { method: 'HEAD' });
  expect(head.headers.get('content-type')).toBe('video/mp4');
  expect(head.headers.get('content-length')).toBe(String(bytes.length));
  expect(await head.text()).toBe('');
  const partial = await fetch(`${base}/course-sample.mp4?devChapter=one`, {
    headers: { Range: 'bytes=2-6' },
  });
  expect(partial.status).toBe(206);
  expect(await partial.text()).toBe(bytes.subarray(2, 7).toString());
  const tail = await fetch(`${base}/course-sample.mp4`, { headers: { Range: 'bytes=-5' } });
  expect(await tail.text()).toBe(bytes.subarray(-5).toString());
});

it('rejects unsupported ranges/methods and never serves other local files', async () => {
  expect(
    (await fetch(`${base}/course-sample.mp4`, { headers: { Range: 'bytes=999-' } })).status,
  ).toBe(416);
  expect((await fetch(`${base}/course-sample.mp4`, { method: 'POST' })).status).toBe(405);
  expect((await fetch(`${base}/README.md`)).status).toBe(404);
});
