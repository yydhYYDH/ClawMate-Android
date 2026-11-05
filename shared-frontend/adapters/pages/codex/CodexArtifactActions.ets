// Android adaptation of pages/codex/CodexArtifactActions.ets.
//
// The HarmonyOS version used @kit.PreviewKit and the system document picker. Neither Kit
// exists under ArkUI-X, so preview and "save as" are delegated to the native side, which
// uses a FileProvider-backed ACTION_VIEW intent and ACTION_CREATE_DOCUMENT respectively.
//
// Export is start/poll for the same reason the pickers are: the user may take a long time
// choosing a destination, far longer than one bridge call may block.
//
// `true` means the export completed, not merely that the chooser opened.

import { common } from '@kit.AbilityKit';
import Bridge from '@arkui-x.bridge';
import { CodexLocalArtifact } from './CodexTypes';

function bridge(): ESObject { return Bridge.createBridge('ClawMatePlatform'); }

const POLL_INTERVAL_MS: number = 250;
const EXPORT_DEADLINE_MS: number = 10 * 60 * 1000;

interface ExportResult {
  v: number;
  status: string;
}

/** Opens the delivered file in whatever app handles its MIME type. */
export async function openCodexArtifact(context: common.UIAbilityContext, saved: CodexLocalArtifact): Promise<void> {
  const opened = await bridge().callMethod('codexOpenArtifact', JSON.stringify(saved)) as boolean;
  if (!opened) { throw new Error('该格式暂不支持预览，请使用“另存为”导出'); }
}

/** Lets the user pick a destination and copies the file there. */
export async function exportCodexArtifact(context: common.UIAbilityContext, saved: CodexLocalArtifact): Promise<boolean> {
  const started = await bridge().callMethod('codexExportStart', JSON.stringify(saved)) as boolean;
  if (started !== true) { return false; }
  const deadline = Date.now() + EXPORT_DEADLINE_MS;
  while (Date.now() < deadline) {
    const result = JSON.parse(await bridge().callMethod('codexExportResult') as string) as ExportResult;
    if (result === null || result.v !== 1) { throw new Error('导出接口版本不兼容'); }
    if (result.status === 'done') { return true; }
    if (result.status !== 'running') { return false; }
    await new Promise<void>((resolve): void => { setTimeout(resolve, POLL_INTERVAL_MS); });
  }
  return false;
}

/** True when the platform can attempt a preview for this artifact path. */
export function canPreviewCodexArtifact(path: string, mimeType: string): boolean {
  return path.length > 0 && mimeType.length > 0;
}
