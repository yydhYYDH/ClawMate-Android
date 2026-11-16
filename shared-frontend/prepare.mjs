import { replaceChecked, applyPatches } from './source-patches.mjs';
import { syncOverlays, recordPrepared } from './build-state.mjs';
import { buildChatContextSource } from './context-source.mjs';
import { buildAndroidGallerySource } from './gallery-source.mjs';
import { buildChatAvatarView, buildChatAvatarPage, buildChatAvatarMessages } from './chat-avatar-source.mjs';
import { buildMimoProtocol } from './voice-source.mjs';
import { androidPageTemplates, patchCollectionTaskPage } from './collection-workflow-source.mjs';
import { verifySources, toolchainPaths, checkedPath, checkedPaths } from './bootstrap.mjs';
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
const resources = sourceInputs.files.filter(file => !file.path.includes('/ets/'));
const resourceTargets = checkedPaths(project, resources.map(input => input.target || input.path));
for (const [index, input] of resources.entries()) {
  const target = resourceTargets[index];
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, input.bytes);
}
const weiboConfig = 'entry/src/main/resources/rawfile/workflows/configs/default_weibo_hot_search.json';
const androidWeibo = fs.readFileSync(path.join(template, weiboConfig), 'utf8');
fs.writeFileSync(checkedPath(repo, '.arkui-x-validation/application/project/' + weiboConfig), androidWeibo);
const xhsConfig = 'entry/src/main/resources/rawfile/workflows/configs/default_xhs_public_notes.json';
const androidXhs = fs.readFileSync(path.join(template, xhsConfig), 'utf8');
fs.writeFileSync(checkedPath(repo, '.arkui-x-validation/application/project/' + xhsConfig), androidXhs);
const workflowRoot = 'entry/src/main/resources/rawfile/workflows/configs';
const pageTemplates = androidPageTemplates(id => {
  const input = sourceInputs.files.find(file => (file.target || file.path) === workflowRoot + '/' + id + '.json');
  if (!input) throw new Error('Missing locked workflow ' + id);
  return input.bytes.toString('utf8');
});
for (const { id, raw } of pageTemplates) {
  fs.writeFileSync(checkedPath(repo, '.arkui-x-validation/application/project/' + workflowRoot + '/' + id + '.json'), raw);
}
const androidWorkflowFallbacks = [
  ...pageTemplates, { id: 'default_weibo_hot_search', raw: androidWeibo },
  { id: 'default_xhs_public_notes', raw: androidXhs }
].map(({ id, raw }) => `    if (taskId === '${id}') { return ${JSON.stringify(raw)}; }`).join('\n');
const legacyChatSteps = pageTemplates.filter(({ legacySteps }) => legacySteps !== null)
  .map(({ id, legacySteps }) => `    if (taskId === '${id}') { return ${JSON.stringify(JSON.stringify(legacySteps))}; }`).join('\n');
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
overlay('utils/WorkflowTypes.ets', replaceChecked(read('utils/WorkflowTypes.ets'), '  action?: string;\n  x?: number;',
  '  action?: string;\n  item_limit?: number;\n  page_limit?: number;\n  x?: number;'));
overlay('utils/WorkflowStorage.ets', applyPatches(read('utils/WorkflowStorage.ets'), [
  ["  {\n    id: 'default_xiaohongshu_history',", `  {
    id: 'default_xhs_public_notes',
    sceneId: 5,
    fileName: 'default_xhs_public_notes.json',
    rawFilePath: DEFAULT_WORKFLOW_RAW_ROOT + '/default_xhs_public_notes.json'
  },
  {
    id: 'default_xiaohongshu_history',`],
  ['  private static defaultWorkflowJsonForConfig(filesDir: string, config: DefaultWorkflowConfig): string {',
    `  private static androidWorkflowFallback(taskId: string): string {
${androidWorkflowFallbacks}
    return '';
  }

  private static legacyChatSteps(taskId: string): string {
${legacyChatSteps}
    return '';
  }

  private static migrateLegacyChatWorkflow(taskId: string, raw: string): string {
    let original = WorkflowStorage.legacyChatSteps(taskId);
    if (original.length === 0) { return ''; }
    try {
      let stored = WorkflowStorage.validateWorkflowJson(raw, false);
      if (stringifyJsonCompat(stored.steps as Object) !== original) { return ''; }
      stored.steps = WorkflowStorage.validateWorkflowJson(WorkflowStorage.androidWorkflowFallback(taskId)).steps;
      return stringifyJsonCompat(stored as Object, 2);
    } catch (error) { return ''; }
  }

  private static defaultWorkflowJsonForConfig(filesDir: string, config: DefaultWorkflowConfig): string {`],
  ['    if (filesDir.length > 0) {\n      try {\n        let path = WorkflowStorage.configPath(filesDir, config.fileName);',
    `    let androidDefault = WorkflowStorage.androidWorkflowFallback(config.id);
    if (androidDefault.length > 0) { return androidDefault; }
    if (filesDir.length > 0) {
      try {
        let path = WorkflowStorage.configPath(filesDir, config.fileName);`],
  ['    let userModified = storedTask.isUserModified === true;', `    let userModified = storedTask.isUserModified === true;
    let migratedChat = userModified ? WorkflowStorage.migrateLegacyChatWorkflow(defaultTask.id, storedTask.workflowJson) : '';`],
  ['workflowJson: userModified ? WorkflowStorage.nonEmptyOrDefault(storedTask.workflowJson, defaultTask.workflowJson) :',
    'workflowJson: migratedChat.length > 0 ? migratedChat : userModified ? WorkflowStorage.nonEmptyOrDefault(storedTask.workflowJson, defaultTask.workflowJson) :'],
  ['      let rawJson = WorkflowStorage.readText(WorkflowStorage.configPath(filesDir, task.configFileName));\n      let workflow =',
    `      let currentJson = WorkflowStorage.readText(WorkflowStorage.configPath(filesDir, task.configFileName));
      let migratedChat = WorkflowStorage.migrateLegacyChatWorkflow(task.id, currentJson);
      let rawJson = migratedChat.length > 0 ? migratedChat : currentJson;
      let workflow =`],
  [/  static defaultWeiboWorkflow\(\): string \{[^]*?\n  \}/,
    '  static defaultWeiboWorkflow(): string { return ' + JSON.stringify(androidWeibo) + '; }'],
  ["stepType === 'loop' || stepType === 'if';", "stepType === 'loop' || stepType === 'if' || stepType === 'android_accessibility';"],
  ['      title: WorkflowStorage.nonEmptyOrDefault(storedTask.title, defaultTask.title),\n      source: WorkflowStorage.nonEmptyOrDefault(storedTask.source, defaultTask.source),',
    `      title: defaultTask.id === 'default_weibo_hot_search' && !userModified ? defaultTask.title :
        WorkflowStorage.nonEmptyOrDefault(storedTask.title, defaultTask.title),
      source: defaultTask.id === 'default_weibo_hot_search' && !userModified ? defaultTask.source :
        WorkflowStorage.nonEmptyOrDefault(storedTask.source, defaultTask.source),`]
]));
const mainTabs = read('pages/main/FloatingMainTabs.ets');
const navigationConstants = ['TAB_BAR_HEIGHT', 'TAB_BAR_BOTTOM_MARGIN', 'TAB_BAR_SIDE_MARGIN'].map(name => {
  const match = mainTabs.match(new RegExp(`const ${name}: number = \\d+;`));
  if (!match) throw new Error('Upstream navigation metric changed: ' + name);
  return 'export ' + match[0];
});
const sourceIndex = read('pages/Index.ets');
overlay('platform/UpstreamMimoVoice.ets', buildMimoProtocol(read('utils/voice/MimoVoiceClient.ets')));
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
overlay('pages/chat/ChatMessageList.ets', replaceChecked(buildChatAvatarMessages(messageList), 'app.media.chat_assistant_avatar', 'app.media.clawmate_secretary'));
overlay('pages/chat/DigitalAvatarView.ets', applyPatches(buildChatAvatarView(read('pages/chat/DigitalAvatarView.ets')), [
  ['app.media.chat_assistant_avatar', 'app.media.clawmate_secretary'],
  ['app.media.home_explorer_mascot', 'app.media.clawmate_secretary']
]));
// Use the shared board and calendar sheet with Android's own data sources.
let homePage = applyPatches(read('pages/home/HomePage.ets'), [
  ["import { showMailTodayDialog } from '../../components/MailTodayDialog';",
    "import { HomePersonalSources } from '../../platform/HomePersonalSources';"],
  ['  @Prop active: boolean = false;', "  @Prop @Watch('refreshHomeSources') active: boolean = false;"],
  ['  private sourceOpen: boolean = false;', `  private sourceOpen: boolean = false;
  private secretaryArea: Area | null = null;
  private sources: HomePersonalSources = new HomePersonalSources();
  @State private calendarAvailable: boolean = false;
  @State private mailAvailable: boolean = false;
  @State private calendarItems: HomeRecommendationViewModel[] = [];

  aboutToAppear(): void { this.refreshHomeSources(); }

  private async refreshHomeSources(): Promise<void> {
    if (!this.active) { return; }
    await this.sources.refresh((calendar: boolean, mail: boolean, items: HomeRecommendationViewModel[]): void => {
      if (!this.active) { return; }
      this.calendarAvailable = calendar;
      this.mailAvailable = mail;
      this.calendarItems = items;
    });
  }`],
  ['this.profile.recommendations.filter((item: HomeRecommendationViewModel): boolean => !item.completed).length',
    `this.profile.recommendations.concat(this.calendarItems).filter((item: HomeRecommendationViewModel): boolean =>
      !item.completed && (item.calendarEnd === undefined || item.calendarEnd > Date.now())).length`],
  ['this.profile.mailReminder', 'this.mailAvailable'],
  ['this.profile.calendarEnabled', 'this.calendarAvailable'],
  ["if (await showCalendarWeekDialog(this.getUIContext(), this.profile.recommendations, true,\n          this.profile.calendarError || '', this.onSetRecommendationCompleted)) { this.onOpenCalendarSettings(); }",
    `await this.refreshHomeSources();
        if (await showCalendarWeekDialog(this.getUIContext(), this.calendarItems, true,
          this.sources.calendarError, (id: string, completed: boolean): void => {
            this.calendarItems = this.sources.complete(this.calendarItems, id, completed);
          })) { this.onOpenCalendarSettings(); }`],
  ['} else if (await showMailTodayDialog(this.getUIContext(), this.onMailChanged)) { this.onOpenMailSettings(); }',
    '} else { this.onOpenMailSettings(); }'],
  ["      .onAreaChange((_old: Area, area: Area): void => {", `      .onVisibleAreaChange([0.0], (visible: boolean): void => { if (visible) { this.refreshHomeSources(); } })
      .onAreaChange((_old: Area, area: Area): void => {`]
]);
homePage = applyPatches(homePage, [
  ['  @State private sheetOpen: boolean = false;', `  @State @Watch('reportAndroidSheet') private sheetOpen: boolean = false;
  @StorageProp('androidHomeBackRequested') @Watch('closeAndroidSheet') private androidBack: number = 0;
  private reportAndroidSheet(): void { AppStorage.setOrCreate('androidHomeOverlayOpen', this.sheetOpen); }
  private closeAndroidSheet(): void { if (this.active) { this.sheetOpen = false; } }`],
  ["    this.bubble = '';\n  }", "    this.sheetOpen = false;\n    this.bubble = '';\n  }"],
  ["    this.speak('收到，交给' + secretaryRouteLabel(route) + '办理。');", "    this.speak('正在处理交办请求…');"]
]);
// Use an in-app gesture so no note payload leaves the app.
homePage = applyPatches(homePage, [
  [/  private drop\(event: DragEvent\): void \{[^]*?\n  \}/, `  private dragTarget(event: GestureEvent): boolean {
    const finger = event.fingerList[0];
    const target = this.secretaryArea;
    if (!finger || target === null) { return false; }
    const x = target.globalPosition.x;
    const y = target.globalPosition.y;
    if (x === undefined || y === undefined) { return false; }
    const left = parseFloat(x.toString());
    const top = parseFloat(y.toString());
    return finger.globalX >= left && finger.globalX <= left + parseFloat(target.width.toString()) &&
      finger.globalY >= top && finger.globalY <= top + parseFloat(target.height.toString());
  }

  private drop(): void {
    const note = this.lifted;
    const accepted = this.overSecretary;
    this.endDrag();
    if (!accepted || !note) { return; }
    const current = this.board().find((item: HomeNote): boolean => item.key === note.key);
    if (current && current.canDelegate && !current.completed) { this.accept(current); }
  }`],
  [/\n      \.allowDrop\(\['general.plain-text'\]\)[^]*?\.onDrop\(\(event: DragEvent\): void => \{ this.drop\(event\); \}\)/,
    "\n      .onAreaChange((_old: Area, area: Area): void => { this.secretaryArea = area; })"],
  ['                onRelease: (): void => { this.endDrag(); },', `                onMove: (event: GestureEvent): void => { this.overSecretary = this.dragTarget(event); },
                onFinishDrag: (): void => { this.drop(); },
                onRelease: (): void => { this.endDrag(); },`]
]);
overlay('pages/home/HomePage.ets', homePage);
overlay('pages/home/SecretaryNoteCard.ets', applyPatches(read('pages/home/SecretaryNoteCard.ets'), [
  ["import { unifiedDataChannel } from '@kit.ArkData';\n", ''],
  ['  onRelease: () => void = (): void => {};',
    '  onMove: (event: GestureEvent) => void = (_event: GestureEvent): void => {};\n  onFinishDrag: () => void = (): void => {};\n  onRelease: () => void = (): void => {};'],
  [/  private lift\(event: DragEvent\): DragItemInfo \{[^]*?\n  \}\n\n/, ''],
  [/        \.width\('100%'\).height\('100%'\).draggable\(true\)[^]*?\.onDragEnd\(\(\): void => \{ this.onRelease\(\); \}\)/,
    `        .width('100%').height('100%')
        .priorityGesture(GestureGroup(GestureMode.Sequence,
          LongPressGesture().onAction((): void => { this.onLift(); }),
          PanGesture().onActionUpdate((event: GestureEvent): void => { this.onMove(event); })
            .onActionEnd((): void => { this.onFinishDrag(); })
        ).onCancel((): void => { this.onRelease(); }))`]
]));
overlay('utils/calendar/CalendarMemoryPolicy.ets', applyPatches(read('utils/calendar/CalendarMemoryPolicy.ets'), [
  ['HomeRecommendationViewModel, IndexedEvent', 'HomeRecommendationViewModel'],
  [/export interface CalendarSource \{[^]*?(?=export interface CalendarAgendaGroup)/, ''],
  [/export const CALENDAR_REMINDER_OVERVIEW_ID[^]*?(?=export function calendarAgenda\()/, ''],
  [/export interface CalendarSeries \{[^]*?(?=export function calendarDate\()/, ''],
  [/export function calendarWindow\([^]*$/, '']
]));
const accountSource = read('pages/settings/HuaweiAccountCard.ets');
const accountBuilder = accountSource.indexOf('  @Builder AccountContent()');
if (accountBuilder < 0) throw new Error('Missing upstream Huawei account layout');
overlay('pages/settings/HuaweiAccountCard.ets', `import { AppTheme } from '../../utils/AppTheme';
import { promptAction } from '@kit.ArkUI';

@Component
export struct HuaweiAccountCard {
  onOpenCloudAccess: () => void = (): void => {};

` + applyPatches(accountSource.slice(accountBuilder), [
  ["this.sessionActive ? '已登录' : '未登录'", "'未登录'"],
  [/      if \(this.sessionActive\) \{[^]*?\n      \}\n/, ''],
  [/    if \(!this.sessionActive\) \{[^]*?\n    \}/, `    Button({ type: ButtonType.Normal }) {
      Row({ space: 6 }) {
        Image($r('app.media.huawei_account_white')).width(24).height(24).objectFit(ImageFit.Contain)
        Text('华为账号登录').fontSize(16).fontWeight(FontWeight.Medium).fontColor(Color.White)
      }.justifyContent(FlexAlign.Center)
    }.width('100%').height(44).borderRadius(24).margin({ top: 18 }).backgroundColor('#CF0A2C')
      .accessibilityText('华为账号登录').onClick(async (): Promise<void> => {
        const choice = await promptAction.showDialog({
          title: '华为账号登录',
          message: 'Android 版暂未接入华为账号。使用自行配置的云模型可在「个人与隐私」授权。',
          buttons: [{ text: '知道了', color: '#65718B' }, { text: '前往设置', color: '#276EF1' }]
        });
        if (choice.index === 1) { this.onOpenCloudAccess(); }
      })`]
]));
// The existing Android Codex handoff handles target confirmation and optional local excerpts.
const homeContext = read('datamanager/HomeCodexContext.ets');
overlay('datamanager/HomeCodexContext.ets', homeContext.slice(homeContext.indexOf('export interface HomeCodexEvidence'),
  homeContext.indexOf('export function homeCodexImageLabel')));
overlay('pages/home/HomeSecretary.ets', replaceChecked(read('pages/home/HomeSecretary.ets'), "'千问'", "'云端聊天'"));
overlay('components/HomeTaskExecutionSheet.ets', applyPatches(read('components/HomeTaskExecutionSheet.ets'), [
  ["'由小 C 操作手机上的目标应用。'", "'打开任务页，选择并确认可用的 Android 采集工作流。'"],
  ["'在手机上的千问新对话中发送需求，需已安装并登录千问。'", "'开启新会话，将需求发送到你已配置的云端模型。'"],
  ["'在手机上执行'", "'打开手机任务'" ]
]));
let debug = applyPatches(read('utils/DebugSettings.ets'), [
  ["import native from 'libentry.so';\n", ''],
  [/^.*native\.(initLogFile|setDebugLogging).*\n/gm, '']
]);
overlay('utils/DebugSettings.ets', debug);
let settings = applyPatches(read('pages/settings/SettingsCenterPage.ets'), [
  ['import { FrameCallback, promptAction, router }', 'import { promptAction, router }'],
  [/class SettingsScrollFrame extends FrameCallback \{[^]*?\n\}\n\n/, ''],
  [/this.getUIContext\(\).postFrameCallback\(new SettingsScrollFrame\(\(\): void => \{([^]*?)\n    \}\)\);/,
    'setTimeout((): void => {$1\n    }, 32);'],
  [/^import .*?(?:StartupSourceSync|CalendarMemoryPanel|MailSettingsPanel).*;\n/gm, ''],
  ["import { HuaweiPrivacyAccess } from '../../utils/HuaweiPrivacyAccess';", "import { PRIVACY_DECLARATION } from '../../components/PrivacyConsentDialog';"],
  ['HuaweiPrivacyAccess.openPolicy(this.getUIContext());', "promptAction.showDialog({ title: '隐私政策', message: PRIVACY_DECLARATION, buttons: [{ text: '关闭', color: '#276EF1' }] }).catch(() => {});"],
  [/^  .*STARTUP_SOURCE_SYNC_ENABLED_KEY.*\n/gm, ''],
  [/^    this.startupSyncEnabled = new StartupSourceSync.*\n/gm, ''],
  ["import { AppTheme }", "import { PersonalSourceEntry } from '../../components/PersonalSourceEntry';\nimport { AndroidCloudAccessCard } from './AndroidCloudAccessCard';\nimport { SettingsStore } from '../../platform/SettingsStore';\nimport { AppTheme }"],
  ['      HuaweiAccountCard({ sessionActive: $huaweiAccountSessionActive })',
    '      HuaweiAccountCard({ onOpenCloudAccess: (): void => { this.openSection(SettingsSectionId.PersonalPrivacy); } })'],
  ['      this.PrivacyDeclarationEntry()', `      this.PrivacyDeclarationEntry()
      AndroidCloudAccessCard({ sessionActive: $huaweiAccountSessionActive }).width('92%')
      Column() {
        SettingsNavigationRow({ title: '人物资料', icon: $r('app.media.settings_people'),
          onOpen: (): void => { router.pushUrl({ url: 'pages/collection/PeopleInfoPage' }); } })
      }.width('92%').backgroundColor($r('app.color.ui_surface')).borderRadius(22).clip(true)`],
  ['  @Builder ModelAgentsSection() {\n    Column() {', `  @Builder ModelAgentsSection() {
    Column() {
      Column() {
        SettingsNavigationRow({ title: '数字分身', icon: $r('app.media.settings_account'),
          onOpen: (): void => { router.pushUrl({ url: 'pages/settings/AvatarManagementPage' }); } })
      }.width('92%').backgroundColor($r('app.color.ui_surface')).borderRadius(22).clip(true)`],
  [/    CollectionSourceRow\(\{ title: '日历分析'[^]*?SettingsSectionId.MailAnalysis\); \} \}\)/,
    "    PersonalSourceEntry({ kind: 'calendar', target: 'pages/settings/CalendarSourcesPage' })\n    CollectionFormDivider()\n    PersonalSourceEntry({ kind: 'mail', target: 'pages/settings/MailSourcesPage' })"],
  [/  private changeStartupSyncEnabled\([^]*?(?=  @Builder DataCollectionSection)/, ''],
  [/      CollectionFormGroup\(\{ title: '同步', footer: [^]*?this.StartupSyncSettings\(\); \} \}\)\n/, ''],
  [/      if \(this.selectedSectionId === SettingsSectionId.CalendarAnalysis\) \{[^]*?else if \(this.selectedSectionId === SettingsSectionId.WechatCollection\)/,
    '      if (this.selectedSectionId === SettingsSectionId.WechatCollection)'],
  ["    CollectionSourceRow({ title: '文字识别'", `    CollectionSourceRow({ title: '界面采集与规划', icon: $r('app.media.settings_automation'),
      onOpen: (): void => { router.pushUrl({ url: 'pages/task/GuiPlanPage' }); } })
    CollectionFormDivider()
    CollectionSourceRow({ title: '聊天长期记忆与本机索引', icon: $r('app.media.settings_storage'),
      onOpen: (): void => { router.pushUrl({ url: 'pages/settings/MemorySyncPage' }); } })
    CollectionFormDivider()
    CollectionSourceRow({ title: '文字识别'`],
  ['  @Builder ContextCompressionGroup() {', "  @Builder ContextCompressionGroup() {\n    Text('达到估算阈值时自动摘要较早历史。完整历史保留在本机。').fontSize(12).fontColor($r('app.color.ui_text_secondary')).width('100%')"],
  ['    this.modelScopeMnnRepo = repo;', '    this.modelScopeMnnRepo = repo;\n    SettingsStore.scheduleSave();'],
  ['    this.cloudApiKey = next.apiKey', '    SettingsStore.scheduleSave();\n    this.cloudApiKey = next.apiKey']
]);
overlay('pages/settings/SettingsCenterPage.ets', settings);
// Public defaults contain no environment service endpoints, credentials or tokens.
const endpointNames = new Set([...settings.matchAll(/DEFAULT_ENDPOINT_CONFIG\.(\w+)/g)].map(match => match[1]));
overlay('config/DefaultEndpointConfig.ets',
  'interface AndroidEndpointDefaults {\n' + [...endpointNames].sort().map(name =>
    `  ${name}: ${name === 'voiceTtsStreamingEnabled' ? 'boolean' : 'string'};`).join('\n') + '\n}\n' +
  'export const DEFAULT_ENDPOINT_CONFIG: AndroidEndpointDefaults = {\n' + [...endpointNames].sort().map(name =>
    `  ${name}: ${name === 'voiceTtsStreamingEnabled' ? 'false' : "''"},`).join('\n') + '\n};\n' +
  "export function configuredEndpointValueOrDefault(value: string, fallback: string = ''): string { return value.trim() || fallback; }\n" +
  "export function configuredEndpointSecretOrDefault(value: string, fallback: string = ''): string { return value.trim() || fallback; }\n");
const stringKeys = new Set([...settings.matchAll(/@StorageLink\('([^']+)'\)/g)].map(match => match[1]));
['firstLaunchOnboardingVersion', 'androidTaskControlMode', 'threadNumInput', 'desktopCompanionDownloadUrl', 'androidChatAvatarStyle', 'secretaryCodexComputer', 'secretaryCodexWorkspace',
  'androidCalendarCompletedIds'].forEach(key => stringKeys.add(key));
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
overlay('pages/codex/CodexChatTargetPicker.ets', applyPatches(read('pages/codex/CodexChatTargetPicker.ets'), [
  [/^import /, "import { SettingsStore } from '../../platform/SettingsStore';\nimport "],
  [/^PersistentStorage.persistProp\(SECRETARY_\w+, ''\);\n/gm, ''],
  ['  AppStorage.setOrCreate(SECRETARY_WORKSPACE, workspaceId);', '  AppStorage.setOrCreate(SECRETARY_WORKSPACE, workspaceId);\n  SettingsStore.scheduleSave();'],
  ["  let selected: number | null = saved.findIndex((item: CodexSavedConnection): boolean => item.id === preferred);",
    "  let selected: number | null = secretary ? saved.findIndex((item: CodexSavedConnection): boolean => item.id === preferred) : -1;"]
]));
overlay('pages/chat/ChatSessionDrawer.ets', replaceChecked(read('pages/chat/ChatSessionDrawer.ets'), '选择会话后由上层恢复上下文', '选择历史会话继续聊天'));
let privacy = read('components/PrivacyConsentDialog.ets');
let start = privacy.indexOf('export const PRIVACY_DECLARATION: string =');
let end = privacy.indexOf('// Native ArkUI alert dialog', start);
if (start < 0 || end < 0) throw new Error('Privacy declaration boundary changed');
privacy = privacy.slice(0, start) + 'export const PRIVACY_DECLARATION: string = ' + JSON.stringify(
  '聊天、配置和索引保存在此应用的本地存储。仅在你主动使用云端功能并同意后，相关输入和上下文才会发送至你配置的模型服务。\n\n' +
  '服务提供方的数据保留规则由该服务决定，本应用无法保证其删除或不留存。不要向不信任的服务发送隐私信息。\n\n' +
  '安卓尚未接入华为账号和小艺 A2A。云服务授权不代表账号登录；应用不会通过该入口对外提供个人记忆。\n\n' +
  '拒绝后仍可浏览和编辑本地内容。可在“我的 → 个人与隐私”撤回授权，停止新的请求并尝试取消正在进行的请求；已发送的数据无法收回。相册和麦克风权限会在使用对应功能时另行申请。\n\n') +
  " + '声明版本：' + PRIVACY_VERSION;\n\n" + privacy.slice(end);
overlay('components/PrivacyConsentDialog.ets', privacy);
overlay('components/AiLoginGuard.ets', replaceChecked(read('components/AiLoginGuard.ets'), "title: '请先登录'", "title: '请先授权云服务'"));
overlay('utils/PrivacyConsent.ets', replaceChecked(read('utils/PrivacyConsent.ets'), "export const PRIVACY_VERSION: string = '2026-09-30-v2';", "export const PRIVACY_VERSION: string = '2026-10-26-android-v1';"));
// Kotlin preserves Android content grants; the SDK picker converts them to _data file paths.
overlay('utils/gallery/GalleryPhotoPicker.ets', fs.readFileSync(path.join(here, 'adapters/utils/gallery/GalleryPhotoPicker.ets'), 'utf8'));
let collectionSources = applyPatches(read('pages/collection/CollectionPage.ets'), [
  ["import { FrostedPage }", "import { PersonalSourceEntry } from '../../components/PersonalSourceEntry';\nimport { FrostedPage }"],
  ['  onOpenDataCollectionSettings: () => void = () => {};',
    '  onOpenDataCollectionSettings: () => void = () => {};\n  onOpenMeeting: () => void = () => {};'],
  ["route: string) {\n    Button", "open: () => void) {\n    Button"],
  [".onClick((): void => { router.pushUrl({ url: route }); })", '.onClick(open)'],
  ["'#28618A', 'pages/gallery/GalleryPage')", "'#28618A', (): void => { router.pushUrl({ url: 'pages/gallery/GalleryPage' }); })"],
  ["this.AnalysisShortcut('录音分析', $r('app.media.source_recording_white'), '#D96B56', '#AC425F', 'pages/recording/RecordingPage')",
    "this.AnalysisShortcut('录音分析', $r('app.media.source_recording_white'), '#D96B56', '#AC425F', this.onOpenMeeting)"],
  [/        CollectionSourceRow\(\{ title: '日历分析'[^]*?this.onOpenMailAnalysis\(\); \} \}\)/,
    "        PersonalSourceEntry({ kind: 'calendar', target: 'pages/settings/CalendarSourcesPage' })\n        CollectionFormDivider()\n        PersonalSourceEntry({ kind: 'mail', target: 'pages/settings/MailSourcesPage' })"]
]);
overlay('pages/collection/CollectionPage.ets', collectionSources);
overlay('components/CollectionFormControls.ets', applyPatches(read('components/CollectionFormControls.ets'), [
  ['  @Prop syncing: boolean = false;', "  @Prop syncing: boolean = false;\n  @Prop syncLabel: string = '';"],
  ["Button(this.syncing ? '同步中' : '同步')", "Button(this.syncLabel || (this.syncing ? '同步中' : '同步'))"],
  ["this.title + (this.syncing ? '同步中' : '同步')",
    "this.title + (this.syncLabel || (this.syncing ? '同步中' : '同步'))"]
]));
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
for (const route of ['pages/collection/CollectionGroupDetailPage', 'pages/collection/PeopleInfoPage', 'pages/task/WorkflowFileViewer']) {
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
// Data-pipeline requests must use the saved model and reject cancelled results
// before validators/checkpoints or later stages can commit them.
overlay('datamanager/DataManagerTypes.ets', applyPatches(read('datamanager/DataManagerTypes.ets'), [
  ['export interface DataManagerModelConfig {', 'export interface DataManagerModelConfig {\n  model?: string;\n  ensureActive?: () => void;'],
  ['export interface IndexedEvent {', `export interface MemorySubjectRef { id: string; name: string; relation: string; is_self: boolean; }
export interface MemoryFactSource { kind: string; subject_id: string; field: string; authority: string; }

export interface IndexedEvent {
  subjects?: MemorySubjectRef[];
  fact_source?: MemoryFactSource;`],
  ['export interface QueryOptions {', 'export interface QueryOptions {\n  subjectIds?: string[];'],
  ['export interface HomeRecommendationViewModel {', `export interface HomeRecommendationViewModel {
  relatedFacts?: string[];
  calendarStart?: number;
  calendarEnd?: number;
  calendarAllDay?: boolean;
  calendarLocation?: string;`]
]));
overlay('datamanager/DataManagerFacade.ets', applyPatches(read('datamanager/DataManagerFacade.ets'), [
  [/^import /, "import { collectionProfileNote } from './CollectionProfileNote';\nimport "],
  ['constructor(filesDir: string) {', 'constructor(filesDir: string, modelClient: DataPipelineModelClient = new DataPipelineModelClient()) {'],
  ['this.modelClient = new DataPipelineModelClient();', 'this.modelClient = modelClient;'],
  ['  loadProfileMarkdown(): string {', `  loadCollectionSecretaryNote(): string { return collectionProfileNote(this.indexService.loadProfileState()); }

  loadProfileMarkdown(): string {`]
]));
overlay('datamanager/DataPipelineModelClient.ets', applyPatches(read('datamanager/DataPipelineModelClient.ets'), [
  ['        let content = await this.cloudClient.chatCompletionText({', '        config.ensureActive?.();\n        let content = await this.cloudClient.chatCompletionText({'],
  ['          baseUrl: config.baseUrl,', '          baseUrl: config.baseUrl,\n          model: config.model,\n          logResponseContent: kind !== \'chat.memory\',\n          readTimeout: 60000,'],
  ['        dataManagerInfo(`Model request response:', '        config.ensureActive?.();\n        dataManagerInfo(`Model request response:']
]));
let taskPage = applyPatches(read('pages/task/TaskPage.ets'), [
  ["import { AppTheme }", "import Bridge from '@arkui-x.bridge';\nimport { taskServiceLabel } from '../../platform/TaskServices';\nimport { WorkflowVlm } from '../../platform/WorkflowVlm';\nimport { AppTheme }"],
  ['  @State private batchSyncing: boolean = false;', '  @State private batchSyncing: boolean = false;\n  @State private androidCollectedResultsReady: boolean = false;'],
  [/  onRunWorkflow: [^]*?(?=  onRunPhoneWorkflow:)/,
    '  onRunWorkflow: (task: WorkflowTask, cancellation: WorkflowCancellation) => Promise<boolean> = async (): Promise<boolean> => false;\n'],
  [/  onRunPhoneWorkflow: [^]*?\n  \};/,
    '  onRunPhoneWorkflow: (task: WorkflowTask, cancellation: WorkflowCancellation, interactive: boolean) => Promise<boolean> =\n    async (): Promise<boolean> => false;'],
  ["import { AiLoginGuard } from '../../components/AiLoginGuard';\n", ''],
  ["import { SelfHdcSetupGuard } from '../../utils/selfhdc/SelfHdcSetupGuard';\n", ''],
  [/^    if \(!AiLoginGuard.ensure\(this.getUIContext\(\)\)\) \{(?: return; \}|\n      return;\n    \})\n/gm, ''],
  [/^        if \(!AiLoginGuard.ensure\(this.getUIContext\(\)\)\) \{\n          stoppedByCancel = true;\n          break;\n        \}\n/m, ''],
  [/      let preparedSettings: SelfHdcTaskSettings[^]*?else if \(!await this.onEnsureDesktopCompanion\(\)\) \{ return; \}/,
    "      if (this.taskControlMode !== 'phone' && !await this.onEnsureDesktopCompanion()) { return; }"],
  [/^    let preparedSettings: SelfHdcTaskSettings[^]*?^    \}\n/m, ''],
  ['      await this.runSelectedWorkflow(currentTask, new WorkflowCancellation(), preparedSettings);',
    '      this.batchCancellation = new WorkflowCancellation();\n      await this.runSelectedWorkflow(currentTask, this.batchCancellation, true);'],
  ['      this.preparingTaskId = \'\';\n      this.runningTaskId = \'\';', "      this.preparingTaskId = '';\n      this.runningTaskId = '';\n      this.batchCancellation = null;"],
  [/  private async runSelectedWorkflow\([^]*?\n  \}/, `  private async runSelectedWorkflow(task: WorkflowTask, cancellation: WorkflowCancellation,
    interactive: boolean = false): Promise<boolean> {
    cancellation.throwIfCancelled();
    const saved = this.taskControlMode === 'phone' ? await this.onRunPhoneWorkflow(task, cancellation, interactive) :
      await this.onRunWorkflow(task, cancellation);
    if (saved) { this.androidCollectedResultsReady = true; }
    return saved;
  }`],
  ['    let attemptedTasks = 0;', '    let collectedSummaries = 0;'],
  ['          attemptedTasks++;\n          await this.runSelectedWorkflow(currentTask, cancellation, i === 0 ? preparedSettings : undefined);',
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
  ['      this.lastAutomaticReminderRequest = request;', `      const choice = await promptAction.showDialog({ title: '执行已开启的任务？',
        message: '将依次执行已开启的 Android 任务。可随时停止。',
        buttons: [{ text: '取消', color: '#65718B' }, { text: '执行任务', color: '#276EF1' }] });
      if (choice.index !== 1) { CollectionReminderService.clearRequest(request); return; }
      if (!this.active || !this.reminderWindowReady || this.reminderRequest !== request) { return; }
      this.lastAutomaticReminderRequest = request;`],
  ['!CollectionReminderService.claim(reminderRequest)', '!await CollectionReminderService.claim(reminderRequest)'],
  ["      AppStorage.setOrCreate('collectionReminderActiveBatch', true);", "      if (!this.active || (automatic && !this.reminderWindowReady)) { return; }\n      AppStorage.setOrCreate('collectionReminderActiveBatch', true);"],
  ['  aboutToDisappear(): void {', '  aboutToDisappear(): void {\n    this.batchCancellation?.cancel();'],
  ["this.runningTaskId === task.id ? '执行中' : '运行'", "this.runningTaskId === task.id ? '停止' : '运行'"],
  ['.borderRadius(20).enabled(!this.isTaskExecutionBusy())\n          .onClick((): void => { this.runTask(task); })',
    '.borderRadius(20).enabled(this.runningTaskId === task.id || !this.isTaskExecutionBusy())\n' +
    '          .onClick((): void => { if (this.runningTaskId === task.id) { this.batchCancellation?.cancel(); } else { this.runTask(task); } })'],
  ['      if (this.batchRunStatusText.length > 0) {', `      if (this.androidCollectedResultsReady && !this.batchRunning) {
        Button(this.batchSyncing ? '正在打开同步…' : '同步采集结果').height(40)
          .enabled(!this.batchSyncing && !this.isTaskExecutionBusy()).onClick(async (): Promise<void> => {
            if (this.batchSyncing || this.isTaskExecutionBusy()) { return; }
            this.batchSyncing = true;
            try { await this.onSyncCollectedTasks(); }
            catch (error) { showToastSafely((error as Error).message, 2200); }
            finally { this.batchSyncing = false; }
          })
      }
      if (this.batchRunStatusText.length > 0) {`],
  ['  @Builder SimpleTaskRow(task: WorkflowTask) {', `  private androidTaskService(task: WorkflowTask): string {
    try {
      const raw = WorkflowStorage.readTaskWorkflowJson(this.filesDir, task);
      return raw.length <= 256000 ? taskServiceLabel(WorkflowStorage.validateWorkflowJson(raw).steps) : '检查任务配置';
    } catch (error) { return '检查任务配置'; }
  }

  @Builder SimpleTaskRow(task: WorkflowTask) {`],
  ["      Text((task.enabled ? task.lastStatus : '已暂停') + ' · ' + task.lastRun)", `      Text(this.androidTaskService(task)).fontSize(12).fontColor($r('app.color.ui_text_secondary')).width('100%')
      Text((task.enabled ? task.lastStatus : '已暂停') + ' · ' + task.lastRun)`],
  ['  @Builder ToolEditor() {', `  private async pickAndroidWorkflowImage(): Promise<void> {
    let selected = this.selectedPath; let task = this.editingTaskId;
    try {
      let path = await WorkflowVlm.pickImage(this.filesDir, (): boolean => !this.editorOpen || this.selectedPath !== selected || this.editingTaskId !== task);
      if (path.length > 0 && this.editorOpen && this.selectedPath === selected && this.editingTaskId === task) { this.updateSelectedToolInput('image', path); }
    } catch (error) { showToastSafely((error as Error).message); }
  }
  @Builder ToolEditor() {`],
  ["        this.FieldLabel('图片变量')", `        this.FieldLabel('图片引用（Android 使用本机图片）')
        Button('选择本机图片').fontSize(12).onClick(() => { this.pickAndroidWorkflowImage(); })
        Text('使用已保存的 Decider 视觉模型；每次发送前确认。').fontSize(11).fontColor(AppTheme.text('#6F7584'))`],
  ['  private fileViewerContainsAiGeneratedContent(): boolean {',
    '  private fileViewerContainsAiGeneratedContent(): boolean {\n    if (/"source_kind"\\s*:\\s*"android_workflow"/.test(this.fileViewerText)) { return false; }'],
  ['  private pageScroller: Scroller = new Scroller();', `  @StorageProp('androidWorkflowBackRequested') @Watch('handleAndroidBack') private androidBackRequest: number = 0;
  private pageScroller: Scroller = new Scroller();
  private handleAndroidBack(): void { if (this.active && this.configViewOpen) { this.closeConfigView(); } }
  private requestCloseEditor(): void {
    promptAction.showDialog({ title: '放弃当前编辑？', message: '未保存的修改将丢失。', buttons: [
      { text: '继续编辑', color: '#65718B' }, { text: '放弃修改', color: '#B42318' }
    ] }).then((result: promptAction.ShowDialogSuccessResponse): void => { if (result.index === 1) { this.editorOpen = false; } }).catch(() => {});
  }`],
  ['  private closeConfigView(): void {', '  private closeConfigView(): void {\n    if (this.editorOpen) { this.requestCloseEditor(); return; }'],
  ['.onClick(() => { this.editorOpen = false; })', '.onClick(() => { this.requestCloseEditor(); })']
]);
taskPage = applyPatches(taskPage, [
  ['  private enabledTasks(): WorkflowTask[] {', `  private reviewTasks(): WorkflowTask[] {
    // Android's extra public-notes workflow remains available in the configuration list.
    return this.tasks.filter((task: WorkflowTask): boolean => task.id !== 'default_xhs_public_notes');
  }

  private reviewTasksForScene(sceneId: number): WorkflowTask[] {
    return this.reviewTasks().filter((task: WorkflowTask): boolean => task.sceneId === sceneId);
  }

  private enabledTasks(): WorkflowTask[] {`],
  ['this.tasksForScene(scenes[sceneIndex].sceneId)', 'this.reviewTasksForScene(scenes[sceneIndex].sceneId)'],
  [/  private enabledTaskCount\(\): number \{[^]*?\n  \}/,
    '  private enabledTaskCount(): number { return this.enabledTasks().length; }'],
  ['this.tasksForScene(scene.sceneId)', 'this.reviewTasksForScene(scene.sceneId)', { all: true }],
  ['    if (this.tasks.length === 0) {', '    if (this.reviewTasks().length === 0) {'],
  ["this.enabledTaskCount() + ' / ' + this.tasks.length", "this.enabledTaskCount() + ' / ' + this.reviewTasks().length"]
]);
overlay('pages/task/TaskPage.ets', patchCollectionTaskPage(taskPage));
writeJson('entry/src/main/resources/base/profile/main_pages.json', { src: [
  'pages/Index', 'pages/gallery/GalleryPage', 'pages/collection/CollectionGroupDetailPage',
  'pages/collection/PeopleInfoPage', 'pages/collection/PersonInfoPage', 'pages/settings/AvatarManagementPage',
  'pages/task/WorkflowFileViewer', 'pages/settings/DesktopCompanionPage', 'pages/settings/SelfHdcTestPage',
  'pages/LogView', 'pages/OpTest', 'pages/ProfileTest', 'pages/settings/MemorySyncPage', 'pages/settings/CalendarSourcesPage', 'pages/settings/MailSourcesPage', 'pages/meeting/MeetingPage', 'pages/task/GuiPlanPage' ] });
fs.copyFileSync(path.join(template, 'entry/src/main/resources/base/media/huawei_account_white.svg'),
  path.join(project, 'entry/src/main/resources/base/media/huawei_account_white.svg'));
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
