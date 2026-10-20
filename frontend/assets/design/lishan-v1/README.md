# 力山学堂 UI 素材 V1

本素材对应 同目录的 `design-source.html`。用户认可的蓝白品牌、线性图标、书脊封面与阅读专题已导出为仓库内可编辑素材；不依赖外部图片服务。

## 文件与用途

| 位置 | 内容 | 规格 |
| --- | --- | --- |
| `icons/` | 55 个 Lucide 图标，每个 5 色，SVG 源与 PNG | SVG 24×24，PNG 96×96 |
| `covers/` | 阅读方法 / 作文章法 / 阅读训练 / 语文资料 / 古诗意境 / 两个会员书封 | SVG 源、PNG 1200×900，4:3 |
| `banners/reading.*`、`writing.*`、`books.*` | 认可稿阅读横幅及同结构写作、图书变体 | SVG 源、PNG 1200×600，2:1 |
| `manifest.json` | 图标列表、尺寸、类别、逐文件 SHA-256 | 素材核对依据 |
| `LUCIDE-LICENSE` | Lucide 图标的 ISC 许可 | 随素材保留 |

后台图标产物在 `frontend/admin/src/assets/design/icons/`（SVG）；小程序图标在 `frontend/miniprogram/src/static/design/icons/`（PNG）。不要向小程序引入内联 SVG 组件或临时网络图标。两端封面 / 横幅同时保存 PNG；SVG 可编辑源留在此处。

## 后续替换客户图片

1. 准备商品 / 图文封面 4:3 或首页横幅 2:1 的 PNG、JPEG、WEBP。不要把设计稿截图当作整页背景。
2. 后台“素材资源”上传为公开图片；等待服务端状态 READY，拿到真实公开地址。
3. 在商品、图文或首页装修编辑器的公开图片选择器中选择资源，保存并按现有发布流程发布。
4. 客户端优先显示服务端图片。缺图或读取失败时，`BookCover` 显示当前真实标题的书封；替换 URL 后会重新尝试读取。首页已知的本地 DEV 占位图使用批准稿专题兜底，真实上传地址不会被覆盖。
5. 在网站、小程序开发者工具与微信真机分别检查图片比例和可读性。真机资源需要由部署负责人提供公开 HTTPS、合法域名和有效权限。

实际素材绑定与首页发布版本属于各环境数据库，不在前端硬编码，也不随源码同步。

原有 assetId、上传授权、READY 校验、发布版本控制全部保留。封面上传不要误用课程私有视频或试卷私有附件。后台缩略图失败会明确显示“图片暂不可读”，仍由服务端决定是否可选。

## 修改图标、颜色或封面源

生成器：`frontend/tools/generate-design-assets.cjs`。图标名和色值定义于生成器，UI 组件的 `name` / `tone` 映射在两端 `components/ui/UiIcon.vue`。新增图标时两端白名单一起更新；未知图标名兜底为 circle-help。

生成工具不进入应用依赖。本轮锁定工具版本 `lucide-static@1.54.0`、`@resvg/resvg-js@2.6.2`，在独立工具目录安装后传入 node_modules 路径。从 frontend 目录运行：

```text
node tools/generate-design-assets.cjs <tool-directory>/node_modules
```

换机器时在独立工具目录安装这两个固定版本，传入其 node_modules 路径，不修改应用 package.json / 锁文件。PNG 中中文排版使用 SimSun / Microsoft YaHei，重新导出时应在安装这些字体的环境检查字形与裁切。修改 SVG 需同步重新渲染 PNG 并更新 manifest，避免仅修改一端。

品牌变量：后台 `src/styles/admin-tokens.scss` 与 `design-system.scss`，小程序 `src/styles/tokens.scss` 与 `design-system.scss`。改变品牌色时还需同步生成器图标色值；封面主题有独立色值，两端 BookCover 和素材源应一起核对。颜色不是后台设置中的业务字段，不新建存储接口。

## 组件交接

两端共有以下组件名及 props：

| 组件 | 输入 / 事件 | 约定 |
| --- | --- | --- |
| UiIcon | name、tone、size | 装饰图标；操作名称放在按钮或相邻文字 |
| UiButton | kind、disabled、loading、type；click | disabled 或 loading 不触发 click |
| UiStatusBadge | label、tone | 状态仍由服务端 / 控制器映射，颜色不能替代文字 |
| UiState | kind、title、description、actionLabel；action | loading / empty / error / denied；由业务层决定重试 |
| PageHeader | title、description；默认 slot | 默认 slot 放现有业务操作 |
| BookCover | title、imageUrl、theme | 真图优先；失败兜底；URL 改变重试 |
| BrandMark | compact、subtitle | 品牌标识，紧凑模式仅保留标记 |

按钮 kind 为 primary / secondary / soft / link / danger；tone 为 neutral / blue / success / warning / danger；封面 theme 为 blue / jade / sand / paper。现有业务按钮可以沿用原方法与 class，无需改成另一套状态管理。

后台可运行样板：开发服务器的 `/ui-showcase.html`，直接使用生产 Vue 组件；未加入业务路由，也未加入默认发行入口。小程序以实际首页、登录、会员、错误页和订单封面作为平台样板，PNG 图标随微信构建离线打包。
