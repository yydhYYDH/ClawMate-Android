import fs from '@ohos.file.fs';
import { decodeUtf8Compat, encodeUtf8Compat } from '../utils/TextEncodingCompat';
import { GalleryScanSession } from '../utils/gallery/GalleryTypes';

// The original scan-session contract remains the single durable owner.
export class GalleryScanCheckpoint {
  static validate(session: GalleryScanSession, filesDir: string): GalleryScanSession {
    if (!session || !Array.isArray(session.items) || session.items.length > 5000 ||
      typeof session.id !== 'string' || !/^[A-Za-z0-9_-]{1,160}$/.test(session.id) ||
      ['queued', 'running', 'pausing', 'paused', 'completed', 'failed'].indexOf(session.status) < 0 ||
      !Number.isSafeInteger(session.createdAt) || session.createdAt < 0 || !Number.isSafeInteger(session.updatedAt) || session.updatedAt < 0 ||
      (session.source !== undefined && ['hdc', 'android-picker', 'media'].indexOf(session.source) < 0)) {
      throw new Error('扫描进度格式损坏，请重新选择图片；已有图库结果会保留');
    }
    let ids: Set<string> = new Set();
    let processed = 0; let skipped = 0; let failed = 0;
    for (let item of session.items) {
      if (!item || typeof item.id !== 'string' || !/^[A-Za-z0-9_-]{1,160}$/.test(item.id) || ids.has(item.id) ||
        typeof item.uri !== 'string' || item.uri.length === 0 || item.uri.length > 8192 ||
        typeof item.date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(item.date) ||
        ['queued', 'failed', 'done', 'skipped'].indexOf(item.status) < 0 || typeof item.error !== 'string' || item.error.length > 8000 ||
        (item.skipReason !== undefined && (typeof item.skipReason !== 'string' || item.skipReason.length > 8000)) ||
        (item.thumbnailUri !== undefined && (typeof item.thumbnailUri !== 'string' || item.thumbnailUri.length > 8192))) {
        throw new Error('扫描图片进度损坏，请重新选择图片；已有图库结果会保留');
      }
      let date = Date.parse(item.date + 'T00:00:00Z');
      if (!Number.isFinite(date) || new Date(date).toISOString().slice(0, 10) !== item.date) { throw new Error('扫描图片日期无效，请重新选择'); }
      if (session.source === 'android-picker') {
        if (!/^gallery_android_[0-9a-f]{64}$/.test(item.id) ||
          item.uri !== 'file://' + filesDir + '/gallery-log/android-images/' + item.id.slice(16) + '.jpg') {
          throw new Error('扫描图片不属于本机批次，请重新选择');
        }
      }
      ids.add(item.id);
      if (item.status === 'skipped' && item.skipReason === '已存在可用缓存') { item.status = 'done'; item.skipReason = ''; item.error = ''; }
      if (item.status === 'done' || item.status === 'skipped') { processed++; }
      if (item.status === 'skipped') { skipped++; }
      if (item.status === 'failed') { failed++; }
    }
    session.total = session.items.length; session.processed = processed; session.skipped = skipped; session.failed = failed;
    return session;
  }
  static read(path: string, filesDir: string): GalleryScanSession | null {
    if (!fs.accessSync(path)) { return null; }
    let size = fs.statSync(path).size;
    if (size <= 0 || size > 4000000) { throw new Error('扫描进度文件无效，请重新选择图片；已有图库结果会保留'); }
    let file = fs.openSync(path, fs.OpenMode.READ_ONLY);
    let data = new ArrayBuffer(size);
    try { if (fs.readSync(file.fd, data) !== size) { throw new Error('读取扫描进度不完整，请重试'); } }
    finally { fs.closeSync(file); }
    let parsed: GalleryScanSession;
    try { parsed = JSON.parse(decodeUtf8Compat(new Uint8Array(data))) as GalleryScanSession; }
    catch (error) { throw new Error('扫描进度无法解析，请重新选择图片；已有图库结果会保留'); }
    return GalleryScanCheckpoint.validate(parsed, filesDir);
  }
  static write(path: string, session: GalleryScanSession, filesDir: string): void {
    GalleryScanCheckpoint.validate(session, filesDir);
    let bytes = encodeUtf8Compat(JSON.stringify(session));
    if (bytes.byteLength > 4000000) { throw new Error('扫描进度超出保存上限'); }
    let temporary = path + '.part';
    try {
      let file = fs.openSync(temporary, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
      try { fs.writeSync(file.fd, bytes.slice().buffer); } finally { fs.closeSync(file); }
      if (fs.statSync(temporary).size !== bytes.byteLength) { throw new Error('扫描进度未完整保存，请重试'); }
      fs.renameSync(temporary, path);
    } catch (error) { try { fs.unlinkSync(temporary); } catch (ignored) {} throw error as Error; }
  }
}
