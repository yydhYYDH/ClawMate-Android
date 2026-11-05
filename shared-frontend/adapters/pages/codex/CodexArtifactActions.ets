// Android adaptation of pages/codex/CodexArtifactActions.ets.
//
// The HarmonyOS version used @kit.PreviewKit and the system document picker. Neither Kit
// exists under ArkUI-X, so preview and "save as" are delegated to the native side, which
// uses a FileProvider-backed ACTION_VIEW intent and ACTION_CREATE_DOCUMENT respectively.
//
// Both calls return a boolean: `true` means the system UI was opened. Opening the chooser
// is not proof that the user completed the export, so callers must not treat `true` as
// "file saved".

import { common } from '@kit.AbilityKit';
import Bridge from '@arkui-x.bridge';
import { CodexLocalArtifact } from './CodexTypes';

function bridge(): ESObject { return Bridge.createBridge('ClawMatePlatform'); }

/** Opens the delivered file in whatever app handles its MIME type. */
export async function openCodexArtifact(context: common.UIAbilityContext, saved: CodexLocalArtifact): Promise<void> {
  const opened = await bridge().callMethod('codexOpenArtifact', JSON.stringify(saved)) as boolean;
  if (!opened) { throw new Error('该格式暂不支持预览，请使用“另存为”导出'); }
}

/** Lets the user pick a destination and copies the file there. */
export async function exportCodexArtifact(context: common.UIAbilityContext, saved: CodexLocalArtifact): Promise<boolean> {
  return await bridge().callMethod('codexExportArtifact', JSON.stringify(saved)) as boolean;
}

/** True when the platform can attempt a preview for this artifact path. */
export function canPreviewCodexArtifact(path: string, mimeType: string): boolean {
  return path.length > 0 && mimeType.length > 0;
}
