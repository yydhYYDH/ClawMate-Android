// Reuse exact original request/parser and PCM cache functions; Android owns I/O.
export function buildMimoProtocol(source) {
  const header = source.slice(source.indexOf('export type MimoAudioFormat'), source.indexOf('export class MimoVoiceClient'));
  const methods = ['buildTtsRequest','buildStreamingTtsRequest','parseTtsAudioBase64','parseTtsStreamDeltaLine','parseTtsStreamDeltaEvent','normalizeChatUrl'].map(name => {
    const found = source.match(new RegExp(`  static ${name}\\([^]*?\\n  \\}`));
    if (!found) throw new Error('Pinned MiMO method changed: ' + name);
    return found[0];
  });
  if (!header.startsWith('export type')) throw new Error('Pinned MiMO types changed');
  return "import { parseJsonCompat } from '../utils/JsonCompat';\nimport { normalizeVoiceTtsPreset } from '../utils/voice/VoiceTtsPresets';\n" +
    header + "export class MimoVoiceClient {\n  private static readonly DEFAULT_TTS_MODEL: string = 'mimo-v2.5-tts';\n" + methods.join('\n') + '\n}\n';
}
