// Android adaptation of pages/codex/CodexResponseText.ets.
//
// The HarmonyOS client used @kit.BasicServicesKit zlib because its RCP transport could
// hand back either decoded text or the raw gzip bytes. The Android transport is OkHttp,
// which performs transparent gzip decoding and rejects a body it cannot inflate, so the
// ArkTS side only ever receives decoded text. Gzip bytes that still reach this function
// mean something upstream broke, and are reported instead of being silently accepted.

const MAX_RESPONSE_BYTES: number = 16 * 1024 * 1024;

export function codexResponseText(body: ArrayBuffer | undefined, text: string | null): string {
  const bytes: Uint8Array = body === undefined ? new Uint8Array(0) : new Uint8Array(body);
  if (bytes.byteLength > MAX_RESPONSE_BYTES) { throw new Error('服务器响应过大'); }
  if (bytes.byteLength >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b) {
    throw new Error('压缩响应未能解析，将改用普通传输，请同步会话后重试');
  }
  if (text === null || text.trim().length === 0) {
    throw new Error('服务器未返回有效的文本响应，请稍后重试');
  }
  return text;
}
