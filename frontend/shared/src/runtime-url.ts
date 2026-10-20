// eslint-disable-next-line @typescript-eslint/triple-slash-reference -- Include ambient vendor types in both consuming Vue workspaces.
/// <reference path="./core-js-pure.d.ts" />
// A module-scoped WHATWG URL implementation: WeChat may have no URL constructor.
// The pure build also provides searchParams without changing platform globals.
export { default as RuntimeURL } from 'core-js-pure/actual/url/index.js';
