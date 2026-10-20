import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import type { IncomingMessage, ServerResponse } from 'node:http';
import type { Plugin } from 'vite';

/** One original local sample only; never resolves a request into an arbitrary file path. */
export function courseMediaMiddleware(bytes: Buffer) {
  return (request: IncomingMessage, response: ServerResponse, next: () => void) => {
    if (request.url?.split('?')[0] !== '/course-sample.mp4') return next();
    if (!['GET', 'HEAD'].includes(request.method ?? '')) {
      response.writeHead(405, { Allow: 'GET, HEAD' });
      response.end();
      return;
    }
    const headers: Record<string, string | number> = {
      'Content-Type': 'video/mp4',
      'Accept-Ranges': 'bytes',
      'Cache-Control': 'no-store',
    };
    let start = 0,
      end = bytes.length - 1;
    const range = request.headers.range;
    if (range) {
      const match = /^bytes=(\d*)-(\d*)$/.exec(range);
      let valid = !!match && !!(match[1] || match[2]);
      if (valid && match) {
        if (match[1]) {
          start = Number(match[1]);
          end = match[2] ? Math.min(Number(match[2]), end) : end;
        } else {
          const count = Number(match[2]);
          valid = count > 0;
          start = Math.max(0, bytes.length - count);
        }
        valid =
          valid &&
          Number.isSafeInteger(start) &&
          Number.isSafeInteger(end) &&
          start <= end &&
          start < bytes.length;
      }
      if (!valid) {
        response.writeHead(416, { ...headers, 'Content-Range': `bytes */${bytes.length}` });
        response.end();
        return;
      }
      headers['Content-Range'] = `bytes ${start}-${end}/${bytes.length}`;
    }
    headers['Content-Length'] = end - start + 1;
    response.writeHead(range ? 206 : 200, headers);
    response.end(request.method === 'HEAD' ? undefined : bytes.subarray(start, end + 1));
  };
}

export function courseMediaPlugin(): Plugin {
  return {
    name: 'jxsc-original-dev-course-media',
    apply: 'serve',
    configureServer(server) {
      const bytes = readFileSync(
        fileURLToPath(new URL('../dev-assets/course-sample.mp4', import.meta.url)),
      );
      server.middlewares.use(courseMediaMiddleware(bytes));
    },
  };
}
