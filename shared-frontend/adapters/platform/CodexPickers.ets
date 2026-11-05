// Android replacements for the HarmonyOS pickers used by the Codex page.
//
//  - ScanKit (QR scan)        -> native camera scanner; unavailable on this platform
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
interface PickerResult {
  v: number;
  status: string;
  path?: string;
}

const POLL_INTERVAL_MS: number = 250;
// Long enough for a slow camera start, short enough that a lost result does not hang the UI.
const PICKER_DEADLINE_MS: number = 10 * 60 * 1000;

async function pollUntilSettled(poll: () => Promise<string>): Promise<string> {
  const deadline = Date.now() + PICKER_DEADLINE_MS;
  while (Date.now() < deadline) {
    const result = JSON.parse(await poll()) as PickerResult;
    if (result === null || result.v !== 1) { throw new Error('选择器接口版本不兼容'); }
    if (result.status === 'done') { return result.path ? result.path : ''; }
    if (result.status !== 'running') { return ''; }
    await new Promise<void>((resolve): void => { setTimeout(resolve, POLL_INTERVAL_MS); });
  }
  return '';
}

export class CodexPickers {
  /**
   * Opens the QR scanner.
   *
   * @return the decoded value, or an empty string when the user cancelled.
   */
  static async scanPairingCode(): Promise<string> {
    const value = await bridge().callMethod('codexScanPairingCode') as string;
    return value === null || value === undefined ? '' : value;
  }

  /**
   * Captures a photo into [cacheDir].
   *
   * @return the absolute file path, or an empty string when cancelled.
   */
  static async takePhoto(cacheDir: string): Promise<string> {
    const started = await bridge().callMethod('codexTakePhotoStart', cacheDir) as boolean;
    if (started !== true) { return ''; }
    return await pollUntilSettled((): Promise<string> =>
      bridge().callMethod('codexTakePhotoResult') as Promise<string>);
  }

  /**
   * Opens the system photo picker.
   *
   * @return a readable path, or an empty string when cancelled.
   */
  static async pickImage(): Promise<string> {
    const started = await bridge().callMethod('codexPickImageStart') as boolean;
    if (started !== true) { return ''; }
    return await pollUntilSettled((): Promise<string> =>
      bridge().callMethod('codexPickImageResult') as Promise<string>);
  }
}
