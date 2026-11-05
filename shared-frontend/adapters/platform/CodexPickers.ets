// Android replacements for the HarmonyOS pickers used by the Codex page.
//
//  - ScanKit (QR scan)      -> native camera scanner; returns the decoded text
//  - CameraKit (cameraPicker) -> native ACTION_IMAGE_CAPTURE into the app cache
//  - MediaLibraryKit (PhotoViewPicker) -> native system photo picker
//
// Each returns an empty result when the user cancels, so callers keep their existing
// "user dismissed" behaviour instead of showing an error.

import Bridge from '@arkui-x.bridge';

function bridge(): ESObject { return Bridge.createBridge('ClawMatePlatform'); }

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
    const path = await bridge().callMethod('codexTakePhoto', cacheDir) as string;
    return path === null || path === undefined ? '' : path;
  }

  /**
   * Opens the system photo picker.
   *
   * @return a path readable by this app, or an empty string when cancelled.
   */
  static async pickImage(): Promise<string> {
    const path = await bridge().callMethod('codexPickImage') as string;
    return path === null || path === undefined ? '' : path;
  }
}
