// Android adaptation of pages/codex/CodexClient.ets.
//
// The HarmonyOS implementation used the RCP session for every request. On Android the
// request itself must be performed by the native side, because only Kotlin can supply a
// trust store scoped to one paired connection and a cancellation model that survives the
// ArkTS page being rebuilt. Everything the UI observes — validation, retry policy, status
// classification and the envelope shape — stays here so the page behaviour is unchanged.
//
// Native methods (see CodexPlatformBridge.kt):
//   codexRequest(json) -> json   performs one HTTP exchange, no redirects, no auto-retry
//   codexCancel(id)              cancels the in-flight request with that id
//   codexConnectionSave/list/load/forget  encrypted credential storage

import Bridge from '@arkui-x.bridge';
import { codexResponseText } from './CodexResponseText';
import {
  CodexConnection, CodexEnvelope, CodexCreateBody, CodexTurnBody, CodexUploadBody, CodexApprovalBody,
  normalizeCodexOrigin, validCodexToken, validCodexCa, CodexPairBody, CodexPairingBundle, validateCodexPairing,
  CodexForkBody, CodexResumeBody
} from './CodexTypes';

/** Wire shape of one native request. */
interface CodexNativeRequest {  id: string;
  origin: string;
  method: string;
  route: string;
  body?: string;
  authenticated: boolean;
  caCertificate: string;
  token: string;
  timeoutMs: number;
  expectGzip: boolean;
}

/** Wire shape of the native answer. */
interface CodexNativeResponse {
  status: number;
  text: string;
  gzipUnsupported: boolean;
  failure: string;
  transportFailure: boolean;
}

/** Ticket returned when the native side starts an exchange on a coroutine. */
interface CodexTicket { ticket: string; }

/** A poll of a pending exchange: either a marker or the finished reply. */
interface CodexPolledReply {
  status?: string;
  failure?: string;
  text?: string;
  gzipUnsupported?: boolean;
  transportFailure?: boolean;
}

// How long one exchange may take, and how often to ask. The native side keeps its own
// timeouts; these only bound how long the page keeps polling for the answer.
const EXCHANGE_DEADLINE_MS: number = 60000;
const EXCHANGE_POLL_MS: number = 120;

export class CodexRequestFailure extends Error {
  retryable: boolean;
  transportFailure: boolean;
  constructor(message: string, retryable: boolean = false, transportFailure: boolean = false) {
    super(message); this.retryable = retryable; this.transportFailure = transportFailure;
  }
}

/** Status shared with the UI so a failure can be classified without parsing messages. */
export const CODEX_AUTH_FAILURE: string = 'auth';

export class CodexClient {
  private origin: string = '';
  private token: string = '';
  private caCertificate: string = '';
  private generation: number = 0;
  private pending: string[] = [];
  private updatePending: string[] = [];
  private compressionEnabled: boolean = true;
  private chunkBytes: number = 1024 * 1024;
  private sequence: number = 0;
  onReachability: (reachable: boolean, message: string) => void = () => {};

  /**
   * Performs one exchange through the native side without blocking its thread.
   *
   * A long poll can occupy the transport for tens of seconds, and holding the bridge thread
   * that long is reported as an ANR. The native side therefore starts the exchange and
   * returns a ticket, and this polls until the answer is ready.
   */
  private static async exchangeOnce(payload: string): Promise<string> {
    const started = JSON.parse(
      await CodexClient.bridge().callMethod('codexRequestStart', payload) as string) as CodexTicket;
    if (started === null || !started.ticket) {
      // The native side answered immediately (a validation failure with no exchange).
      return JSON.stringify({ status: 0, text: '', gzipUnsupported: false,
        failure: '无法开始请求，请重新连接', transportFailure: false });
    }
    const deadline = Date.now() + EXCHANGE_DEADLINE_MS;
    while (Date.now() < deadline) {
      const polled = JSON.parse(
        await CodexClient.bridge().callMethod('codexRequestPoll', started.ticket) as string) as CodexPolledReply;
      if (polled !== null && polled.status === 'pending') {
        await new Promise<void>((resolve): void => { setTimeout(resolve, EXCHANGE_POLL_MS); });
        continue;
      }
      if (polled !== null && polled.status === 'expired') {
        throw new CodexRequestFailure('请求超时，请同步会话后重试', true, true);
      }
      // Anything that is not a pending/expired marker is the exchange result itself.
      return JSON.stringify(polled);
    }
    await CodexClient.bridge().callMethod('codexRequestCancel', started.ticket);
    throw new CodexRequestFailure('请求超时，请同步会话后重试', true, true);
  }

  private static bridge(): ESObject { return Bridge.createBridge('ClawMatePlatform'); }

  setArtifactChunkBytes(bytes: number): void {
    this.chunkBytes = Number.isSafeInteger(bytes) && bytes >= 16384 && bytes <= 1048576 ? bytes : 1048576;
  }
  artifactChunkBytes(): number { return this.chunkBytes; }

  configure(connection: CodexConnection): void {
    this.close();
    this.origin = normalizeCodexOrigin(connection.origin);
    if (!validCodexToken(connection.token)) { throw new Error('桥接令牌格式无效，请使用服务器生成的 token'); }
    this.token = connection.token;
    if (connection.caCertificate && !validCodexCa(connection.caCertificate)) { throw new Error('配对证书格式无效'); }
    this.caCertificate = connection.caCertificate || '';
    this.generation++;
  }

  close(): void {
    this.generation++;
    this.cancelPending(this.pending);
    this.cancelPending(this.updatePending);
    this.pending = []; this.updatePending = [];
    this.token = ''; this.origin = ''; this.caCertificate = '';
    this.chunkBytes = 1024 * 1024; this.compressionEnabled = true;
  }

  private cancelPending(ids: string[]): void {
    const bridge: ESObject = CodexClient.bridge();
    ids.forEach((id: string): void => { bridge.callMethod('codexCancel', id).catch((): void => {}); });
  }

  cancelTaskUpdates(): void {
    this.cancelPending(this.updatePending); this.updatePending = [];
  }

  async pair(bundle: CodexPairingBundle): Promise<CodexConnection> {
    validateCodexPairing(bundle);
    this.configure({ origin: bundle.origin, token: bundle.code, caCertificate: bundle.caCertificate });
    const result: CodexEnvelope = await this.request('/v1/pair',
      { code: bundle.code, deviceName: '我的安卓手机' }, false);
    if (!result.token || !validCodexToken(result.token) || result.serverId !== bundle.serverId) {
      this.close(); throw new Error('配对响应不匹配，请重新生成配对信息');
    }
    const connection: CodexConnection = { origin: bundle.origin, token: result.token, caCertificate: bundle.caCertificate };
    this.configure(connection); return connection;
  }

  async call(route: string,
    body?: CodexCreateBody | CodexTurnBody | CodexUploadBody | CodexApprovalBody | CodexForkBody | CodexResumeBody
  ): Promise<CodexEnvelope> {
    return this.request(route, body, true);
  }

  private async request(route: string,
    body: CodexCreateBody | CodexTurnBody | CodexUploadBody | CodexApprovalBody | CodexPairBody | CodexForkBody | CodexResumeBody | undefined,
    authenticated: boolean): Promise<CodexEnvelope> {
    const generation = this.generation;
    for (let attempt: number = 0; ; attempt++) {
      try { return await this.exchange(route, body, authenticated); }
      catch (e) {
        const failure = e as CodexRequestFailure;
        // Only idempotent reads may retry. Never resend a task, upload, pairing, stop or
        // approval after an ambiguous transport failure.
        if (body !== undefined || route === '/v1/health' || route.includes('waitMs=') || attempt >= 1 ||
          !failure.retryable || generation !== this.generation) { throw failure; }
        await new Promise<void>((resolve): void => { setTimeout(resolve, 600 + Math.floor(Math.random() * 600)); });
        if (generation !== this.generation) { throw new Error('连接已更换'); }
      }
    }
  }

  private async exchange(route: string,
    body: CodexCreateBody | CodexTurnBody | CodexUploadBody | CodexApprovalBody | CodexPairBody | CodexForkBody | CodexResumeBody | undefined,
    authenticated: boolean): Promise<CodexEnvelope> {
    if (!this.origin || !this.token) { throw new Error('请先连接 Codex 服务器'); }
    const generation = this.generation;
    const id = 'codex-' + (++this.sequence).toString() + '-' + Date.now().toString();
    this.pending.push(id);
    const isUpdate = route.includes('waitMs=');
    if (isUpdate) { this.updatePending.push(id); }
    try {
      const payload: CodexNativeRequest = {
        id: id, origin: this.origin, method: body === undefined ? 'GET' : 'POST', route: route,
        body: body === undefined ? undefined : JSON.stringify(body), authenticated: authenticated,
        caCertificate: this.caCertificate, token: this.token,
        // The platform bridge call itself is capped (~60 s), so a single exchange must ask
        // for less than that or the bridge times out before the request does. Long polls use
        // waitMs=12000; a follow-up exchange gets the remaining headroom.
        timeoutMs: route === '/v1/health' ? 15000 : 45000,
        expectGzip: this.compressionEnabled
      };
      const raw = await CodexClient.exchangeOnce(JSON.stringify(payload));
      if (generation !== this.generation) { throw new Error('连接已更换'); }
      const response = JSON.parse(raw) as CodexNativeResponse;
      if (response.failure && response.failure.length > 0) {
        if (response.transportFailure) { this.onReachability(false, response.failure); }
        throw new CodexRequestFailure(response.failure, true, response.transportFailure);
      }
      if (response.gzipUnsupported && this.compressionEnabled) {
        this.compressionEnabled = false;
        throw new CodexRequestFailure('压缩响应未能解析，将改用普通传输，请同步会话后重试', true);
      }
      if (response.status !== 401) { this.onReachability(true, ''); }
      if (response.status >= 300 && response.status < 400) {
        throw new Error('服务器重定向已拒绝，请填写最终 HTTPS 地址');
      }
      if (response.status === 401) {
        this.onReachability(false, '连接授权已失效，请重新配对');
        throw new CodexRequestFailure('令牌无效或已撤销，请在连接设置中更新', false, false);
      }
      if (response.status === 429) { throw new Error('请求过于频繁，请稍后重试'); }
      const text = codexResponseText(undefined, response.text);
      const envelope = JSON.parse(text) as CodexEnvelope;
      if (response.status < 200 || response.status >= 300) {
        throw new CodexRequestFailure(envelope.error || '服务器请求失败',
          response.status === 502 || response.status === 504, false);
      }
      return envelope;
    } finally {
      this.pending = this.pending.filter((item: string): boolean => item !== id);
      this.updatePending = this.updatePending.filter((item: string): boolean => item !== id);
    }
  }
}
