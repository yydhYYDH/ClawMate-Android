import { util } from '@kit.ArkTS';
import fs from '@ohos.file.fs';
import Bridge from '@arkui-x.bridge';
import { ChatSessionStorage } from '../utils/ChatSessionStorage';
import { CloudContentPart } from '../utils/CloudModelClient';

interface ImagePreparation { v: number; status: string; images: string[]; error: string; fingerprints?: string[]; }
export interface ImageSnapshot { images: string[]; fingerprints: string[]; }
export interface ChatImageBatch { paths: string[]; parts: CloudContentPart[]; }
export class ChatImages {
  static async prepare(uris: string[], cancelled: () => boolean): Promise<string[]> {
    return (await ChatImages.prepareResult(uris, cancelled)).images;
  }
  static async snapshot(uris: string[], cancelled: () => boolean): Promise<ImageSnapshot> {
    let result = await ChatImages.prepareResult(uris, cancelled);
    let hashes = result.fingerprints;
    if (!hashes || hashes.length !== result.images.length || hashes.some((hash: string): boolean => !/^[0-9a-f]{64}$/.test(hash))) {
      throw new Error('图片快照缺少内容校验，请更新共享宿主后重试');
    }
    return { images: result.images, fingerprints: hashes };
  }
  private static async prepareResult(uris: string[], cancelled: () => boolean): Promise<ImagePreparation> {
    if (uris.length < 1 || uris.length > 4) { throw new Error('每次最多发送4张图片'); }
    let bridge = Bridge.createBridge('ClawMatePlatform');
    let id = await bridge.callMethod('imagePrepareStart', JSON.stringify(uris)) as string;
    let done = false;
    try {
      let deadline = Date.now() + 45000;
      while (Date.now() < deadline) {
        if (cancelled()) { throw new Error('图片准备已取消'); }
        let result = JSON.parse(await bridge.callMethod('imagePreparePoll', id) as string) as ImagePreparation;
        if (result.v !== 1) { throw new Error('图片接口版本不兼容'); }
        if (cancelled()) { throw new Error('图片准备已取消'); }
        if (result.status === 'done') {
          if (!Array.isArray(result.images) || result.images.length !== uris.length) { throw new Error('图片准备结果不完整'); }
          done = true; return result;
        }
        if (result.status !== 'running') { throw new Error(result.error || '图片解码失败，请重新选择较小图片'); }
        await new Promise<void>((resolve): void => { setTimeout(resolve, 120); });
      }
      throw new Error('图片准备超时');
    } finally { if (!done) { await bridge.callMethod('imagePrepareCancel', id); } }
  }
  static save(filesDir: string, sessionId: string, dataUrls: string[], text: string): ChatImageBatch {
    let paths: string[] = [];
    let parts: CloudContentPart[] = [{ type: 'text', text: text }];
    try {
      ChatSessionStorage.ensureSessionAssetsDir(filesDir, sessionId);
      for (let i = 0; i < dataUrls.length; i++) {
        let url = dataUrls[i];
        if (!url.startsWith('data:image/jpeg;base64,') || url.length > 1400000) { throw new Error('图片准备结果无效'); }
        let bytes = new util.Base64Helper().decodeSync(url.slice(url.indexOf(',') + 1));
        let path = ChatSessionStorage.sessionAssetPath(filesDir, sessionId, 'image-' + Date.now().toString() + '-' + i.toString() + '.jpg');
        paths.push('file://' + path);
        let file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
        try { fs.writeSync(file.fd, bytes.slice().buffer); } finally { fs.closeSync(file); }
        parts.push({ type: 'image_url', image_url: { url: url } });
      }
      return { paths: paths, parts: parts };
    } catch (error) { ChatImages.discard(paths); throw error as Error; }
  }
  static discard(paths: string[]): void {
    paths.forEach((uri: string): void => { try { fs.unlinkSync(uri.slice(7)); } catch (error) {} });
  }
}
