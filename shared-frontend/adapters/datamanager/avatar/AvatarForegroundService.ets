import Bridge from '@arkui-x.bridge';
import { util } from '@kit.ArkTS';
import { showToastSafely } from '../../utils/PromptSafe';
import { DataManagerPaths } from '../DataManagerPaths';
import { DataManagerStorage } from '../DataManagerStorage';
import { AvatarStorage } from './AvatarStorage';
import { cropRgbaToAlphaBounds, findAlphaCropBounds } from './AvatarForegroundCrop';
import { createAvatarForegroundMessageRgba, encodeAvatarForegroundMessagePng, encodeRgbaToPng } from './AvatarForegroundThumbnail';

interface ForegroundResult { v: number; status: string; width: number; height: number; rgba: string; error: string; }

/** Android supplies only pixels/person mask. Keep the original shared asset contract. */
export class AvatarForegroundService {
  private queue: Promise<string> = Promise.resolve('');
  private paths: DataManagerPaths;
  private storage: DataManagerStorage;
  private avatarStorage: AvatarStorage;
  constructor(paths: DataManagerPaths, storage: DataManagerStorage, avatarStorage: AvatarStorage) {
    this.paths = paths; this.storage = storage; this.avatarStorage = avatarStorage;
  }
  async ensureForegroundForVersion(versionId: string, imagePath: string): Promise<string> {
    if (versionId.length === 0 || imagePath.trim().length === 0) { return ''; }
    if (!/^[A-Za-z0-9_-]{1,160}$/.test(versionId)) { throw new Error('分身版本编号无效'); }
    let task = this.queue.then(async (): Promise<string> => this.ensure(versionId, imagePath));
    this.queue = task.catch((): string => '');
    try { return await task; } catch (error) {
      showToastSafely((error as Error).message || '人物分割未完成，原分身已保留', 2500);
      throw error as Error;
    }
  }
  private async ensure(versionId: string, imagePath: string): Promise<string> {
    let existing = this.avatarStorage.foregroundExists(versionId);
    if (existing && this.avatarStorage.messageForegroundExists(versionId)) { return this.avatarStorage.foregroundDisplayUri(versionId); }
    let source = existing ? this.avatarStorage.foregroundAssetPath(versionId) : imagePath.trim();
    if (!source.startsWith('file://')) {
      if (source.includes('://') || source.startsWith('data:')) { throw new Error('人物分割只接受本机分身图片'); }
      source = 'file://' + (source.startsWith('/') ? source : this.storage.join(this.paths.avatarRoot, source));
    }
    let result = await this.prepare(source, !existing);
    let pixels = new util.Base64Helper().decodeSync(result.rgba);
    if (pixels.length !== result.width * result.height * 4) { throw new Error('人物分割像素不完整'); }
    let bounds = findAlphaCropBounds(pixels, result.width, result.height, 0);
    if (bounds.isEmpty) { throw new Error('未检测到人物前景，原分身图片已保留'); }
    let cropped = cropRgbaToAlphaBounds(pixels, result.width, bounds);
    let thumbnail = createAvatarForegroundMessageRgba(cropped.pixels, cropped.width, cropped.height);
    let message = encodeAvatarForegroundMessagePng(thumbnail);
    if (!existing) { this.avatarStorage.saveForegroundPng(versionId, encodeRgbaToPng(cropped.pixels, cropped.width, cropped.height)); }
    this.avatarStorage.saveMessageForegroundPng(versionId, message);
    return this.avatarStorage.foregroundDisplayUri(versionId);
  }
  private async prepare(uri: string, segment: boolean): Promise<ForegroundResult> {
    let bridge = Bridge.createBridge('ClawMatePlatform');
    let id = await bridge.callMethod('avatarForegroundStart', uri, segment) as string;
    let done = false;
    try {
      let deadline = Date.now() + 45000;
      while (Date.now() < deadline) {
        let value = JSON.parse(await bridge.callMethod('avatarForegroundPoll', id) as string) as ForegroundResult;
        if (value.v !== 1) { throw new Error('人物分割接口版本不兼容'); }
        if (value.status === 'done') {
          if (!Number.isInteger(value.width) || !Number.isInteger(value.height) || value.width < 1 || value.height < 1 ||
            value.width > 512 || value.height > 512 || typeof value.rgba !== 'string' || value.rgba.length > 1400000) {
            throw new Error('人物分割结果无效');
          }
          done = true; return value;
        }
        if (value.status !== 'running') { throw new Error(value.error || '人物分割未完成，请换用人物清晰的图片'); }
        await new Promise<void>((resolve): void => { setTimeout(resolve, 120); });
      }
      throw new Error('人物分割超时，原分身图片已保留');
    } finally { if (!done) { await bridge.callMethod('avatarForegroundCancel', id); } }
  }
}
