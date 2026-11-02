import fs from '@ohos.file.fs';
import { util } from '@kit.ArkTS';
import { ChatImages } from './ChatImages';
import { NativeCapabilities } from './NativeCapabilities';
import { GalleryStorage } from '../utils/gallery/GalleryStorage';
import { GalleryPhotoInput } from '../utils/gallery/GalleryTypes';
import Bridge from '@arkui-x.bridge';

export class AndroidGalleryScan {
  private static active: Set<string> = new Set();
  static busy(root: string): boolean { return AndroidGalleryScan.active.has(root); }
  static begin(root: string): void {
    if (AndroidGalleryScan.busy(root)) { throw new Error('上一批图片仍在收尾，请稍后点击继续'); }
    AndroidGalleryScan.active.add(root);
  }
  static end(root: string): void { AndroidGalleryScan.active.delete(root); }
  static async windowReady(): Promise<boolean> { return (await NativeCapabilities.runtime()).windowReady === true; }
  static async verify(filesDir: string, input: GalleryPhotoInput): Promise<void> {
    let hash = input.id ? input.id.slice(16) : '';
    let path = GalleryStorage.rootDir(filesDir) + '/android-images/' + hash + '.jpg';
    if (!/^[0-9a-f]{64}$/.test(hash) || input.uri !== 'file://' + path ||
      await Bridge.createBridge('ClawMatePlatform').callMethod('imageDigest', input.uri) !== hash) {
      throw new Error('图片副本缺失或校验失败，请重新选择该图片');
    }
  }
  static async importImages(filesDir: string, uris: string[], stopped: () => boolean): Promise<GalleryPhotoInput[]> {
    if (uris.length < 1 || uris.length > 9) { throw new Error('每批请选择1到9张图片'); }
    let inputs: GalleryPhotoInput[] = []; let created: string[] = []; let seen: Set<string> = new Set();
    let directory = GalleryStorage.rootDir(filesDir) + '/android-images';
    AndroidGalleryScan.begin(filesDir);
    try {
      GalleryStorage.ensureWorkspace(filesDir); GalleryStorage.ensureDirectory(directory);
      for (let uri of uris) {
        if (stopped() || !await AndroidGalleryScan.windowReady()) { throw new Error('图片准备已停止，请回到图库重新选择'); }
        let snapshot = await ChatImages.snapshot([uri], stopped);
        if (stopped() || !await AndroidGalleryScan.windowReady()) { throw new Error('图片准备已停止'); }
        let hash = snapshot.fingerprints[0];
        if (seen.has(hash)) { continue; }
        seen.add(hash);
        let data = snapshot.images[0];
        if (!data.startsWith('data:image/jpeg;base64,') || data.length > 1400000) { throw new Error('图片快照无效'); }
        let bytes = new util.Base64Helper().decodeSync(data.slice(data.indexOf(',') + 1)).slice().buffer as ArrayBuffer;
        let path = directory + '/' + hash + '.jpg';
        let existed = fs.accessSync(path); let usable = false;
        if (existed) { try { await AndroidGalleryScan.verify(filesDir, { uri: 'file://' + path, id: 'gallery_android_' + hash }); usable = true; } catch (ignored) {} }
        if (!usable) {
          if (!existed) { created.push(path); }
          GalleryStorage.writeBytes(path + '.part', bytes);
          if (fs.statSync(path + '.part').size !== bytes.byteLength) { throw new Error('图片快照保存不完整'); }
          fs.renameSync(path + '.part', path);
        }
        await AndroidGalleryScan.verify(filesDir, { uri: 'file://' + path, id: 'gallery_android_' + hash });
        let day = new Date();
        let date = day.getFullYear().toString() + '-' + String(day.getMonth() + 1).padStart(2, '0') + '-' + String(day.getDate()).padStart(2, '0');
        inputs.push({ uri: 'file://' + path, id: 'gallery_android_' + hash, date: date });
      }
      return inputs;
    } catch (error) {
      // Only files created by this uncommitted import can be removed.
      for (let path of created) { try { fs.unlinkSync(path + '.part'); } catch (ignored) {} try { fs.unlinkSync(path); } catch (ignored) {} }
      throw error as Error;
    } finally { AndroidGalleryScan.end(filesDir); }
  }
}
