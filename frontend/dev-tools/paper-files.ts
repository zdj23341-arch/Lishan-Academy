import type { IncomingMessage, ServerResponse } from 'node:http';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import type { Plugin } from 'vite';
import { devPaperSamples } from '../shared/src/dev/paper-assets';

/** Fixed original public DEV samples only; no request path is passed to the filesystem. */
export function paperFileMiddleware(
  files: readonly { path: string; mime: string; bytes: Buffer }[],
) {
  const entries = new Map(files.map((file) => [file.path, file]));
  return (request: IncomingMessage, response: ServerResponse, next: () => void) => {
    const file = entries.get(request.url?.split('?')[0] ?? '');
    if (!file) return next();
    if (!['GET', 'HEAD'].includes(request.method ?? '')) {
      response.writeHead(405, { Allow: 'GET, HEAD' });
      response.end();
      return;
    }
    response.writeHead(200, {
      'Content-Type': file.mime,
      'Content-Length': file.bytes.length,
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    });
    response.end(request.method === 'HEAD' ? undefined : file.bytes);
  };
}

export function paperFilesPlugin(): Plugin {
  return {
    name: 'jxsc-original-dev-paper-files',
    apply: 'serve',
    configureServer(server) {
      const files = devPaperSamples.map((sample) => {
        const bytes = readFileSync(
          fileURLToPath(new URL(`../dev-assets${sample.path}`, import.meta.url)),
        );
        if (bytes.length !== sample.bytes) throw new Error('DEV纸质资料样例元数据与文件大小不一致');
        return { ...sample, bytes };
      });
      server.middlewares.use(paperFileMiddleware(files));
    },
  };
}
