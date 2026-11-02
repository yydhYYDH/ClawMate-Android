import Bridge from '@arkui-x.bridge';

export interface AndroidRuntimeSnapshot {
  v: number;
  dark: boolean;
  keyboardHeight: number;
  ttsEngineState: string;
  voiceStatus: string;
  voiceMessageId: string;
  voiceError: string;
  asrState: string;
  asrText: string;
  asrError: string;
  asrVersion: number;
  reminderVersion: number;
  reminderRequest: string;
  reminderReady: boolean;
  windowReady: boolean;
}
interface OcrSnapshot { v: number; status: string; text: string; error: string; }

export class NativeCapabilities {
  static async runtime(): Promise<AndroidRuntimeSnapshot> {
    let value = JSON.parse(await Bridge.createBridge('ClawMatePlatform').callMethod('runtimeSnapshot') as string) as AndroidRuntimeSnapshot;
    if (value.v !== 1) { throw new Error('安卓能力接口版本不兼容'); }
    return value;
  }
  static async initializeSpeech(): Promise<void> {
    await Bridge.createBridge('ClawMatePlatform').callMethod('ttsInitialize');
  }
  static async speak(id: string, text: string): Promise<void> {
    let accepted = await Bridge.createBridge('ClawMatePlatform').callMethod('ttsSpeak', id, text) as boolean;
    if (!accepted) { throw new Error('本机语音引擎没有接受朗读请求'); }
  }
  static async stopSpeech(): Promise<void> { await Bridge.createBridge('ClawMatePlatform').callMethod('ttsStop'); }
  static async wait(): Promise<void> { await new Promise<void>((resolve): void => { setTimeout(resolve, 120); }); }

  static async recognize(uri: string, active: (id: string) => void, cancelled: () => boolean): Promise<string> {
    let bridge = Bridge.createBridge('ClawMatePlatform');
    let id = await bridge.callMethod('ocrStart', uri) as string;
    active(id);
    let consumed = false;
    try {
      let deadline = Date.now() + 45000;
      while (Date.now() < deadline) {
        if (cancelled()) { throw new Error('本地识别已取消'); }
        let result = JSON.parse(await bridge.callMethod('ocrPoll', id) as string) as OcrSnapshot;
        if (result.v !== 1) { throw new Error('安卓识别接口版本不兼容'); }
        if (cancelled()) { throw new Error('本地识别已取消'); }
        if (result.status === 'done') { consumed = true; return result.text; }
        if (result.status !== 'running') { throw new Error(result.error || '本地识别未完成，请重试'); }
        await NativeCapabilities.wait();
      }
      throw new Error('本地识别超时，请重试或降低 OCR 并发数');
    } finally {
      if (!consumed) { await bridge.callMethod('ocrCancel', id); }
    }
  }
  static async cancelRecognition(id: string): Promise<void> {
    await Bridge.createBridge('ClawMatePlatform').callMethod('ocrCancel', id);
  }
}
