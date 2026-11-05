// Android adaptation of pages/codex/CodexCredentialStore.ets.
//
// The HarmonyOS version used @kit.AssetStoreKit, which has no ArkUI-X equivalent. On
// Android the secrets live in the native side, encrypted with an AES-256-GCM key held in
// the Android Keystore (see EncryptedCodexSecretStorage.kt). This file keeps the same
// validation and the same public surface, so callers are unchanged.
//
// The token is written once through the bridge and never read back into this file's
// storage; `load` returns it only so CodexClient can build an Authorization header.

import Bridge from '@arkui-x.bridge';
import {
  CodexConnection, CodexSavedConnection, codexConnectionLabel, normalizeCodexOrigin, validCodexToken, validCodexCa
} from './CodexTypes';

export class CodexCredentialStore {
  private static bridge(): ESObject { return Bridge.createBridge('ClawMatePlatform'); }

  /** Lists saved connections, newest first, without exposing tokens. */
  static async list(): Promise<CodexSavedConnection[]> {
    let raw = await CodexCredentialStore.bridge().callMethod('codexConnectionList') as string;
    let items = JSON.parse(raw) as CodexSavedConnection[];
    if (!Array.isArray(items)) { throw new Error('无法读取连接列表，请解锁设备后重试'); }
    return items;
  }

  /** Loads a connection, or the most recent one when [id] is empty. */
  static async load(id: string = ''): Promise<CodexConnection | null> {
    let raw = await CodexCredentialStore.bridge().callMethod('codexConnectionLoad', id) as string;
    if (raw === null || raw.length === 0) { return null; }
    let saved = JSON.parse(raw) as CodexConnection;
    if (normalizeCodexOrigin(saved.origin) !== saved.origin || !validCodexToken(saved.token)) {
      throw new Error('已保存的 Codex 连接信息无效，请删除后重新配对');
    }
    if (saved.caCertificate && !validCodexCa(saved.caCertificate)) {
      throw new Error('配对证书无效');
    }
    return saved;
  }

  /** Saves a connection, reusing the entry that already has this origin. */
  static async save(connection: CodexConnection, name: string = ''): Promise<CodexSavedConnection> {
    const origin = normalizeCodexOrigin(connection.origin);
    if (!validCodexToken(connection.token)) { throw new Error('桥接令牌格式无效'); }
    const ca = connection.caCertificate || '';
    if (ca.length > 0 && !validCodexCa(ca)) { throw new Error('配对证书格式无效'); }
    let raw = await CodexCredentialStore.bridge().callMethod('codexConnectionSave',
      JSON.stringify({ origin: origin, token: connection.token, caCertificate: ca, name: name.trim() })) as string;
    return JSON.parse(raw) as CodexSavedConnection;
  }

  /** Removes a saved connection, or the most recent one when [id] is empty. */
  static async forget(id: string = ''): Promise<void> {
    await CodexCredentialStore.bridge().callMethod('codexConnectionForget', id);
  }

  /** Human-readable label for an origin, shared with the source component. */
  static label(origin: string): string { return codexConnectionLabel(origin); }
}
