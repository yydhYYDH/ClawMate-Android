import { common } from '@kit.AbilityKit';
import Bridge from '@arkui-x.bridge';
import { HomePage } from '../pages/home/HomePage';
import { HomeComfortChatStore } from '../pages/home/HomeComfortChatStore';
import { ChatPage } from '../pages/chat/ChatPage';
import { CHAT_MODE_CLOUD_AGENT } from '../pages/chat/ChatPageTypes';
import { ChatMessage, ChatSessionSummary } from '../pages/chat/ChatSessionTypes';
import { CloudModelClient, CloudChatCompletionOptions } from '../utils/CloudModelClient';
import { ChatSessionStorage } from '../utils/ChatSessionStorage';
import { PrivacyConsent } from '../utils/PrivacyConsent';
import { HUAWEI_ACCOUNT_SESSION_KEY } from '../utils/AiServiceAccess';
import { homeRecommendationGridHeight } from '../pages/home/HomeRecommendationLayout';

interface ProbeConfiguration {
  fixtureBaseUrl: string;
}

@Entry
@Component
struct Index {
  @State page: string = 'checks';
  @State results: string[] = [];
  @State running: boolean = false;
  @State fixtureBaseUrl: string = '';
  @State status: string = '仅验证原始源码复用；真实登录、模型和自动化未接入';
  @State comfortStore: HomeComfortChatStore = new HomeComfortChatStore();
  @State mode: string = CHAT_MODE_CLOUD_AGENT;
  @State cloudInput: string = '';
  @State localInput: string = '';
  @State deepThinking: boolean = false;
  @State thinking: boolean = false;
  @State messages: ChatMessage[] = [];
  @State sessions: ChatSessionSummary[] = [];
  @State sessionId: string = '';
  private client: CloudModelClient = new CloudModelClient();
  private bridge: Bridge.BridgeObject = Bridge.createBridge('ClawMateProbe');

  aboutToAppear(): void {
    AppStorage.setOrCreate(HUAWEI_ACCOUNT_SESSION_KEY, false);
    AppStorage.setOrCreate('debugEnabled', false);
    this.initialize().catch((error: Error) => { this.status = '初始化失败：' + error.message; });
  }

  private context(): common.UIAbilityContext {
    return getContext(this) as common.UIAbilityContext;
  }

  private async initialize(): Promise<void> {
    await PrivacyConsent.initialize(this.context());
    this.restore();
    let raw = await this.bridge.callMethod('configuration') as string;
    let configuration = JSON.parse(raw) as ProbeConfiguration;
    // Synthetic login state is confined to loopback protocol fixtures.
    if (/^http:\/\/127\.0\.0\.1:[0-9]+$/.test(configuration.fixtureBaseUrl)) {
      this.fixtureBaseUrl = configuration.fixtureBaseUrl;
    }
  }

  private restore(): void {
    this.sessions = ChatSessionStorage.loadIndex(this.context().filesDir);
    if (this.sessions.length > 0) {
      let session = ChatSessionStorage.loadSession(this.context().filesDir, this.sessions[0].id);
      if (session !== null) {
        this.sessionId = session.id;
        this.messages = session.messages;
      }
    }
  }

  private record(name: string, passed: boolean, detail: string = ''): void {
    this.results = this.results.concat([(passed ? 'PASS ' : 'FAIL ') + name + (detail.length > 0 ? ': ' + detail : '')]);
  }

  private options(scenario: string): CloudChatCompletionOptions {
    return {
      baseUrl: this.fixtureBaseUrl + '/' + scenario,
      apiKey: '', model: 'LOCAL_PROTOCOL_FIXTURE',
      messages: [{ role: 'user', content: '隔离协议测试，无用户数据' }],
      readTimeout: scenario === 'timeout' ? 1000 : 10000
    };
  }

  private async outcome(task: Promise<string>, limit: number = 3500): Promise<string> {
    let timer: number = -1;
    let timeout = new Promise<string>((resolve) => {
      timer = setTimeout(() => resolve('PROBE_TIMEOUT'), limit);
    });
    let result = await Promise.race([task.then((value: string) => 'OK:' + value)
      .catch((error: Error) => 'ERROR:' + error.message), timeout]);
    if (timer >= 0) { clearTimeout(timer); }
    return result;
  }

  private makeMessage(role: 'user' | 'assistant', content: string): ChatMessage {
    return {
      id: 'probe-' + role + '-' + Date.now().toString(), role: role, kind: 'chat', content: content,
      thinkingContent: '', thinkingCollapsed: true, trace: [], collapsed: true, createdAt: Date.now()
    };
  }

  private async runChecks(): Promise<void> {
    if (this.running || this.fixtureBaseUrl.length === 0) {
      this.status = '本机回环协议夹具尚未就绪';
      return;
    }
    this.running = true;
    this.results = [];
    this.status = '测试数据和登录状态均为本地夹具，不代表真实模型或账号';
    try {
      let platform = await this.bridge.callMethod('platformInfo') as string;
      this.record('ArkTS→Android bridge', platform.startsWith('Android '), platform);
      let echo = await this.bridge.callMethod('echo', '跨平台🙂') as string;
      this.record('Android→ArkTS Unicode bridge', echo === '跨平台🙂');
      let clipboard = await this.bridge.callMethod('testClipboard') as boolean;
      this.record('Android clipboard adapter', clipboard);
      this.record('original home layout contract', homeRecommendationGridHeight(4) === 368);
      AppStorage.setOrCreate(HUAWEI_ACCOUNT_SESSION_KEY, false);
      let denied = await this.outcome(this.client.chatCompletionStream(this.options('success'))
        .then((result) => result.content));
      this.record('original login gate remains closed', denied.startsWith('ERROR:') && denied.indexOf('登录') >= 0);

      await PrivacyConsent.choose(true);
      AppStorage.setOrCreate(HUAWEI_ACCOUNT_SESSION_KEY, true);
      let deltas: string[] = [];
      let stream = await this.client.chatCompletionStream(this.options('success'), {
        onContentDelta: (delta: string): void => { deltas.push(delta); }
      });
      this.record('shared HTTP/SSE and split UTF-8', stream.content === '本地协议夹具：你好🙂' && deltas.length >= 2,
        'callbacks=' + deltas.length.toString());
      this.record('original streamed tool-call assembly', stream.toolCalls.length === 1 &&
        stream.toolCalls[0].function.name === 'fixture_lookup' &&
        stream.toolCalls[0].function.arguments === '{"query":"fixture"}');

      let session = ChatSessionStorage.createSession(this.context().filesDir, CHAT_MODE_CLOUD_AGENT);
      session.messages = [this.makeMessage('user', '本地协议夹具'), this.makeMessage('assistant', stream.content)];
      ChatSessionStorage.saveSession(this.context().filesDir, session);
      let saved = ChatSessionStorage.loadSession(this.context().filesDir, session.id);
      this.record('original file persistence round trip', saved !== null && saved.messages.length === 2 &&
        saved.messages[1].content === stream.content);
      this.restore();

      let httpError = await this.outcome(this.client.chatCompletionStream(this.options('error'))
        .then((result) => result.content));
      this.record('HTTP error is reported', httpError.startsWith('ERROR:') && httpError.indexOf('429') >= 0);

      let cancelTask = this.outcome(this.client.chatCompletionStream(this.options('cancel'))
        .then((result) => result.content));
      setTimeout(() => this.client.cancelActiveRequests(), 150);
      let cancelled = await cancelTask;
      this.record('client cancellation settles', cancelled.indexOf('请求已取消') >= 0, cancelled);

      let privacyTask = this.outcome(this.client.chatCompletionStream(this.options('cancel'))
        .then((result) => result.content));
      let revocation = new Promise<void>((resolve) => { setTimeout(resolve, 150); })
        .then(async (): Promise<void> => { await PrivacyConsent.choose(false); });
      let revoked = await privacyTask;
      this.record('privacy revocation settles', revoked.startsWith('ERROR:'), revoked);
      // The request rejects before choose(false) finishes its preference writes.
      await revocation;
      await PrivacyConsent.choose(true);
      let timedOut = await this.outcome(this.client.chatCompletionStream(this.options('timeout'))
        .then((result) => result.content));
      this.record('transport read timeout settles', timedOut.startsWith('ERROR:'), timedOut);
    } catch (error) {
      this.record('probe completed', false, (error as Error).message);
    } finally {
      this.client.cancelActiveRequests();
      await PrivacyConsent.choose(false).catch(() => {});
      AppStorage.setOrCreate(HUAWEI_ACCOUNT_SESSION_KEY, false);
      this.running = false;
      this.status = '夹具检查结束；真实登录和模型仍未验证';
      await this.bridge.callMethod('report', JSON.stringify({
        sourceCommit: 'cd69b68bf16b9ed352be5073939eb24d042de583',
        sdkApi: 20, results: this.results, storedMessages: this.messages.length
      }));
    }
  }

  private async sendFixture(): Promise<void> {
    if (this.fixtureBaseUrl.length === 0 || this.thinking) {
      this.status = '尚未配置本地协议夹具；真实模型未接入';
      return;
    }
    this.thinking = true;
    await PrivacyConsent.choose(true);
    AppStorage.setOrCreate(HUAWEI_ACCOUNT_SESSION_KEY, true);
    let user = this.makeMessage('user', this.cloudInput);
    let assistant = this.makeMessage('assistant', '');
    assistant.streamingActive = true;
    this.messages = this.messages.concat([user, assistant]);
    this.cloudInput = '';
    try {
      await this.client.chatCompletionStream(this.options('success'), {
        onContentDelta: (delta: string): void => {
          assistant.content += delta;
          assistant.streamingVersion = (assistant.streamingVersion || 0) + 1;
          this.messages = this.messages.slice();
        }
      });
    } catch (error) { this.status = '夹具请求失败：' + (error as Error).message; }
    finally {
      assistant.streamingActive = false;
      this.messages = this.messages.slice();
      this.thinking = false;
      AppStorage.setOrCreate(HUAWEI_ACCOUNT_SESSION_KEY, false);
      await PrivacyConsent.choose(false).catch(() => {});
    }
  }

  build() {
    Column() {
      Row({ space: 6 }) {
        Button('验证').onClick(() => { this.page = 'checks'; })
        Button('原首页').onClick(() => { this.page = 'home'; })
        Button('原聊天').onClick(() => { this.page = 'chat'; })
      }.padding(8).width('100%').justifyContent(FlexAlign.Center)
      if (this.page === 'home') {
        HomePage({
          comfortChatStore: this.comfortStore,
          onGenerateAvatar: async (): Promise<void> => { throw new Error('本验证未接入分身生成'); },
          onExecuteAgentTask: async (): Promise<void> => { throw new Error('本验证未接入任务执行'); },
          onRefreshTaskSuggestions: async (): Promise<void> => { throw new Error('本验证未接入任务推荐'); },
          onRefreshComfortConcerns: async (): Promise<void> => { throw new Error('本验证未接入关怀生成'); }
        }).layoutWeight(1)
      } else if (this.page === 'chat') {
        ChatPage({
          chatMode: $mode, cloudUserInput: $cloudInput, userInput: $localInput,
          chatDeepThinkingEnabled: $deepThinking, messages: this.messages,
          sessions: this.sessions, activeSessionId: this.sessionId, cloudThinking: this.thinking,
          onRunCloudPlanner: async (): Promise<void> => { await this.sendFixture(); },
          onCancelCloudChat: (): void => { this.client.cancelActiveRequests(); }
        }).layoutWeight(1)
      } else {
        Scroll() {
          Column({ space: 12 }) {
            Text('ClawMate ArkUI-X 复用验证').fontSize(21).fontWeight(FontWeight.Bold)
            Text(this.status).fontSize(13)
            Text('读取到的持久化消息：' + this.messages.length.toString()).id('stored-count')
            Button(this.running ? '检查中' : '运行本地夹具检查')
              .enabled(!this.running).onClick(async () => { await this.runChecks(); })
            ForEach(this.results, (result: string) => {
              Text(result).fontSize(12).fontColor(result.startsWith('PASS') ? '#18743E' : '#B42318')
                .width('100%').copyOption(CopyOptions.InApp)
            }, (result: string) => result)
          }.padding(16).width('100%')
        }.layoutWeight(1)
      }
    }.width('100%').height('100%').backgroundColor('#F4F8FF')
  }
}
