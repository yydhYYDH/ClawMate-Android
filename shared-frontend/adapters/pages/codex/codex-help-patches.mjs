// Android adaptation of pages/codex/CodexHelpPage.ets.
//
// The page is reached by router.pushUrl, not by import, so it is neither in the upstream
// project nor discovered by the dependency walk; it lives here and is staged explicitly.
//
// Two HarmonyOS APIs on it are rejected by ArkUI-X as "can't support crossplatform":
//   - context.openLink(url)      -> the existing native bridge method `openLink`
//   - @kit.BasicServicesKit pasteboard -> the existing platform/Pasteboard adapter
//
// `openLink` must go through the native side: ArkUI-X has no equivalent of the HarmonyOS
// "open this URL in the system browser" context call.
import { replaceChecked } from '../../../source-patches.mjs';

/**
 * @param {string} source the staged upstream page
 * @return {string} the page with platform-safe link opening and clipboard access
 */
export function adaptCodexHelpPage(source) {
  let out = source;

  // 1. Clipboard goes through the adapter that already backs the rest of the app.
  out = replaceChecked(out,
    "import { pasteboard } from '@kit.BasicServicesKit';",
    "import { pasteboard } from '../../platform/Pasteboard';");

  // 2. context.openLink is unavailable; ask the native side to start the intent.
  out = replaceChecked(out,
    `      const context = getContext(this) as common.UIAbilityContext;
      await context.openLink(url);`,
    `      const bridge: ESObject = Bridge.createBridge('ClawMatePlatform');
      const opened = await bridge.callMethod('openLink', url) as boolean;
      if (!opened) { throw new Error('no handler'); }`);

  out = replaceChecked(out,
    "import { common } from '@kit.AbilityKit';",
    "import Bridge from '@arkui-x.bridge';");

  return out;
}
