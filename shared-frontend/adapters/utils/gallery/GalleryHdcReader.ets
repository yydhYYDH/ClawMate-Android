import { common } from '@kit.AbilityKit';
import { GalleryPhotoInput } from './GalleryTypes';
export class GalleryHdcReader {
  constructor(context: common.UIAbilityContext, shouldPause: () => boolean,
    onProgress: (message: string) => void, onCancel: () => void) {}
  async open(title: string = '图库批量读图'): Promise<void> { throw new Error('安卓尚未接入 HDC 批量读图，请使用“手动分析”系统选图'); }
  async latestImages(count: number): Promise<GalleryPhotoInput[]> { throw new Error('安卓 HDC 读取不可用'); }
  async prepareImage(input: GalleryPhotoInput, forceRefresh: boolean = false): Promise<void> { throw new Error('安卓 HDC 原图读取不可用'); }
  pause(): void {}
  note(message: string): void {}
  finish(outcome: string, message: string): void {}
}
