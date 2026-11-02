import { image } from '@kit.ImageKit';
import { NativeCapabilities } from '../../platform/NativeCapabilities';
export class GalleryOcrService {
  private generation: number = 0;
  private activeIds: Set<string> = new Set();
  async recognize(pixelMap: image.PixelMap): Promise<string> {
    throw new Error('安卓本地识别需要图片 URI，请使用系统选图入口');
  }
  async recognizeUri(uri: string): Promise<string> {
    let generation = this.generation;
    let operation = '';
    try {
      return await NativeCapabilities.recognize(uri, (id: string): void => { operation = id; this.activeIds.add(id); },
        (): boolean => generation !== this.generation);
    } finally { this.activeIds.delete(operation); }
  }
  async release(): Promise<void> {
    this.generation += 1;
    let ids: string[] = Array.from(this.activeIds);
    this.activeIds.clear();
    for (let id of ids) { await NativeCapabilities.cancelRecognition(id); }
  }
}
