# 手册的 App 接入基础（路由协议 v1）

本阶段提供可离线运行的阅读器、稳定章节目录和宿主接口，未修改 Android/ArkUI-X 应用。后续在既有 `shared-frontend/` 共享页面中接入帮助入口；Android 系统返回和本地资源装载按需要交给 Kotlin 适配。不以另建 Compose 帮助页代替共享前端路线。

## 可直接打包的资源

保留以下文件的相对目录。无需 Python/Node、HTTP 服务器、网络请求、模块加载器或 CDN 即可阅读：

```text
index.html                 生成的正文、页面和内嵌目录数据
manual.css                 宽窄屏与打印样式
navigation.js              无 DOM 的路由解析/编码模块
manual.js                  浏览器视图与 History 适配
app-manifest.json          版本化章节 ID、标题、摘要和路由清单
content.json               受审查的正文源，可供后续共享页面复用
assets/*.png               本地截图
README.md                  页面现有维护说明链接的目标
APP_INTEGRATION.md          README 的本地链接目标
```

开发工具 `build.py`、`navigation.test.cjs`、`host-api.d.ts` 和截图来源清单无需在运行时加载。不要把 localhost 预览地址写进 App。先用 `python docs/user-manual/build.py --check` 确保 HTML 与 App 目录未漂移。未来若采用共享原生渲染，沿用 `app-manifest.json` 的 ID 和路由；正文 HTML 不是 ArkUI 组件，原生排版仍需专门实现，不能宣称复制 JSON 即完成原生移植。

## 地址与章节 ID

| 地址（附在本地 index.html 后） | 页面 |
| --- | --- |
| `#/` | 手册首页与目录卡片 |
| `#/chapter/start` | 第一次使用 |
| `#/chapter/models` | 模型配置 |
| `#/chapter/gallery` | 图库 |
| `#/search?q=API%20Key` | 搜索结果，可刷新和返回 |

其余 ID 取自 `app-manifest.json` 的 `chapters`，不要根据中文标题猜测。旧 `#start`、`#home`、`#title-start` 兼容；特别注意 `#home` 是“首页功能”这一章，`#/` 才是手册首页。未知路由显示可返回目录的错误页，不打开其他 URL。纯路由模块同时支持普通 `<script>` 与 CommonJS，不使用 ESM，以保留浏览器本地文件阅读能力。

## 宿主调用接口

页面加载完成并确认 `window.ClawMateManual.version === 1` 后，可使用下列公开接口；类型见 `host-api.d.ts`：

```javascript
// 例如由“图库”页的帮助按钮指定章节 ID。
window.ClawMateManual.openChapter('gallery'); // 已知 ID 返回 true，未知 ID 返回 false
window.ClawMateManual.openHome();
window.ClawMateManual.getState();
// { version: 1, route: { page: 'chapter', id: 'gallery' },
//   hash: '#/chapter/gallery', canGoBack: true }

// 系统返回的约定：先关闭图片，再返回阅读历史。
const handled = window.ClawMateManual.back();
// true：图片已关闭，或已发起异步 history.back()，宿主本次不要再关闭帮助页。
// false：已到本次加载的入口，由宿主关闭帮助页并返回原功能页。

window.addEventListener('clawmate-manual:routechange', event => {
  const state = event.detail; // 同 getState()；可更新宿主的返回状态。
});
```

初次直接加载 `index.html#/chapter/gallery` 时，入口处 `canGoBack` 为 false；再点进其他章后为 true。刷新/重建后的当前页成为新的入口，内部返回栈不跨页面加载恢复。浏览器工具栏本身的前进后退仍可切换已有浏览器历史。不要依据 `history.length` 决定关闭原生页面，也不要仅凭 `canGoBack` 绕过 `back()` 的图片关闭优先级。

该接口只在页面内导航，不打开 App 模型配置、不请求权限、不读取私有数据。未来若增加“去 App 中操作”，应另行定义并白名单核对实际目标，不能把正文里的任意链接或模型输出交给原生桥执行。宿主向 JS 传参需用结构化参数或 JSON 序列化，不拼接未经校验的字符串。

## 接入时验证

开发用 `host-api.test.html` 不属于运行时资源。在本地 HTTP 服务中打开该页并点“运行宿主接口检查”，会在独立手册 iframe 中实际调用公开 API，检查章节白名单、页面切换、路由事件和返回优先级，共 8 项；每次重跑前刷新测试页。

当前浏览器验证不能代替 Android 验收。移植阶段实际核对本地资源、网络断开时阅读、系统返回优先级、横竖屏/进程重建、字体缩放与 TalkBack。若使用 WebView，应限制在所打包手册资源内，不能给任意网页注入宿主能力；资源加载方式与既有宿主适配一并评审。内置浏览器仅允许 HTTP/HTTPS，本轮直接 file: 地址检查被安全策略阻止，未改用其他通道绕过；直接文件打开仍需在允许的本地阅读环境补验。

打印仍提供全章样式；内置浏览器此前打印调用超时，PDF 排版尚未验收。App 无打印能力时可由宿主样式隐藏打印按钮，不把它列为已完成原生功能。
