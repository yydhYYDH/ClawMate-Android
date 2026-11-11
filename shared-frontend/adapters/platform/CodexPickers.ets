// Android replacements for the HarmonyOS pickers used by the Codex page.
//
//  - ScanKit (QR scan)        -> native camera and album scanner
//  - CameraKit (cameraPicker) -> native ACTION_IMAGE_CAPTURE into the app cache
//  - MediaLibraryKit          -> native system photo picker
//
// All three are start/poll: a picker keeps the user in another activity for as long as they
// like, which is far longer than one bridge call may block. Waiting inline would time out at
// the bridge while the user is still choosing.
//
// Each returns an empty result when the user cancels, so callers keep their existing
// "user dismissed" behaviour instead of showing an error.

import Bridge from '@arkui-x.bridge';

function bridge(): ESObject { return Bridge.createBridge('ClawMatePlatform'); }

/** Wire shape returned by the picker poll methods. */
export interface CodexPickerResult {
  v: number;
  status: string;
  path?: string;
  value?: string;
  name?: string;
  error?: string;
}

const POLL_INTERVAL_MS: number = 250;
// Long enough for a slow camera start, short enough that a lost result does not hang the UI.
const PICKER_DEADLINE_MS: number = 10 * 60 * 1000;

async function pollUntilSettled(poll: () => Promise<string>): Promise<CodexPickerResult> {
  const deadline = Date.now() + PICKER_DEADLINE_MS;
  while (Date.now() < deadline) {
    const result = JSON.parse(await poll()) as CodexPickerResult;
    if (result === null || result.v !== 1) { throw new Error('选择器接口版本不兼容'); }
    if (result.status === 'permission') { throw new Error('请允许相机权限，或从相册选图'); }
    if (result.status === 'failed') { throw new Error(result.error || '无法读取照片，请重新拍摄或从相册选图'); }
    if (result.status !== 'running') { return result; }
    await new Promise<void>((resolve): void => { setTimeout(resolve, POLL_INTERVAL_MS); });
  }
  throw new Error('选择器等待超时，请关闭选择窗口后重试');
}

export class CodexPickers {
  /**
   * Opens the QR scanner.
   *
   * @return the decoded value, or an empty string when the user cancelled.
   */
  static async scanPairingCode(): Promise<string> {
    const native: ESObject = bridge();
    if (await native.callMethod('codexScanStart') !== true) { throw new Error('扫码窗口尚未结束'); }
    const result = await pollUntilSettled((): Promise<string> =>
      native.callMethod('codexScanResult') as Promise<string>);
    return result.value || '';
  }

  /**
   * Captures a photo into [cacheDir].
   *
   * @return the absolute file path, or an empty string when cancelled.
   */
  static async takePhoto(cacheDir: string): Promise<string> {
    const started = await bridge().callMethod('codexTakePhotoStart', cacheDir) as boolean;
    if (started !== true) { throw new Error('相机窗口尚未结束'); }
    const result = await pollUntilSettled((): Promise<string> =>
      bridge().callMethod('codexTakePhotoResult') as Promise<string>);
    return result.path || '';
  }

  static async pickDocument(kind: 'pairing' | 'image' | 'file'): Promise<CodexPickerResult> {
    const native: ESObject = bridge();
    if (await native.callMethod('codexDocumentStart', kind) !== true) {
      throw new Error('文件选择窗口尚未结束');
    }
    return await pollUntilSettled((): Promise<string> =>
      native.callMethod('codexDocumentResult') as Promise<string>);
  }
}
