import Bridge from '@arkui-x.bridge';
import fs from '@ohos.file.fs';
import { util } from '@kit.ArkTS';
import { MimoVoiceClient } from './UpstreamMimoVoice';
import { buildPcm16WavBytes, concatPcmChunks } from '../utils/voice/PcmWav';
import { ChatSessionStorage } from '../utils/ChatSessionStorage';
import { ChatMessage } from '../pages/chat/ChatSessionTypes';
import { ChatVoiceMetadata, VoiceInteractionStatus, TtsVoiceRuntimeStatus, createTtsVoiceRuntimeMetadata, createVoiceErrorMetadata } from '../pages/chat/VoiceInteractionTypes';
import { AiServiceAccess } from '../utils/AiServiceAccess';
import { PrivacyConsent } from '../utils/PrivacyConsent';

interface SpeechConfig { baseUrl: string; apiKey: string; model: string; voice: string; stylePrompt: string; stream: boolean; }
interface HttpResult { v: number; status: string; events: string[]; body: string; error: string; }
interface PlaybackResult { v: number; id: string; status: string; error: string; frames: number; }
export interface CloudSpeechHost {
  filesDir: () => string; sessionId: () => string; message: (id: string) => ChatMessage | undefined;
  update: (id: string, voice?: ChatVoiceMetadata) => void;
  state: (id: string, status: VoiceInteractionStatus) => void; authorize: () => Promise<void>;
}

/** Shared chat orchestration; Android provides HTTPS transport and audio sinks only. */
export class CloudSpeech {
  private host: CloudSpeechHost;
  private generation: number = 0;
  private request: string = '';
  private id: string = '';
  private status: VoiceInteractionStatus = 'idle';
  private uri: string = '';
  private voice: string = '';
  private playbackId: string = '';
  constructor(host: CloudSpeechHost) { this.host = host; }
  static configured(): boolean { return ['voiceTtsBaseUrl', 'voiceTtsApiKey', 'voiceTtsModel'].some((key: string): boolean => (AppStorage.get<string>(key) || '').trim().length > 0); }
  private config(): SpeechConfig {
    return { baseUrl: (AppStorage.get<string>('voiceTtsBaseUrl') || '').trim(), apiKey: AppStorage.get<string>('voiceTtsApiKey') || '',
      model: (AppStorage.get<string>('voiceTtsModel') || '').trim(), voice: (AppStorage.get<string>('voiceTtsVoice') || 'mimo_default').trim(),
      stylePrompt: AppStorage.get<string>('voiceTtsStylePrompt') || '', stream: AppStorage.get<boolean>('voiceTtsStreamingEnabled') === true };
  }
  ownsVoice(): boolean { return this.id.length > 0; }
  private emit(status: VoiceInteractionStatus): void { this.status = status; this.host.state(this.id, status); }
  async stop(): Promise<void> {
    this.generation++;
    let bridge = Bridge.createBridge('ClawMatePlatform');
    let request = this.request; let playback = this.playbackId; this.request = ''; this.playbackId = '';
    if (this.id.length > 0) {
      this.host.update(this.id, this.uri.length > 0 ? createTtsVoiceRuntimeMetadata('mimo', this.voice, this.uri, 'ready', Date.now()) : undefined);
      this.emit('idle');
    }
    this.id = ''; this.uri = '';
    if (request.length > 0) { await bridge.callMethod('speechRequestCancel', request); }
    if (playback.length > 0) { await bridge.callMethod('speechStop', playback); }
  }
  private active(generation: number, session: string, config?: SpeechConfig): void {
    if (generation !== this.generation || this.host.sessionId() !== session || !ChatSessionStorage.sessionExists(this.host.filesDir(), session)) { throw new Error('语音已取消或会话已变化'); }
    if (config !== undefined) {
      AiServiceAccess.requireAllowed(); PrivacyConsent.requireAccepted();
      if (JSON.stringify(config) !== JSON.stringify(this.config())) { throw new Error('语音服务配置已变化，请重新确认'); }
    }
  }
  private metadata(): void {
    let status: TtsVoiceRuntimeStatus = this.status === 'speaking' ? 'playing' : this.status === 'paused' ? 'paused' : this.status === 'synthesizing' ? 'synthesizing' : 'ready';
    this.host.update(this.id, createTtsVoiceRuntimeMetadata('mimo', this.voice, this.uri, status, Date.now()));
  }
  private async playback(generation: number, session: string, config?: SpeechConfig): Promise<boolean> {
    this.active(generation, session, config);
    let value = JSON.parse(await Bridge.createBridge('ClawMatePlatform').callMethod('speechPlaybackPoll') as string) as PlaybackResult;
    this.active(generation, session, config);
    if (value.v !== 1 || value.id !== this.playbackId) { throw new Error('语音输出已关闭或接口不兼容'); }
    if (value.status === 'error') { throw new Error(value.error || '语音播放失败'); }
    let status = value.status as VoiceInteractionStatus;
    if (status !== this.status) { this.emit(status); this.metadata(); }
    return status === 'idle';
  }
  private async wait(): Promise<void> { await new Promise<void>((resolve): void => { setTimeout(resolve, 60); }); }
  private save(session: string, id: string, bytes: Uint8Array): string {
    if (bytes.length < 44 || bytes.length > 8 * 1024 * 1024 + 44) { throw new Error('语音缓存为空或超出限制'); }
    if (bytes[0] !== 82 || bytes[1] !== 73 || bytes[2] !== 70 || bytes[3] !== 70 || bytes[8] !== 87 || bytes[9] !== 65 || bytes[10] !== 86 || bytes[11] !== 69) { throw new Error('服务未返回有效WAV音频'); }
    if (!ChatSessionStorage.sessionExists(this.host.filesDir(), session)) { throw new Error('会话已删除，语音未保存'); }
    ChatSessionStorage.ensureSessionAssetsDir(this.host.filesDir(), session);
    let path = ChatSessionStorage.sessionAssetPath(this.host.filesDir(), session, id + '-tts.wav');
    let temporary = path + '.tmp';
    try {
      let file = fs.openSync(temporary, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
      try { let exact = new Uint8Array(bytes.length); exact.set(bytes); fs.writeSync(file.fd, exact.buffer); } finally { fs.closeSync(file); }
      if (!ChatSessionStorage.sessionExists(this.host.filesDir(), session)) { throw new Error('会话已删除，语音未保存'); }
      fs.renameSync(temporary, path); return 'file://' + path;
    } finally { if (ChatSessionStorage.pathExists(temporary)) { fs.unlinkSync(temporary); } }
  }
  async play(id: string, text: string, replay: boolean, nonStreamingFallback: boolean = false): Promise<void> {
    let bridge = Bridge.createBridge('ClawMatePlatform');
    if (!nonStreamingFallback && !replay && this.id === id && (this.status === 'speaking' || this.status === 'paused')) {
      let paused = this.status === 'speaking'; await bridge.callMethod('speechPlaybackPause', paused); this.emit(paused ? 'paused' : 'speaking'); this.metadata(); return;
    }
    if (!nonStreamingFallback && !replay && this.id === id && this.status === 'synthesizing') { await this.stop(); return; }
    let cached = this.host.message(id)?.voice;
    if (replay && (!cached || cached.audioUri.length === 0)) { throw new Error('语音缓存尚未准备好'); }
    let stopping = this.stop(); let generation = this.generation; let session = this.host.sessionId(); await stopping;
    if (generation !== this.generation || session !== this.host.sessionId()) { return; }
    if (!/^[A-Za-z0-9_-]{1,160}$/.test(session) || !/^[A-Za-z0-9_-]{1,160}$/.test(id) || text.trim().length === 0 || text.length > 20000) { throw new Error('语音文本或会话编号无效'); }
    this.id = id; this.uri = ''; this.voice = cached?.voice || 'mimo_default'; this.emit('synthesizing');
    let playback = 'speech-' + String(Date.now()) + '-' + String(generation); this.playbackId = playback;
    let config: SpeechConfig | undefined = undefined;
    let audioReceived = false;
    try {
      if (cached && cached.provider === 'mimo' && cached.audioUri.length > 0) {
        let expected = 'file://' + ChatSessionStorage.sessionAssetPath(this.host.filesDir(), session, id + '-tts.wav');
        if (cached.audioUri !== expected) { throw new Error('语音缓存不属于当前会话，请重新合成'); }
        this.uri = cached.audioUri; await bridge.callMethod('speechFilePlay', playback, this.uri);
      } else {
        config = this.config();
        if (!/^https:\/\/[^/?#@\s]+(?:\/[^?#\s]*)?$/.test(config.baseUrl) || config.apiKey.trim().length === 0 || config.model.length === 0) { throw new Error('请配置HTTPS语音服务、API Key和语音模型'); }
        await this.host.authorize(); this.active(generation, session, config); this.voice = config.voice;
        this.metadata();
        let stream = config.stream && !nonStreamingFallback;
        let request = stream ? MimoVoiceClient.buildStreamingTtsRequest(text.trim(), { model: config.model, selectedVoice: config.voice, stylePrompt: config.stylePrompt }) :
          MimoVoiceClient.buildTtsRequest(text.trim(), { model: config.model, voice: config.voice, format: 'wav', stylePrompt: config.stylePrompt, stream: false });
        this.voice = request.audio.voice;
        if (stream) { await bridge.callMethod('speechPcmStart', playback); this.active(generation, session, config); }
        let requestId = await bridge.callMethod('speechRequestStart', MimoVoiceClient.normalizeChatUrl(config.baseUrl), config.apiKey, JSON.stringify(request), stream) as string;
        if (generation !== this.generation) { await bridge.callMethod('speechRequestCancel', requestId); await bridge.callMethod('speechStop', playback); return; }
        this.request = requestId;
        let chunks: Uint8Array[] = []; let total = 0; let pendingByte = -1; let done = false;
        let deadline = Date.now() + 125000;
        while (Date.now() < deadline && !done) {
          this.active(generation, session, config);
          let result = JSON.parse(await bridge.callMethod('speechRequestPoll', requestId) as string) as HttpResult;
          this.active(generation, session, config);
          if (result.v !== 1 || result.status === 'failed' || result.status === 'missing') { throw new Error(result.error || '语音网络已关闭'); }
          if (stream) {
            for (let event of result.events) {
              let delta = MimoVoiceClient.parseTtsStreamDeltaEvent(event.split('\n'));
              if (delta.done) { done = true; break; }
              if (delta.audioBase64.length === 0) { continue; }
              let bytes = new util.Base64Helper().decodeSync(delta.audioBase64); total += bytes.length;
              if (bytes.length > 0) { audioReceived = true; }
              if (total > 8 * 1024 * 1024) { throw new Error('语音过长，请缩短文本'); }
              chunks.push(bytes);
              let aligned = new Uint8Array(bytes.length + (pendingByte >= 0 ? 1 : 0));
              if (pendingByte >= 0) { aligned[0] = pendingByte; aligned.set(bytes, 1); } else { aligned.set(bytes); }
              pendingByte = aligned.length % 2 === 1 ? aligned[aligned.length - 1] : -1;
              let length = aligned.length - (pendingByte >= 0 ? 1 : 0);
              for (let offset = 0; offset < length; offset += 32768) {
                let part = aligned.slice(offset, Math.min(offset + 32768, length));
                let encoded = new util.Base64Helper().encodeToStringSync(part);
                while (!await bridge.callMethod('speechPcmAppend', playback, encoded) as boolean) {
                  await this.playback(generation, session, config); await this.wait();
                }
                this.active(generation, session, config);
              }
            }
            await this.playback(generation, session, config);
            if (result.status === 'done' && !done) { throw new Error('语音流未完整结束，请重试'); }
          } else if (result.status === 'done') {
            let base64 = MimoVoiceClient.parseTtsAudioBase64(result.body);
            let bytes = new util.Base64Helper().decodeSync(base64); this.uri = this.save(session, id, bytes); done = true;
          }
          if (!done) { await this.wait(); }
        }
        if (!done) { throw new Error('语音请求超时'); }
        this.active(generation, session, config);
        await bridge.callMethod('speechRequestCancel', requestId); this.request = '';
        if (stream) {
          if (total === 0 || pendingByte >= 0) { throw new Error('服务没有返回完整PCM音频，请检查语音模型配置'); }
          this.uri = this.save(session, id, buildPcm16WavBytes(concatPcmChunks(chunks), { sampleRate: 24000, channels: 1 }));
          await bridge.callMethod('speechPcmFinish', playback);
        } else { await bridge.callMethod('speechFilePlay', playback, this.uri); }
        this.metadata();
      }
      let deadline = Date.now() + 240000;
      while (Date.now() < deadline) { if (await this.playback(generation, session, config)) { this.metadata(); return; } await this.wait(); }
      throw new Error('语音播放等待超时');
    } catch (error) {
      if (generation === this.generation) {
        if (this.request.length > 0) { await bridge.callMethod('speechRequestCancel', this.request); this.request = ''; }
        await bridge.callMethod('speechStop', playback);
        if (session !== this.host.sessionId() || !ChatSessionStorage.sessionExists(this.host.filesDir(), session)) { this.id = ''; this.uri = ''; return; }
        if (config !== undefined && config.stream && !nonStreamingFallback && !audioReceived) {
          let permitted = false;
          try { this.active(generation, session, config); permitted = true; } catch (denied) {}
          if (permitted) { await this.play(id, text, false, true); return; }
        }
        this.emit('error'); let metadata = createVoiceErrorMetadata('tts', 'mimo', (error as Error).message, Date.now());
        this.uri = ''; metadata.voice = this.voice; this.host.update(id, metadata); throw error as Error;
      } else { await bridge.callMethod('speechStop', playback); }
    }
  }
}
