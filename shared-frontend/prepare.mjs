import { replaceChecked, applyPatches } from './source-patches.mjs';
import { syncOverlays, recordPrepared } from './build-state.mjs';
import { buildChatContextSource } from './context-source.mjs';
import { buildAndroidGallerySource } from './gallery-source.mjs';
import { buildChatAvatarView, buildChatAvatarPage, buildChatAvatarMessages } from './chat-avatar-source.mjs';
import { buildMimoProtocol } from './voice-source.mjs';
import { verifySources, toolchainPaths, checkedPath } from './bootstrap.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..');
const scratch = path.join(repo, '.arkui-x-validation');
const template = path.join(here, 'template');
const project = checkedPath(repo, '.arkui-x-validation/application/project');
const sourceInputs = verifySources(repo, path.join(scratch, 'reference'));
const tools = toolchainPaths(repo);
fs.mkdirSync(project, { recursive: true });
fs.writeFileSync(checkedPath(repo, '.arkui-x-validation/application/project/local.properties'),
  'sdk.dir=' + tools.openHarmonySdk.split(path.sep).join('/') + '\n' +
  'arkui-x.sdk.dir=' + tools.arkuiXSdk.split(path.sep).join('/') + '\n');
// Only reset these owned generated directories, after checking every old path
// for links. No source/template/SDK or private application data is removed.
for (const relative of ['AppScope', 'hvigor', 'entry/src/main/resources', 'entry/src/main/ets']) {
  const target = checkedPath(repo, '.arkui-x-validation/application/project/' + relative);
  const validate = directory => {
    for (const item of fs.readdirSync(directory, { withFileTypes: true })) {
      if (item.isSymbolicLink()) throw new Error('Generated resources must not contain links');
      if (item.isDirectory()) validate(path.join(directory, item.name));
    }
  };
  if (fs.existsSync(target)) { validate(target); fs.rmSync(target, { recursive: true }); }
}
// Only copy known template configuration and resources, never application data,
// local.properties, signing files, node_modules contents or build outputs.
for (const item of ['AppScope', 'hvigor', 'entry/src/main/ets/entryability']) {
  fs.cpSync(path.join(template, item), path.join(project, item), { recursive: true });
}
for (const input of sourceInputs.files.filter(file => !file.path.includes('/ets/'))) {
  const target = checkedPath(repo, '.arkui-x-validation/application/project/' + (input.target || input.path));
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, input.bytes);
}
for (const item of ['hvigorfile.ts', 'oh-package.json5', 'entry/oh-package.json5', 'entry/hvigorfile.ts', 'entry/src/main/module.json5']) {
  fs.copyFileSync(path.join(template, item), checkedPath(repo, '.arkui-x-validation/application/project/' + item));
}
const writeJson = (name, value) => {
  const file = checkedPath(repo, '.arkui-x-validation/application/project/' + name);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(value, null, 2));
};
writeJson('build-profile.json5', { app: { signingConfigs: [], products: [{ name: 'default',
  compileSdkVersion: 20, compatibleSdkVersion: 20, targetSdkVersion: 20, runtimeOS: 'OpenHarmony' }],
  buildModeSet: [{ name: 'debug' }, { name: 'release' }] },
  modules: [{ name: 'entry', srcPath: './entry', targets: [{ name: 'default', applyToProducts: ['default'] }] }] });
writeJson('.arkui-x/arkui-x-config.json5', { crossplatform: true, modules: ['entry'] });
const app = JSON.parse(fs.readFileSync(path.join(project, 'AppScope/app.json5'), 'utf8'));
app.app.bundleName = 'com.clawmate.shared';
writeJson('AppScope/app.json5', app);
const modules = path.join(project, 'node_modules');
if (fs.lstatSync(modules, { throwIfNoEntry: false })?.isSymbolicLink()) {
  if (fs.realpathSync(modules) !== fs.realpathSync(path.join(tools.buildTools, 'node_modules'))) fs.unlinkSync(modules);
} else if (fs.existsSync(modules)) { throw new Error('Generated node_modules must be a toolchain link'); }
if (!fs.existsSync(modules)) fs.symlinkSync(path.join(tools.buildTools, 'node_modules'), modules, 'junction');
const generatedOverlay = path.join(scratch, 'application/overlays');
syncOverlays(path.join(here, 'adapters'), generatedOverlay, scratch);
const reference = path.join(scratch, 'reference/entry/src/main/ets');
const read = name => fs.readFileSync(path.join(reference, name), 'utf8');
const overlay = (name, value) => {
  const file = path.join(generatedOverlay, name);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, value);
};
const mainTabs = read('pages/main/FloatingMainTabs.ets');
const navigationConstants = ['TAB_BAR_HEIGHT', 'TAB_BAR_BOTTOM_MARGIN', 'TAB_BAR_SIDE_MARGIN'].map(name => {
  const match = mainTabs.match(new RegExp(`const ${name}: number = \\d+;`));
  if (!match) throw new Error('Upstream navigation metric changed: ' + name);
  return 'export ' + match[0];
});
const sourceIndex = read('pages/Index.ets');
overlay('platform/UpstreamMimoVoice.ets', buildMimoProtocol(read('utils/voice/MimoVoiceClient.ets')));
overlay('components/CollectionReminderPanel.ets', replaceChecked(read('components/CollectionReminderPanel.ets'),
  '      Row({ space: 8 }) {', "      Text('Android 会在设定时间附近提醒，可能因省电策略延迟。提醒本身不采集数据，点击通知后需确认任务。').fontSize(12).fontColor(AppTheme.text('#6F7584')).width('100%')\n      Row({ space: 8 }) {"));
overlay('platform/UpstreamChatContext.ets', buildChatContextSource(sourceIndex));
const navigationMethods = ['tabIconResource', 'tabBarStyle'].map(name => {
  const match = sourceIndex.match(new RegExp(`  private ${name}\\([^]*?\\n  \\}`));
  if (!match) throw new Error('Upstream navigation method changed: ' + name);
  const method = replaceChecked(match[0], 'private ', 'static ');
  return name === 'tabBarStyle' ? replaceChecked(method, 'this.tabIconResource', 'NavigationStyle.tabIconResource') : method;
});
overlay('platform/NavigationStyle.ets', "import { AppTheme } from '../utils/AppTheme';\n" +
  "import { MainTabIndex, mainTabIcon, mainTabLabel } from '../pages/main/MainTabConfig';\n" +
  navigationConstants.join('\n') + '\nexport const MAIN_NAVIGATION_INSET: number = TAB_BAR_HEIGHT + TAB_BAR_BOTTOM_MARGIN + 12;\n' +
  'export class NavigationStyle {\n' + navigationMethods.join('\n') + '\n}\n');
overlay('utils/gallery/GalleryAnalyzer.ets', replaceChecked(read('utils/gallery/GalleryAnalyzer.ets'), 'this.ocrService.recognize(pixelMap)', 'this.ocrService.recognizeUri(uri)'));
let avatarService = read('datamanager/avatar/AvatarService.ets');
avatarService = replaceChecked(avatarService, "import { image } from '@kit.ImageKit';",
  "import { image } from '@kit.ImageKit';\nimport { util } from '@kit.ArkTS';\nimport { ChatImages } from '../../platform/ChatImages';");
avatarService = replaceChecked(avatarService, /  private async encodeReferencePhotoToJpeg\(uri: string\): Promise<Uint8Array> \{[^]*?\n  \}/,
  "  private async encodeReferencePhotoToJpeg(uri: string): Promise<Uint8Array> {\n    let images = await ChatImages.prepare([uri], (): boolean => false);\n    let data = images[0];\n    if (!data.startsWith('data:image/jpeg;base64,') || data.length > 1400000) { throw new Error('参考照片准备结果无效'); }\n    return new util.Base64Helper().decodeSync(data.slice(data.indexOf(',') + 1));\n  }");
overlay('datamanager/avatar/AvatarService.ets', avatarService);
let messageList = replaceChecked(read('pages/chat/ChatMessageList.ets'), "import { pasteboard } from '@kit.BasicServicesKit';", "import { pasteboard } from '../../platform/Pasteboard';");
messageList = replaceChecked(messageList, '  private replayAvailable(): boolean {',
  "  private replayAvailable(): boolean {\n    if (this.detail.voice.provider === 'android-system-tts') { return this.voiceStatus !== 'synthesizing'; }");
messageList = replaceChecked(messageList, '          .opacity(this.synthesizing() ? 0.72 : 1)',
  "          .opacity(this.synthesizing() ? 0.72 : 1)\n          .accessibilityText(this.playing() ? '暂停朗读' : this.synthesizing() ? '取消语音生成' : this.detail.voice.status === 'paused' ? '继续朗读' : '朗读消息')");
messageList = replaceChecked(messageList, '          .enabled(this.replayAvailable())', "          .enabled(this.replayAvailable())\n          .accessibilityText('重播缓存语音')");
overlay('pages/chat/ChatMessageList.ets', buildChatAvatarMessages(messageList));
overlay('pages/chat/DigitalAvatarView.ets', buildChatAvatarView(read('pages/chat/DigitalAvatarView.ets')));
let homePage = read('pages/home/HomePage.ets');
for (const method of ['submitAvatarGeneration', 'submitAvatarPhotoGeneration']) {
  homePage = replaceChecked(homePage, `        AppLogger.errorCause('Home.Avatar', '${method} failed', e as Error);`.trimStart(),
    `AppLogger.errorCause('Home.Avatar', '${method} failed', e as Error);\n      showToastSafely('生成未完成：' + (e as Error).message, 2500);`);
}
for (const name of ['selectedRecommendationId', 'selectedTaskSuggestionId', 'avatarGenerationOpen', 'avatarHistoryOpen', 'selectedAvatarVersionId']) {
  homePage = replaceChecked(homePage, '@State ' + name + ':', "@State @Watch('reportAndroidOverlay') " + name + ':');
}
homePage = replaceChecked(homePage, '  private isWideProfileOverview(): boolean {',
  "  @StorageProp('androidHomeBackRequested') @Watch('handleAndroidBack') private androidBackRequest: number = 0;\n" +
  "  private reportAndroidOverlay(): void { AppStorage.setOrCreate('androidHomeOverlayOpen', this.avatarGenerationOpen || this.avatarHistoryOpen || this.selectedAvatarVersionId.length > 0 || this.selectedRecommendationId.length > 0 || this.selectedTaskSuggestionId.length > 0); }\n" +
  "  private handleAndroidBack(): void {\n    if (this.selectedAvatarVersionId.length > 0) { this.selectedAvatarVersionId = ''; }\n" +
  "    else if (this.avatarHistoryOpen) { this.avatarHistoryOpen = false; }\n    else if (this.avatarGenerationOpen) { this.avatarGenerationOpen = false; }\n" +
  "    else if (this.selectedTaskSuggestionId.length > 0) { this.selectedTaskSuggestionId = ''; }\n    else { this.selectedRecommendationId = ''; }\n  }\n" +
  '  private isWideProfileOverview(): boolean {');
// 首页“为你提醒”上方固定显示日历卡片：系统日历日程与常用节假日，均来自本机读取。
homePage = replaceChecked(homePage, "import { HomeEmptyHint } from '../../components/HomeEmptyHint';",
  "import { HomeEmptyHint } from '../../components/HomeEmptyHint';\nimport { CalendarReminderCard } from '../../components/CalendarReminderCard';");
homePage = replaceChecked(homePage,
  "          Text('为你提醒').fontSize(20).fontWeight(FontWeight.Bold).fontColor(AppTheme.text('#11182F')).layoutWeight(1)",
  "          Text('为你提醒').fontSize(20).fontWeight(FontWeight.Bold).fontColor(AppTheme.text('#11182F')).layoutWeight(1)");
homePage = replaceChecked(homePage, "        }.width('92%').margin({ top: 22 })\n\n        if (this.visibleRecommendations().length === 0) {",
  "        }.width('92%').margin({ top: 22 })\n\n        this.CalendarReminderSection()\n\n        if (this.visibleRecommendations().length === 0) {");
homePage = replaceChecked(homePage, '  @Builder EmptyRecommendationCard() {',
  "  @Builder CalendarReminderSection() {\n    Column() {\n      CalendarReminderCard()\n    }.width('92%').margin({ top: 10 })\n  }\n\n" +
  '  @Builder EmptyRecommendationCard() {');
overlay('pages/home/HomePage.ets', homePage);
let debug = applyPatches(read('utils/DebugSettings.ets'), [
  ["import native from 'libentry.so';\n", ''],
  [/^.*native\.(initLogFile|setDebugLogging).*\n/gm, '']
]);
overlay('utils/DebugSettings.ets', debug);
let settings = read('pages/settings/SettingsCenterPage.ets');
overlay('pages/settings/SettingsCenterPage.ets', applyPatches(settings, [
  ['      this.SectionTitle(SettingsSectionId.DataCollection)', "      this.SectionTitle(SettingsSectionId.DataCollection)\n      Button('日历分析').onClick(() => { router.pushUrl({ url: 'pages/settings/CalendarSourcesPage', params: { kind: 'calendar' } }); })\n      Button('邮箱分析').onClick(() => { router.pushUrl({ url: 'pages/settings/MailSourcesPage', params: { kind: 'mail' } }); })\n      Button('界面采集与规划').onClick(() => { router.pushUrl({ url: 'pages/task/GuiPlanPage' }); })\n      Button('聊天长期记忆与本机索引').onClick(() => { router.pushUrl({ url: 'pages/settings/MemorySyncPage' }); })"],
  ['开启后显示并记录聊天和手机 HDC 日志', '开启后记录本次前端诊断；原生推理与自动控制日志尚未接入'],
  // 模型下载：鸿蒙端“下载中禁用按钮、另设暂停”，安卓改为同一按钮在“下载/继续下载”和“暂停”之间切换，
  // 否则暂停后按钮被禁用，无法续传。其余文案与进度面板沿用原页面。
  ['      actionEnabled: !this.downloading && !this.modelScopeDownloading,', '      actionEnabled: !this.downloading,'],
  ["label: this.modelScopePauseRequested ? '继续下载' : '从 ModelScope 下载',", "label: this.modelScopePauseRequested ? '继续下载' : this.modelScopeDownloading ? '暂停下载' : '从 ModelScope 下载',"],
  ['  @Builder ContextCompressionGroup() {', "  @Builder ContextCompressionGroup() {\n    Text('达到估算阈值时自动摘要较早历史，并按保留轮数续接。摘要使用当前配置的模型；失败时明确标记本地抽取。完整历史保留在本机，可随时停止。压缩后仍超预算会提示减少附件或新建会话。').fontSize(12).fontColor('#65718B').width('100%')"]
]));
// Public defaults contain no environment service endpoints, credentials or tokens.
const endpointNames = new Set([...settings.matchAll(/DEFAULT_ENDPOINT_CONFIG\.(\w+)/g)].map(match => match[1]));
overlay('config/DefaultEndpointConfig.ets',
  'interface AndroidEndpointDefaults {\n' + [...endpointNames].sort().map(name =>
    `  ${name}: ${name === 'voiceTtsStreamingEnabled' ? 'boolean' : 'string'};`).join('\n') + '\n}\n' +
  'export const DEFAULT_ENDPOINT_CONFIG: AndroidEndpointDefaults = {\n' + [...endpointNames].sort().map(name =>
    `  ${name}: ${name === 'voiceTtsStreamingEnabled' ? 'false' : "''"},`).join('\n') + '\n};\n' +
  "export function configuredEndpointSecretOrDefault(value: string, fallback: string = ''): string { return value.trim() || fallback; }\n");
const stringKeys = new Set([...settings.matchAll(/@StorageLink\('([^']+)'\)/g)].map(match => match[1]));
['firstLaunchOnboardingVersion', 'androidTaskControlMode', 'threadNumInput', 'desktopCompanionDownloadUrl', 'androidChatAvatarStyle'].forEach(key => stringKeys.add(key));
const booleanKeys = ['androidCloudAccessGranted', 'appDarkMode', 'autoConfirm', 'cloudAutoConfirm'];
stringKeys.delete('voiceTtsStreamingEnabled');
booleanKeys.push('voiceTtsStreamingEnabled');
overlay('platform/SettingsKeys.ets',
  'export const SETTINGS_STRING_KEYS: string[] = ' + JSON.stringify([...stringKeys].sort()) + ';\n' +
  'export const SETTINGS_BOOLEAN_KEYS: string[] = ' + JSON.stringify(booleanKeys) + ';\n');
const galleryConstants = [...new Set([...settings.matchAll(/\b(?:DEFAULT_GALLERY_\w+|GALLERY_\w+_DEFAULT_SNAPSHOT)\b/g)].map(match => match[0]))];
overlay('utils/gallery/GallerySettingsDefaults.ets', galleryConstants.sort().map(name => {
  let value = name.endsWith('_SNAPSHOT') ? name.toLowerCase() : /CONCURRENCY$/.test(name) ? '1' :
    name === 'DEFAULT_GALLERY_IMAGE_SCALE' ? '1' : /(?:SKIP_OCR|USE_OCR_KEYWORD_FILTER|PARALLEL_FIELD_REQUESTS)$/.test(name) ? 'false' : '';
  return `export const ${name}: string = ${JSON.stringify(value)};`;
}).join('\n') + '\nexport function registerGallerySettingsDefaults(): void {}\n' +
  "export function galleryApiUsesLegacyDefaults(url: string, model: string): boolean { return url.length === 0 && model.length === 0; }\n");
let controls = read('pages/settings/SettingsCenterControls.ets');
controls = "import { SettingsStore } from '../../platform/SettingsStore';\n" + replaceChecked(controls, 'this.onValueChange(value);', 'this.onValueChange(value); SettingsStore.scheduleSave();', { all: true });
controls = replaceChecked(controls, 'this.getUIContext().getFocusController().requestFocus(', 'focusControl.requestFocus(');
controls = replaceChecked(controls, '  private previewText(): string {',
  "  private previewText(): string {\n    if (this.value.trim().length === 0 && this.defaultValue.trim().length === 0) { return '未配置'; }");
overlay('pages/settings/SettingsCenterControls.ets', controls);
overlay('pages/chat/ChatSessionDrawer.ets', replaceChecked(read('pages/chat/ChatSessionDrawer.ets'), '选择会话后由上层恢复上下文', '选择历史会话继续聊天'));
let privacy = read('components/PrivacyConsentDialog.ets');
let start = privacy.indexOf('export const PRIVACY_DECLARATION: string =');
let end = privacy.indexOf('// Native ArkUI alert dialog', start);
if (start < 0 || end < 0) throw new Error('Privacy declaration boundary changed');
privacy = privacy.slice(0, start) + 'export const PRIVACY_DECLARATION: string = ' + JSON.stringify(
  '聊天、配置和索引保存在此应用的本地存储。仅在你主动使用云端功能并同意后，相关输入和上下文才会发送至你配置的模型服务。\n\n' +
  '服务提供方的数据保留规则由该服务决定，本应用无法保证其删除或不留存。不要向不信任的服务发送隐私信息。\n\n' +
  '安卓尚未接入华为账号和小艺 A2A。云服务授权不代表账号登录；应用不会通过该入口对外提供个人记忆。\n\n' +
  '拒绝后仍可浏览和编辑本地内容。可在“我的”撤回授权，停止新的请求并尝试取消正在进行的请求；已发送的数据无法收回。相册和麦克风权限会在使用对应功能时另行申请。\n\n') +
  " + '声明版本：' + PRIVACY_VERSION;\n\n" + privacy.slice(end);
overlay('components/PrivacyConsentDialog.ets', privacy);
overlay('components/AiLoginGuard.ets', replaceChecked(read('components/AiLoginGuard.ets'), "title: '请先登录'", "title: '请先授权云服务'"));
overlay('utils/PrivacyConsent.ets', replaceChecked(read('utils/PrivacyConsent.ets'), "export const PRIVACY_VERSION: string = '2026-09-30-v2';", "export const PRIVACY_VERSION: string = '2026-10-26-android-v1';"));
// Kotlin preserves Android content grants; the SDK picker converts them to _data file paths.
overlay('utils/gallery/GalleryPhotoPicker.ets', fs.readFileSync(path.join(here, 'adapters/utils/gallery/GalleryPhotoPicker.ets'), 'utf8'));
let collectionSources = read('pages/collection/CollectionPage.ets');
collectionSources = "import { PersonalSourceEntry } from '../../components/PersonalSourceEntry';\n" + collectionSources;
collectionSources = replaceChecked(collectionSources, '  onOpenDataCollectionSettings: () => void = () => {};',
  '  onOpenDataCollectionSettings: () => void = () => {};\n  onOpenMeeting: () => void = () => {};');
collectionSources = replaceChecked(collectionSources,
  "router.pushUrl({ url: 'pages/gallery/GalleryPage' });\n            })",
  "router.pushUrl({ url: 'pages/gallery/GalleryPage' });\n            })\n            Divider().color(AppTheme.border('#E8EBF2')).margin({ left: 64 })\n            PersonalSourceEntry({ kind: 'calendar', target: 'pages/settings/CalendarSourcesPage' })\n            Divider().color(AppTheme.border('#E8EBF2')).margin({ left: 64 })\n            PersonalSourceEntry({ kind: 'mail', target: 'pages/settings/MailSourcesPage' })\n            Divider().color(AppTheme.border('#E8EBF2')).margin({ left: 64 })\n            PersonalSourceEntry({ kind: 'meeting', onOpenMeeting: () => { this.onOpenMeeting(); } })",
  'collection personal source entries');
overlay('pages/collection/CollectionPage.ets', collectionSources);
let gallery = read('pages/gallery/GalleryPage.ets');
gallery = buildAndroidGallerySource(gallery);
gallery = replaceChecked(gallery, 'import { abilityAccessCtrl, common, Permissions }', 'import { common }');
gallery = replaceChecked(gallery, /  private async ensureMediaLocationPermission\([^]*?\n  \}/,
  '  private async ensureMediaLocationPermission(ctx: common.UIAbilityContext): Promise<boolean> {\n' +
  '    return false; // No broad Android library/location permission is requested.\n  }');
gallery = replaceChecked(gallery, '  onPageHide(): void {',
  '  onBackPress(): boolean {\n' +
  '    if (this.hdcSheetOpen) { this.hdcSheetOpen = false; return true; }\n' +
  '    if (this.settingsOpen) { this.settingsOpen = false; return true; }\n' +
  "    if (this.selectedDetailId.length > 0) { this.closeResultDetail(); return true; }\n" +
  '    router.back(); return true;\n  }\n  onPageHide(): void {');
overlay('pages/gallery/GalleryPage.ets', gallery);
let galleryStorage = read('utils/gallery/GalleryStorage.ets');
galleryStorage = replaceChecked(galleryStorage, "import fs from '@ohos.file.fs';", "import fs from '@ohos.file.fs';\nimport { GalleryScanCheckpoint } from '../../platform/GalleryScanCheckpoint';");
galleryStorage = replaceChecked(galleryStorage, /  static loadScanSession\(filesDir: string\): GalleryScanSession \| null \{[^]*?\n  \}/,
  '  static loadScanSession(filesDir: string): GalleryScanSession | null {\n    GalleryStorage.ensureWorkspace(filesDir);\n    return GalleryScanCheckpoint.read(GalleryStorage.scanSessionPath(filesDir), filesDir);\n  }');
galleryStorage = replaceChecked(galleryStorage, '    GalleryStorage.writeText(GalleryStorage.scanSessionPath(filesDir), ArkJSON.stringify(session, null, 2));',
  '    GalleryScanCheckpoint.write(GalleryStorage.scanSessionPath(filesDir), session, filesDir);');
overlay('utils/gallery/GalleryStorage.ets', galleryStorage);
for (const route of ['pages/collection/CollectionGroupDetailPage', 'pages/task/WorkflowFileViewer']) {
  let page = read(route + '.ets');
  page = replaceChecked(page, /struct (\w+) \{/, 'struct $1 {\n  onBackPress(): boolean { router.back(); return true; }');
  if (route === 'pages/task/WorkflowFileViewer') {
    page = replaceChecked(page, '  private containsAiGeneratedContent(): boolean {',
      '  private containsAiGeneratedContent(): boolean {\n    if (/"source_kind"\\s*:\\s*"android_workflow"/.test(this.fileText)) { return false; }');
  }
  overlay(route + '.ets', page);
}
overlay('utils/gallery/GalleryImageStore.ets', applyPatches(read('utils/gallery/GalleryImageStore.ets'), [
  ['fileIo as fs, fileUri', 'fileIo as fs'],
  ['fileUri.getUriFromPath(path)', "'file://' + path.split('/').map((part: string): string => encodeURIComponent(part)).join('/')"]
]));
overlay('components/TaskDeviceControlPanel.ets', replaceChecked(read('components/TaskDeviceControlPanel.ets'), "      Text('任务控制方式').fontSize(15).fontWeight(FontWeight.Bold).width('100%')",
    "      Text('任务控制方式').fontSize(15).fontWeight(FontWeight.Bold).width('100%')\n" +
    "      Text(this.mode === 'pc' ? '电脑控制待接入 Android 设备桥接。' : '可运行本机工作流和本机图片视觉问答。自动 GUI 待接入。').fontSize(12).fontColor(AppTheme.text('#65718B')).width('100%')"));
// Data-pipeline requests must use the saved model and reject cancelled results
// before validators/checkpoints or later stages can commit them.
overlay('datamanager/DataManagerTypes.ets', replaceChecked(read('datamanager/DataManagerTypes.ets'), 'export interface DataManagerModelConfig {', 'export interface DataManagerModelConfig {\n  model?: string;\n  ensureActive?: () => void;'));
overlay('datamanager/DataManagerFacade.ets', applyPatches(read('datamanager/DataManagerFacade.ets'), [
  ['constructor(filesDir: string) {', 'constructor(filesDir: string, modelClient: DataPipelineModelClient = new DataPipelineModelClient()) {'],
  ['this.modelClient = new DataPipelineModelClient();', 'this.modelClient = modelClient;']
]));
overlay('datamanager/DataPipelineModelClient.ets', applyPatches(read('datamanager/DataPipelineModelClient.ets'), [
  ['        let content = await this.cloudClient.chatCompletionText({', '        config.ensureActive?.();\n        let content = await this.cloudClient.chatCompletionText({'],
  ['          baseUrl: config.baseUrl,', '          baseUrl: config.baseUrl,\n          model: config.model,\n          logResponseContent: kind !== \'chat.memory\',\n          readTimeout: 60000,'],
  ['        dataManagerInfo(`Model request response:', '        config.ensureActive?.();\n        dataManagerInfo(`Model request response:']
]));
let taskPage = read('pages/task/TaskPage.ets');
taskPage = replaceChecked(taskPage, "import { AppTheme }",
  "import Bridge from '@arkui-x.bridge';\nimport { isBuiltInAlipayCollection, taskServiceLabel } from '../../platform/TaskServices';\nimport { WorkflowVlm } from '../../platform/WorkflowVlm';\nimport { AppTheme }");
taskPage = applyPatches(taskPage, [
  ['  @State private batchSyncing: boolean = false;', '  @State private batchSyncing: boolean = false;\n  @State private androidCollectedResultsReady: boolean = false;'],
  ['  onRunWorkflow: (task: WorkflowTask, cancellation: WorkflowCancellation) => Promise<void> =\n    async (_task: WorkflowTask, _cancellation: WorkflowCancellation): Promise<void> => {};',
    '  onRunWorkflow: (task: WorkflowTask, cancellation: WorkflowCancellation) => Promise<boolean> = async (): Promise<boolean> => false;'],
  ['  onRunPhoneWorkflow: (task: WorkflowTask, cancellation: WorkflowCancellation) => Promise<void> =\n    async (_task: WorkflowTask, _cancellation: WorkflowCancellation): Promise<void> => {',
    '  onRunPhoneWorkflow: (task: WorkflowTask, cancellation: WorkflowCancellation) => Promise<boolean> =\n    async (): Promise<boolean> => {'],
  ['    cancellation: WorkflowCancellation = new WorkflowCancellation()): Promise<void> {\n    cancellation.throwIfCancelled();\n    if (this.taskControlMode',
    '    cancellation: WorkflowCancellation = new WorkflowCancellation()): Promise<boolean> {\n    cancellation.throwIfCancelled();\n    if (this.taskControlMode'],
  [`    if (this.taskControlMode === 'phone') {
      await this.onRunPhoneWorkflow(task, cancellation);
    } else { await this.onRunWorkflow(task, cancellation); }`,
   `    let savedSummary = this.taskControlMode === 'phone' ? await this.onRunPhoneWorkflow(task, cancellation) :
      await this.onRunWorkflow(task, cancellation);
    if (savedSummary) { this.androidCollectedResultsReady = true; }`],
  ["        AppLogger.errorCause('CollectionReminder', 'Collection history save failed', e);\n      });\n    }\n  }",
    "        AppLogger.errorCause('CollectionReminder', 'Collection history save failed', e);\n      });\n    }\n    return savedSummary;\n  }"],
  ['    let attemptedTasks = 0;', '    let collectedSummaries = 0;'],
  ['          attemptedTasks++;\n          await this.runSelectedWorkflow(currentTask, cancellation);',
    '          if (await this.runSelectedWorkflow(currentTask, cancellation)) { collectedSummaries++; }'],
  ["            this.tasks = WorkflowStorage.updateTaskRun(this.filesDir, currentTask.id, 'failed', err.message);",
    '            this.tasks = WorkflowStorage.loadTasks(this.filesDir);'],
  [/      \} else if \(attemptedTasks > 0\) \{[^]*?\n      \}\n    \} finally \{/,
    `      } else if (collectedSummaries > 0) {
        showToastSafely(stoppedByError ? '部分任务未完成，已保存采集结果' : '采集结果已保存，可同步到汇总', 2200);
      } else if (!stoppedByError) {
        showToastSafely('已完成开启任务的依次执行', 1500);
      }
    } finally {`],
  ["if (this.batchSyncing) { return '正在同步分析…'; }", "if (this.batchSyncing) { return '正在打开同步…'; }"],
  ["            showToastSafely('批量任务无法执行：' + (e as Error).message, 2200);\n          }\n        })",
    `            showToastSafely('批量任务无法执行：' + (e as Error).message, 2200);
          }
        })
      if (this.androidCollectedResultsReady && !this.batchRunning) {
        Button(this.batchSyncing ? '正在打开同步…' : '同步采集结果').fontSize(13).height(38).margin({ top: 10 })
          .backgroundColor(AppTheme.background('#EEF1FF')).fontColor(AppTheme.text('#276EF1'))
          .enabled(!this.batchSyncing && !this.isTaskExecutionBusy()).onClick(async (): Promise<void> => {
            if (this.batchSyncing || this.isTaskExecutionBusy()) { return; }
            this.batchSyncing = true;
            try { await this.onSyncCollectedTasks(); }
            catch (error) { showToastSafely((error as Error).message, 2200); }
            finally { this.batchSyncing = false; }
          })
      }`],
  ['  @Builder SimpleTaskRow(task: WorkflowTask, scene: WorkflowSceneConfig) {',
    `  private androidTaskService(task: WorkflowTask): string {
    if (isBuiltInAlipayCollection(task)) { return '账单采集 · 单独确认'; }
    try {
      let raw = WorkflowStorage.readTaskWorkflowJson(this.filesDir, task);
      return raw.length <= 256000 ? taskServiceLabel(WorkflowStorage.validateWorkflowJson(raw).steps) : '检查任务配置';
    } catch (error) { return '检查任务配置'; }
  }

  @Builder SimpleTaskRow(task: WorkflowTask, scene: WorkflowSceneConfig) {`],
  ["Text(this.runningTaskId === task.id ? '执行中' : task.enabled ? '已开启' : '已关闭')",
    "Text(this.runningTaskId === task.id ? '执行中' : (task.enabled ? '已开启' : '已关闭') + ' · ' + this.androidTaskService(task))"],
  ["Text(task.enabled ? task.lastStatus : '已暂停')",
    "Text(task.enabled ? (isBuiltInAlipayCollection(task) ? '单次采集' : task.lastStatus) : '已暂停')"],
  ["Text('最近运行：' + task.lastRun)",
    "Text(isBuiltInAlipayCollection(task) ? '打开支付宝后，请自行进入账单页' : '最近运行：' + task.lastRun)"],
  ["Text(task.lastMessage)",
    "Text(isBuiltInAlipayCollection(task) ? '预览确认后，可在本机采集记录中查看' : task.lastMessage)"],
  ["Text('来源模块：' + task.source)", "Text('来源模块：' + task.source + ' · ' + this.androidTaskService(task))"]
]);
taskPage = replaceChecked(taskPage, '  @Builder ToolEditor() {', `  private async pickAndroidWorkflowImage(): Promise<void> {
    let selected = this.selectedPath; let task = this.editingTaskId;
    try {
      let path = await WorkflowVlm.pickImage(this.filesDir, (): boolean => !this.editorOpen || this.selectedPath !== selected || this.editingTaskId !== task);
      if (path.length > 0 && this.editorOpen && this.selectedPath === selected && this.editingTaskId === task) { this.updateSelectedToolInput('image', path); }
    } catch (error) { showToastSafely((error as Error).message); }
  }
  @Builder ToolEditor() {`);
taskPage = replaceChecked(taskPage, "        this.FieldLabel('图片变量')", `        this.FieldLabel('图片引用（Android 使用本机图片）')
        Button('选择本机图片').fontSize(12).onClick(() => { this.pickAndroidWorkflowImage(); })
        Text('使用已保存的 Decider 视觉模型；每次发送前确认。GUI 截图仍受限。').fontSize(11).fontColor(AppTheme.text('#6F7584'))`);
taskPage = applyPatches(taskPage, [
  ['      this.lastAutomaticReminderRequest = request;', "      let choice = await promptAction.showDialog({ title: '执行已开启的任务？', message: '将依次执行已开启的 Android 有界任务；GUI 与未配置模型工具仍会阻断。可停止执行。', buttons: [{ text: '取消', color: '#65718B' }, { text: '执行任务', color: '#276EF1' }] });\n      if (choice.index !== 1) { CollectionReminderService.clearRequest(request); return; }\n      if (!this.active || !this.reminderWindowReady || this.reminderRequest !== request) { return; }\n      this.lastAutomaticReminderRequest = request;"],
  ['!CollectionReminderService.claim(reminderRequest)', '!await CollectionReminderService.claim(reminderRequest)'],
  ["      AppStorage.setOrCreate('collectionReminderActiveBatch', true);", "      if (!this.active || (automatic && !this.reminderWindowReady)) { return; }\n      AppStorage.setOrCreate('collectionReminderActiveBatch', true);"],
  [/    if \(!cancellation.isCancelled\(\) && savedTask !== undefined && savedTask.lastStatus === 'success'\) \{[^]*?\n    \}/, '    // AndroidWorkflow records collection only after saving a real summary artifact.']
]);
taskPage = replaceChecked(taskPage, '  private fileViewerContainsAiGeneratedContent(): boolean {',
  '  private fileViewerContainsAiGeneratedContent(): boolean {\n    if (/"source_kind"\\s*:\\s*"android_workflow"/.test(this.fileViewerText)) { return false; }');
// Deterministic Android workflows do not need cloud login or phone HDC setup.
// The shared native runner reports GUI/model steps as blocked/not configured.
taskPage = applyPatches(taskPage, [
  ['AiLoginGuard.ensure(this.getUIContext())', 'true', { all: true }],
  ["!await SelfHdcSetupGuard.ensure(this.getUIContext(), this.filesDir, 'workflow-start')", 'false', { all: true }],
  ["!await SelfHdcSetupGuard.ensure(this.getUIContext(), this.filesDir, 'workflow-batch-start')", 'false', { all: true }]
]);
taskPage = applyPatches(taskPage, [
  ['      await this.runSelectedWorkflow(currentTask);', '      this.batchCancellation = new WorkflowCancellation();\n      await this.runSelectedWorkflow(currentTask, this.batchCancellation);'],
  ['      await this.runSelectedWorkflow(currentTask, this.batchCancellation);\n    } finally {', "      await this.runSelectedWorkflow(currentTask, this.batchCancellation);\n    } catch (error) {\n      showToastSafely((error as Error).message, 2500);\n    } finally {"],
  ["      this.runningTaskId = '';\n      this.tasks = WorkflowStorage.loadTasks(this.filesDir);", "      this.runningTaskId = ''; this.batchCancellation = null;\n      this.tasks = WorkflowStorage.loadTasks(this.filesDir);"],
  ['  private batchRunButtonText(): string {', "  private batchRunButtonText(): string {\n    if (!this.batchRunning && this.runningTaskId.length > 0) { return '停止当前任务'; }"],
  ['  private batchRunButtonEnabled(): boolean {', '  private batchRunButtonEnabled(): boolean {\n    if (!this.batchRunning && this.runningTaskId.length > 0) { return true; }'],
  ['  private stopEnabledTasks(): void {', '  private stopEnabledTasks(): void {\n    if (!this.batchRunning && this.runningTaskId.length > 0) { this.batchCancellation?.cancel(); return; }'],
  ['if (this.batchRunning) { this.stopEnabledTasks(); return; }', 'if (this.batchRunning || this.runningTaskId.length > 0) { this.stopEnabledTasks(); return; }'],
  ['  aboutToDisappear(): void {', '  aboutToDisappear(): void {\n    this.batchCancellation?.cancel();']
]);
taskPage = applyPatches(taskPage, [
  ["    this.tasks = WorkflowStorage.updateTaskRun(this.filesDir, currentTask.id, '运行中', 'Workflow 正在执行');\n    try {\n      this.batchCancellation = new WorkflowCancellation();",
    `    try {
      if (this.taskControlMode === 'phone' && isBuiltInAlipayCollection(currentTask)) {
        await Bridge.createBridge('ClawMatePlatform').callMethod('guiAlipayCollectionOpen');
        return;
      }
      this.tasks = WorkflowStorage.updateTaskRun(this.filesDir, currentTask.id, '运行中', 'Workflow 正在执行');
      this.batchCancellation = new WorkflowCancellation();`],
  ["Button(this.runningTaskId === task.id ? '执行中' : '运行')", "Button(this.runningTaskId === task.id ? '停止' : this.taskControlMode === 'phone' && isBuiltInAlipayCollection(task) ? '采集账单' : '运行')"],
  ["this.runningTaskId === task.id ? '#A9B7E9' : '#276EF1'", "this.runningTaskId === task.id ? '#B42318' : '#276EF1'"],
  ['            .enabled(!this.isTaskExecutionBusy())\n            .onClick(() => { this.runTask(task); })', '            .enabled(this.runningTaskId === task.id || !this.isTaskExecutionBusy())\n' +
    '            .onClick(() => { if (this.runningTaskId === task.id) { this.batchCancellation?.cancel(); } else { this.runTask(task); } })']
]);
taskPage = replaceChecked(taskPage, '  private pageScroller: Scroller = new Scroller();',
  "  @StorageProp('androidWorkflowBackRequested') @Watch('handleAndroidBack') private androidBackRequest: number = 0;\n" +
  '  private pageScroller: Scroller = new Scroller();\n' +
  "  private androidManagedFiles(category: string): WorkflowManagedFile[] { return category === 'config' ? this.configManagedFiles : category === 'daily' ? this.dailyLogManagedFiles : this.runManagedFiles; }\n" +
  '  private handleAndroidBack(): void { if (this.active && this.configViewOpen) { this.closeConfigView(); } }\n' +
  '  private requestCloseEditor(): void {\n' +
  "    promptAction.showDialog({ title: '放弃当前编辑？', message: '未保存的修改将丢失。', buttons: [\n" +
  "      { text: '继续编辑', color: '#65718B' }, { text: '放弃修改', color: '#B42318' }\n" +
  '    ] }).then((result: promptAction.ShowDialogSuccessResponse): void => { if (result.index === 1) { this.editorOpen = false; } }).catch(() => {});\n  }');
taskPage = replaceChecked(taskPage, '  private closeConfigView(): void {\n    this.configViewOpen = false;',
  '  private closeConfigView(): void {\n    if (this.editorOpen) { this.requestCloseEditor(); return; }\n    this.configViewOpen = false;');
taskPage = replaceChecked(taskPage, '.onClick(() => { this.editorOpen = false; })', '.onClick(() => { this.requestCloseEditor(); })');
taskPage = replaceChecked(taskPage, "Text(subtitle + ' · ' + files.length + ' 个 · ' + WorkflowStorage.formatSize(this.totalFileSize(files)))",
  "Text(subtitle + ' · ' + this.androidManagedFiles(category).length + ' 个 · ' + WorkflowStorage.formatSize(this.totalFileSize(this.androidManagedFiles(category))))");
overlay('pages/task/TaskPage.ets', taskPage);
writeJson('entry/src/main/resources/base/profile/main_pages.json', { src: [
  'pages/Index', 'pages/gallery/GalleryPage', 'pages/collection/CollectionGroupDetailPage',
  'pages/task/WorkflowFileViewer', 'pages/settings/DesktopCompanionPage', 'pages/settings/SelfHdcTestPage',
  'pages/LogView', 'pages/OpTest', 'pages/ProfileTest', 'pages/settings/MemorySyncPage', 'pages/settings/CalendarSourcesPage', 'pages/settings/MailSourcesPage', 'pages/meeting/MeetingPage', 'pages/task/GuiPlanPage' ] });
for (const name of ['source_calendar', 'source_mail', 'source_meeting']) {
  fs.copyFileSync(path.join(template, 'entry/src/main/resources/base/media', name + '.svg'),
    path.join(project, 'entry/src/main/resources/base/media', name + '.svg'));
}
for (const name of ['onboarding_agent', 'onboarding_avatar', 'onboarding_data_home']) {
  fs.copyFileSync(path.join(repo, 'app/src/main/res/drawable-nodpi', name + '.png'),
    path.join(project, 'entry/src/main/resources/base/media', name + '.png'));
}
const prepared = spawnSync(process.execPath, [path.join(repo, 'experiments/arkui-x/prepare-project.mjs'),
  '--project', project, '--entry', path.join(here, 'app/Index.ets'),
  '--extra-lock', path.join(here, 'upstream-lock.json'), '--overlay', generatedOverlay, '--keep-facade',
  '--routes', path.join(project, 'entry/src/main/resources/base/profile/main_pages.json')],
  { cwd: repo, stdio: 'inherit' });
if (prepared.status !== 0) process.exit(prepared.status || 1);
for (const name of ['InputMethod', 'PlatformUi', 'Pasteboard']) {
  const file = path.join(project, 'entry/src/main/ets/platform', name + '.ets');
  fs.writeFileSync(file, replaceChecked(fs.readFileSync(file, 'utf8'), 'ClawMateProbe', 'ClawMatePlatform', { all: true }));
}
// Android adjustResize already shortens the window. Keep the keyboard tracker
// for scroll-follow, but do not apply a second sheet offset or bottom padding.
// Keep the existing IME/focus platform adaptation before applying the chat UI delta.
const chatPageFile = path.join(project, 'entry/src/main/ets/pages/chat/ChatPage.ets');
fs.writeFileSync(chatPageFile, buildChatAvatarPage(fs.readFileSync(chatPageFile, 'utf8')));
const comfortFile = path.join(project, 'entry/src/main/ets/pages/home/HomeComfortSection.ets');
fs.writeFileSync(comfortFile, applyPatches(fs.readFileSync(comfortFile, 'utf8'), [
  ['bottom: this.keyboardHeightVp', 'bottom: 0'],
  ['.translate({ y: -this.keyboardHeightVp })', '.translate({ y: 0 })']
]));
for (const route of ['pages/settings/DesktopCompanionPage', 'pages/settings/SelfHdcTestPage', 'pages/LogView', 'pages/OpTest', 'pages/ProfileTest']) {
  const file = path.join(project, 'entry/src/main/ets', route + '.ets');
  const source = fs.readFileSync(file, 'utf8');
  fs.writeFileSync(file, replaceChecked(source, /struct (\w+) \{/, 'struct $1 {\n  onBackPress(): boolean { router.back(); return true; }'));
}
// Codex page adaptations run inside prepare-project.mjs, before the import walk, because
// they add imports that the walk must follow.
console.log(JSON.stringify({ product: 'com.clawmate.shared', project, sourceCommit: app.sourceCommit }));

// Some API-20 Android transports do not settle the request Promise on destroy.
// Non-streaming memory/home requests need the same explicit cancellation as SSE.
const cloudFile = path.join(project, 'entry/src/main/ets/utils/CloudModelClient.ets');
let cloud = fs.readFileSync(cloudFile, 'utf8');
cloud = applyPatches(cloud, [
  ['  async chatCompletion(options: CloudChatCompletionOptions)', `  private requestCancellable(request: http.HttpRequest, url: string, options: http.HttpRequestOptions,
    timeout: number): Promise<http.HttpResponse> {
    return new Promise<http.HttpResponse>((resolve, reject) => {
      let settled = false;
      let timer = -1;
      let fail = (message: string): void => {
        if (settled) { return; }
        settled = true; clearTimeout(timer); reject(new Error(message));
        PrivacyConsent.destroyHttp(request);
      };
      timer = setTimeout((): void => { fail('模型请求超时'); }, timeout);
      this.streamCancellers.set(request, (): void => { fail('用户取消请求'); });
      PrivacyConsent.onHttpCancel(request, (): void => { fail('隐私授权已撤回'); });
      request.request(url, options).then((response: http.HttpResponse): void => {
        if (settled) { return; }
        settled = true; clearTimeout(timer); resolve(response);
      }).catch((error: Error): void => { fail(error.message); });
    });
  }

  async chatCompletion(options: CloudChatCompletionOptions)`],
  ['let response = await httpReq.request(url, requestOptions);', 'let response = await this.requestCancellable(httpReq, url, requestOptions, Math.min(120000, options.readTimeout || 60000) + 10000);']
]);
fs.writeFileSync(cloudFile, cloud);
recordPrepared(repo);
