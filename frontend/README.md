# 前端工程

Vue 3 + TypeScript 的 npm workspaces：admin 是管理后台，miniprogram 是 uni-app 微信小程序/H5，shared 为共享领域模块。

## 安装和启动

使用 Node.js 24 / npm 11，在本目录执行：

~~~text
npm ci
npm run dev:admin
npm run dev:h5
~~~

后台和 H5 是独立进程，访问终端显示的地址。后端默认端口 8080。

## 环境配置

admin、miniprogram 各有 .env.development、.env.test、.env.production，属于可提交模板。个人配置写入同目录 .env.development.local / .env.production.local，本机文件不入库。

| 变量 | 用途 |
| --- | --- |
| VITE_PROVIDER | 显式选择 mock 或 http；生产只允许 http |
| VITE_API_BASE_URL | 包含 /api/v1 的完整后端地址 |
| VITE_UPLOAD_BASE_URL / VITE_IMAGE_BASE_URL / VITE_VIDEO_BASE_URL | 资源地址；缺省沿用 API 地址 |
| VITE_DEVELOPMENT_PROXY | 开发 HTTP 模式的浏览器同源代理 |
| VITE_ENABLE_DEV_LOGIN | 开发登录开关，真实微信登录设为 false |
| VITE_LOG_ENABLED | 非生产日志 |

本地 HTTP 示例，分别放入需要联调的应用目录：

~~~dotenv
VITE_PROVIDER=http
VITE_API_BASE_URL=http://127.0.0.1:8080/api/v1
VITE_DEVELOPMENT_PROXY=true
VITE_ENABLE_DEV_LOGIN=false
~~~

服务器使用 HTTPS 公网域名；生产模板的 example.invalid 及资源地址必须替换。VITE_* 会进入前端构建，禁止填写 AppSecret、密码和支付密钥。网络失败不会自动切换 mock。

## 构建

~~~text
npm run build:admin
npm run build:mp:local
npm run build:mp:production
npm run build:h5
~~~

后台产物 admin/dist，小程序产物 miniprogram/dist/build/mp-weixin。后台部署在 /admin/ 时执行 npm run build:admin -- --base=/admin/，反向代理须匹配静态路径及 /api/。

build:mp:local 使用 development 配置并核验微信静态资源；build:mp:production 使用 production 配置。微信开发者工具导入产物目录，AppID 位于 miniprogram/src/manifest.json，须与后端一致。真机需配置 HTTPS 合法域名。

Windows 可用 npm run review:mp:local 构建并打开固定验收目录；自定义 CLI 路径通过 WECHAT_DEVTOOLS_CLI 指定。工具核对 9420 端口项目标识，避免混入旧构建。

## 验证和目录

~~~text
npm test
npm run typecheck
npm run lint
npm run format:check
~~~

| 目录 | 内容 |
| --- | --- |
| shared/src、shared/tests | 请求、会话、金额和业务控制器及测试 |
| admin/src、admin/tests | 后台页面、路由、权限和测试 |
| miniprogram/src、miniprogram/tests | 小程序页面、平台适配和测试 |
| dev-tools、dev-assets | mock 服务与自制测试媒体，不是正式课程内容 |
| assets/design/lishan-v1 | 可编辑素材源、清单和许可证 |
| tools | 资源生成与微信构建核验工具 |

更多见 [架构说明](docs/architecture.md) 和 [素材说明](assets/README.md)。
