import { replaceChecked } from '../../shared-frontend/source-patches.mjs';
// Stage a probe from pinned, read-only source. No production Android files change.
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const valueOptions = new Set(['--project', '--entry', '--extra-lock', '--overlay', '--routes']);
const options = new Map();
const allowed = new Set(['--raw', '--original-transport', '--simulate-upstream', '--help', '--keep-facade']);
for (let index = 2; index < process.argv.length; index++) {
  const argument = process.argv[index];
  if (valueOptions.has(argument)) {
    const value = process.argv[++index];
    if (!value || value.startsWith('--')) throw new Error(`Missing value: ${argument}`);
    options.set(argument, value);
    continue;
  }
  if (!allowed.has(argument)) throw new Error(`Unknown argument: ${argument}`);
}
if (process.argv.includes('--help')) {
  console.log('Stage pinned sources into the generated probe: [--raw] [--original-transport] [--simulate-upstream]');
  process.exit(0);
}
const repo = path.resolve(here, '../..');
const scratch = path.join(repo, '.arkui-x-validation');
const reference = path.join(scratch, 'reference');
const project = path.resolve(options.get('--project') || path.join(scratch, 'project'));
if (!project.startsWith(scratch + path.sep)) throw new Error('Generated project must remain in the ignored workspace');
const lock = JSON.parse(fs.readFileSync(path.join(here, 'ui-upstream-lock.json'), 'utf8'));
if (options.has('--extra-lock')) {
  const extra = JSON.parse(fs.readFileSync(options.get('--extra-lock'), 'utf8'));
  if (extra.ref !== lock.ref) throw new Error('Additional inputs must pin the same source revision');
  lock.files.push(...extra.files.filter(file => !lock.files.some(existing => existing.path === file.path)));
}
const sources = new Map();
for (const file of lock.files) {
  const bytes = fs.readFileSync(path.join(reference, file.path));
  const actual = createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
  if (actual !== file.gitBlob) throw new Error(`Upstream drift: ${file.path}`);
  sources.set(file.path.slice('entry/src/main/ets/'.length), bytes.toString('utf8'));
}
const original = new Map(sources);
const raw = process.argv.includes('--raw');
const originalTransport = process.argv.includes('--original-transport');
const simulateUpstream = process.argv.includes('--simulate-upstream');
if (!raw) {
  if (!process.argv.includes('--keep-facade')) {
  const facade = sources.get('datamanager/DataManagerFacade.ets');
  const names = ['createHomeProfileAvatarDefaults', 'defaultHomeDomains', 'defaultRecommendations',
    'createEmptyHomeProfileViewModel'];
  const functions = names.map(name => {
    const text = facade.match(new RegExp(`(?:export )?function ${name}\\([\\s\\S]*?\\n\\}`))?.[0];
    if (!text) throw new Error(`Missing original factory: ${name}`);
    return text;
  });
  sources.set('datamanager/DataManagerFacade.ets',
    "import { HomeProfileViewModel, HomeDomainViewModel, HomeRecommendationViewModel } from './DataManagerTypes';\n" +
    "import { AvatarHomeViewModel, createEmptyAvatarHomeViewModel } from './avatar/AvatarTypes';\n" + functions.join('\n\n'));
  }

  const frosted = sources.get('components/FrostedPage.ets');
  const start = frosted.indexOf('      Navigation() {');
  const end = frosted.lastIndexOf('\n    }\n  }\n}');
  if (start < 0 || end < start) throw new Error('Original standard ArkUI fallback changed');
  sources.set('components/FrostedPage.ets',
    "import { AppTheme, APP_DARK_MODE_KEY } from '../utils/AppTheme';\n" +
    "import { window } from '@kit.ArkUI';\nimport { APP_HEADER_HEIGHT } from './AppHeader';\n" +
    '@Component\nexport struct FrostedPage {\n' +
    "  @Prop pageBackgroundColor: string = '#FFFFFF';\n" +
    '  @StorageProp(APP_DARK_MODE_KEY) private darkMode: boolean = false;\n' +
    '  scrollers: Scroller[] = [];\n' +
    '  @BuilderParam headerBuilder: () => void = this.EmptyContent;\n' +
    '  @BuilderParam contentBuilder: () => void = this.EmptyContent;\n' +
    "  private statusBarStyle(): window.SystemBarStyle { return { statusBarContentColor: this.darkMode ? '#ECF1FA' : '#172033' }; }\n" +
    '  @Builder EmptyContent() {}\n  build() {\n' + frosted.slice(start, end) + '\n  }\n}\n');

  // Native MNN diagnostics are unavailable in this UI/HTTP experiment.
  sources.set('utils/DebugSettings.ets',
    "export const DEBUG_ENABLED_STORAGE_KEY: string = 'debugEnabled';\n" +
    'export class DebugSettings {\n' +
    '  static isEnabled(): boolean { return AppStorage.get<boolean>(DEBUG_ENABLED_STORAGE_KEY) === true; }\n}\n');
  sources.set('pages/chat/ChatPage.ets', replaceChecked(sources.get('pages/chat/ChatPage.ets'), "from '@kit.IMEKit'", "from '../../platform/InputMethod'"));
  sources.set('platform/InputMethod.ets',
    "import Bridge from '@arkui-x.bridge';\n" +
    'class InputMethodController {\n' +
    '  async stopInputSession(): Promise<boolean> {\n' +
    "    return await Bridge.createBridge('ClawMateProbe').callMethod('hideKeyboard') as boolean;\n" +
    '  }\n}\nexport class inputMethod {\n' +
    '  static getController(): InputMethodController { return new InputMethodController(); }\n}\n');

  // Compiler-confirmed API 20 gaps. Keep platform behavior in a bridge rather
  // than disabling compiler checks or claiming unsupported calls succeeded.
  sources.set('components/FrostedPage.ets', replaceChecked(sources.get('components/FrostedPage.ets'), '      .systemBarStyle(this.statusBarStyle())\n', ''));
  sources.set('utils/gallery/GalleryPhotoPicker.ets',
    'export class GalleryPhotoPicker {\n' +
    '  async selectImageUris(maxSelectNumber: number = 9): Promise<string[]> {\n' +
    "    throw new Error('本验证的图片选择器尚未接入 Android 原生适配');\n  }\n}\n");
  sources.set('platform/PlatformUi.ets',
    "import Bridge from '@arkui-x.bridge';\nexport class PlatformUi {\n" +
    "  static async clearFocus(): Promise<void> { await Bridge.createBridge('ClawMateProbe').callMethod('clearFocus'); }\n" +
    "  static async keyboardHeight(): Promise<number> { return await Bridge.createBridge('ClawMateProbe').callMethod('keyboardHeight') as number; }\n}\n");
  const patchFocus = (source, relativeImport) =>
    `import { PlatformUi } from '${relativeImport}';\n` + replaceChecked(source, 'this.getUIContext().getFocusController().clearFocus();', 'PlatformUi.clearFocus().catch(() => {});', { all: true });
  sources.set('pages/chat/ChatPage.ets', patchFocus(sources.get('pages/chat/ChatPage.ets'), '../../platform/PlatformUi'));
  let comfort = patchFocus(sources.get('pages/home/HomeComfortSection.ets'), '../../platform/PlatformUi');
  comfort = replaceChecked(comfort, '  private startKeyboardTracking(): void {',
    '  private keyboardPollId: number = -1;\n\n  private startKeyboardTracking(): void {');
  const replaceMethod = (source, name, body) => {
    const pattern = new RegExp(`  private ${name}\\([^]*?\\n  \\}`);
    if (!pattern.test(source)) throw new Error(`Missing platform method ${name}`);
    return replaceChecked(source, pattern, body);
  };
  comfort = replaceMethod(comfort, 'startKeyboardTracking',
    '  private startKeyboardTracking(): void {\n' +
    '    if (this.keyboardPollId >= 0) { return; }\n' +
    '    this.keyboardPollId = setInterval(() => {\n' +
    '      PlatformUi.keyboardHeight().then((height: number): void => {\n' +
    '        this.keyboardHeightVp = Math.max(0, px2vp(height));\n' +
    '        if (height > 0) { this.scrollToLatest(); }\n' +
    '      }).catch(() => { this.keyboardHeightVp = 0; });\n' +
    '    }, 150);\n  }');
  comfort = replaceMethod(comfort, 'stopKeyboardTracking',
    '  private stopKeyboardTracking(): void {\n' +
    '    if (this.keyboardPollId >= 0) { clearInterval(this.keyboardPollId); }\n' +
    '    this.keyboardPollId = -1;\n    this.keyboardHeightVp = 0;\n  }');
  sources.set('pages/home/HomeComfortSection.ets', comfort);
  sources.set('pages/chat/ChatMessageList.ets', replaceChecked(sources.get('pages/chat/ChatMessageList.ets'), "import { pasteboard } from '@kit.BasicServicesKit';", "import { pasteboard } from '../../platform/Pasteboard';"));
  sources.set('platform/Pasteboard.ets',
    "import Bridge from '@arkui-x.bridge';\n" +
    'class ClipboardData { text: string; constructor(text: string) { this.text = text; } }\n' +
    'class SystemClipboard {\n  async setData(data: ClipboardData): Promise<void> {\n' +
    "    let ok = await Bridge.createBridge('ClawMateProbe').callMethod('setClipboard', data.text) as boolean;\n" +
    "    if (!ok) { throw new Error('Android 剪贴板不可用'); }\n  }\n}\n" +
    "export class pasteboard {\n  static MIMETYPE_TEXT_PLAIN: string = 'text/plain';\n" +
    '  static getSystemPasteboard(): SystemClipboard { return new SystemClipboard(); }\n' +
    '  static createData(mime: string, text: string): ClipboardData { return new ClipboardData(text); }\n}\n');

  // Device-observed defects in the pinned upstream transport. Keep the SSE
  // parser intact; opt out to reproduce the original failures on Android.
  if (!originalTransport) {
    let client = sources.get('utils/CloudModelClient.ets');
    client = replaceChecked(client, '  private activeRequests: http.HttpRequest[] = [];',
      '  private activeRequests: http.HttpRequest[] = [];\n' +
      '  private streamCancellers: Map<http.HttpRequest, () => void> = new Map();');
    client = replaceChecked(client, '  private unregisterActiveRequest(request: http.HttpRequest): void {',
      '  private unregisterActiveRequest(request: http.HttpRequest): void {\n    this.streamCancellers.delete(request);');
    client = replaceChecked(client, '        PrivacyConsent.destroyHttp(pending[i]);',
      '        this.streamCancellers.get(pending[i])?.();\n        PrivacyConsent.destroyHttp(pending[i]);');
    client = replaceChecked(client, '      PrivacyConsent.onHttpCancel(httpReq, () => {',
      "      self.streamCancellers.set(httpReq, () => { finishWithError('请求已取消'); });\n" +
      '      PrivacyConsent.onHttpCancel(httpReq, () => {');
    client = replaceChecked(client, 'textDecoder.decodeWithStream(new Uint8Array(data))',
      'textDecoder.decodeWithStream(new Uint8Array(data), { stream: true })');
    client = replaceChecked(client, "      httpReq.on('dataEnd', () => {\n        processBufferedText(true);",
      "      httpReq.on('dataEnd', () => {\n" +
      '        textBuffer += textDecoder.decodeWithStream(new Uint8Array(0), { stream: false });\n' +
      '        processBufferedText(true);');
    sources.set('utils/CloudModelClient.ets', client);
  }
}

if (simulateUpstream) {
  const home = sources.get('pages/home/HomePage.ets');
  if (!home.includes("Text('今日概览')")) throw new Error('Upstream sync marker not found');
  sources.set('pages/home/HomePage.ets', replaceChecked(home, "Text('今日概览')", "Text('今日概览 · 同步验证')"));
}

if (options.has('--overlay')) {
  const overlay = path.resolve(options.get('--overlay'));
  for (const item of fs.readdirSync(overlay, { recursive: true, withFileTypes: true })) {
    if (!item.isFile() || !item.name.endsWith('.ets')) continue;
    const file = path.join(item.parentPath, item.name);
    sources.set(path.relative(overlay, file).split(path.sep).join('/'), fs.readFileSync(file, 'utf8'));
  }
}
const entry = fs.readFileSync(options.get('--entry') || path.join(here, 'probe/Index.ets'), 'utf8');
sources.set('pages/Index.ets', entry);
const picked = new Set();
function visit(name) {
  if (picked.has(name)) return;
  const source = sources.get(name);
  if (source === undefined) throw new Error(`Missing staged dependency: ${name}`);
  picked.add(name);
  for (const match of source.matchAll(/^[ \t]*import\s+([^;]*?)\s+from\s*(['"])([^'"]+)\2\s*;?/gm)) {
    if (!match[3].startsWith('.')) continue;
    const base = path.posix.normalize(path.posix.join(path.posix.dirname(name), match[3]));
    visit(sources.has(base) ? base : base + '.ets');
  }
}
visit('pages/Index.ets');
if (options.has('--routes')) {
  const routes = JSON.parse(fs.readFileSync(options.get('--routes'), 'utf8')).src;
  for (const route of routes) { visit(route + '.ets'); }
}
const stage = path.join(project, 'entry/src/main/ets');
// Only remove files from this generated probe that were staged by this script.
const previousManifestPath = options.has('--project') ? path.join(project, 'staged-sources.json') : path.join(scratch, 'staged-sources.json');
if (fs.existsSync(previousManifestPath)) {
  for (const file of JSON.parse(fs.readFileSync(previousManifestPath, 'utf8')).files) {
    const destination = path.resolve(stage, file.path);
    if (!destination.startsWith(path.resolve(stage) + path.sep)) throw new Error('Stage path escape');
    if (!picked.has(file.path) && fs.existsSync(destination)) fs.unlinkSync(destination);
  }
}
const files = [];
for (const name of picked) {
  const destination = path.join(stage, name);
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.writeFileSync(destination, sources.get(name));
  files.push({ path: name, unchanged: original.has(name) && original.get(name) === sources.get(name),
    adapter: original.has(name) && original.get(name) !== sources.get(name) });
}
fs.writeFileSync(previousManifestPath, JSON.stringify({ upstream: lock.ref, raw, originalTransport, simulateUpstream, files }, null, 2));

const module = JSON.parse(fs.readFileSync(path.join(project, 'entry/src/main/module.json5'), 'utf8'));
module.module.requestPermissions = [{ name: 'ohos.permission.INTERNET' }];
fs.writeFileSync(path.join(project, 'entry/src/main/module.json5'), JSON.stringify(module, null, 2));
// The template's unused HarmonyOS device tests require Hypium. Probe device tests
// are Android checks; remove only this generated target, not any product test.
fs.writeFileSync(path.join(project, 'entry/build-profile.json5'), JSON.stringify({
  apiType: 'stageMode', targets: [{ name: 'default' }], buildOption: { arkOptions: {} }
}, null, 2));
console.log(JSON.stringify({ staged: files.length, unchanged: files.filter(file => file.unchanged).length,
  adapted: files.filter(file => file.adapter).map(file => file.path), raw, originalTransport, simulateUpstream }));
