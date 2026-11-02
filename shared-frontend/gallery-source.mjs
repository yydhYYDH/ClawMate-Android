import { replaceChecked } from './source-patches.mjs';

/** Keep original analyzer, work queue, result/cache and checkpoint callbacks. */
export function buildAndroidGallerySource(source) {
  source = replaceChecked(source, "import { AppTheme }", "import { AndroidGalleryScan } from '../../platform/AndroidGalleryScan';\nimport { AppTheme }");
  source = replaceChecked(source, /  private async startManualAnalyze\(\): Promise<void> \{[^]*?\n  \}/, `  private async startManualAnalyze(): Promise<void> {
    if (this.analyzing || this.hdcStarting || this.originalLoading || AndroidGalleryScan.busy(this.filesDir)) { showToastSafely('上一批图片仍在收尾，请稍后重试'); return; }
    this.hdcStarting = true;
    try {
      if (!AiLoginGuard.ensure(this.getUIContext()) || !await PrivacyConsentDialog.show(this.getUIContext())) { return; }
      await this.imageRepair;
      if (!this.pageActive) { return; }
      let existing: GalleryScanSession | null = null;
      let broken = false;
      try { existing = GalleryStorage.loadScanSession(this.filesDir); } catch (error) { broken = true; }
      if (broken || (existing !== null && existing.processed < existing.total)) {
        let replace = await promptAction.showDialog({ title: '新建图片批次？', message: broken ?
          '现有扫描进度无法读取。新批次将替换进度，已保存的图库结果会保留。' : '将替换尚未完成的批次进度，已保存的图库结果会保留。',
          buttons: [{ text: '保留当前批次', color: '#65718B' }, { text: '重新选择', color: '#276EF1' }] });
        if (replace.index !== 1 || !this.pageActive) { return; }
      }
      let explanation = await promptAction.showDialog({ title: '准备可恢复的图片批次',
        message: '请选择最多9张图片。为支持暂停和重启后继续，将保存去元数据的JPEG私有副本；不保留原始拍摄时间和位置，日期记为导入日。开始分析前会确认目标模型。',
        buttons: [{ text: '取消', color: '#65718B' }, { text: '选择图片', color: '#276EF1' }] });
      if (explanation.index !== 1 || !this.pageActive) { return; }
      let uris = await new GalleryPhotoPicker().selectImageUris(9);
      if (uris.length === 0 || !this.pageActive) { return; }
      this.setLastRunLabel('正在保存图片副本');
      let inputs = await AndroidGalleryScan.importImages(this.filesDir, uris, (): boolean => !this.pageActive);
      if (!this.pageActive || AndroidGalleryScan.busy(this.filesDir)) { throw new Error('页面已离开或上一批仍在收尾，请重新选择'); }
      let session = this.createScanSession(inputs);
      session.source = 'android-picker'; session.status = 'paused';
      GalleryStorage.saveScanSession(this.filesDir, session);
      this.manualAnalyzing = false; this.closeResultDetail(); this.clearMonthlyAiRuntimeState(); this.applyScanSession(session);
      await this.runScanSession(session);
    } catch (error) {
      this.analyzing = false; this.manualAnalyzing = false; this.setLastRunLabel('批次已停止');
      showToastSafely((error as Error).message || '图片批次无法开始，请重试', 2500);
    } finally { this.hdcStarting = false; }
  }`);
  source = replaceChecked(source, '  private async runScanSession(session: GalleryScanSession): Promise<void> {', `  private async runScanSession(session: GalleryScanSession): Promise<void> {
    AndroidGalleryScan.begin(this.filesDir);
    let live = true; let checking = false; let timer = -1;
    let scope = JSON.stringify(this.buildAnalyzerConfig(false));
    try {
      if (session.source === 'android-picker' && session.processed < session.total) {
        let config = this.buildAnalyzerConfig(false);
        if (!/^https:\\/\\/[^/?#@\\s]+(?::\\d+)?(?:\\/[^?#\\s]*)?$/.test(config.apiBaseUrl) || config.model.trim().length === 0) { throw new Error('请在图库设置填写HTTPS模型地址与模型名称，再点击继续分析'); }
        let answer = await promptAction.showDialog({ title: '分析本批次剩余图片？', message: '将分析剩余 ' + String(session.total - session.processed) +
          ' 张私有副本，发送图片及OCR文本至 ' + config.apiBaseUrl + '，模型 ' + config.model +
          (config.embeddingMode === 'remote' ? '；远程向量服务：' + config.embeddingBaseUrl + '，模型 ' + config.embeddingModel : '') +
          '。暂停后已开始的图片会完成，尚未开始的图片留待继续。',
          buttons: [{ text: '暂不分析', color: '#65718B' }, { text: '开始分析', color: '#276EF1' }] });
        if (answer.index !== 1) { session.status = 'paused'; GalleryStorage.saveScanSession(this.filesDir, session); this.applyScanSession(session); return; }
        if (!this.pageActive || !await AndroidGalleryScan.windowReady()) { throw new Error('请在前台解锁后继续分析'); }
        PrivacyConsent.requireAccepted();
        if (scope !== JSON.stringify(this.buildAnalyzerConfig(false))) { throw new Error('图库模型配置已变化，请重新确认'); }
      }
      timer = setInterval((): void => {
        if (checking || !live || this.pauseRequested) { return; }
        checking = true;
        AndroidGalleryScan.windowReady().then((ready: boolean): void => {
          if (live && (!ready || !this.pageActive || !PrivacyConsent.isAccepted() || scope !== JSON.stringify(this.buildAnalyzerConfig(false))) && !this.pauseRequested) { this.requestPauseAutoAnalyze(); }
        }).catch((): void => { if (live && !this.pauseRequested) { this.requestPauseAutoAnalyze(); } }).finally((): void => { checking = false; });
      }, 250);
      await this.runAndroidScanCore(session);
    } finally {
      live = false; if (timer >= 0) { clearInterval(timer); }
      this.analyzing = false; AndroidGalleryScan.end(this.filesDir);
    }
  }

  private async runAndroidScanCore(session: GalleryScanSession): Promise<void> {`);
  source = replaceChecked(source, 'this.buildAnalyzerConfig(session.source !== \'hdc\')', "this.buildAnalyzerConfig(session.source !== 'hdc' && session.source !== 'android-picker')");
  source = replaceChecked(source, "        if (session.source !== 'hdc') { return; }", `        if (session.source === 'android-picker') {
          if (!this.pageActive || !PrivacyConsent.isAccepted() || !await AndroidGalleryScan.windowReady()) { this.requestPauseAutoAnalyze(); throw new Error('图库已暂停'); }
          await AndroidGalleryScan.verify(this.filesDir, input); return;
        }
        if (session.source !== 'hdc') { return; }`);
  source = replaceChecked(source, '    if (this.hdcReader !== null) { this.pauseRequested = true; this.hdcReader.pause(); }',
    '    if (this.analyzing && !this.pauseRequested) { this.requestPauseAutoAnalyze(); }', { all: true, expected: 2 });
  const restore = source.match(/  private restoreScanSessionState\(\): void \{[^]*?\n  \}/)?.[0];
  if (!restore) throw new Error('Gallery restore method missing');
  let repaired = replaceChecked(restore, '    this.applyScanSession(session);',
    "    if (session.status === 'running' || session.status === 'pausing') { session.status = 'paused'; }\n    this.applyScanSession(session);", { expected: 2 });
  // Only the first apply handles process interruption; the second repairs completed/pending inconsistencies.
  repaired = replaceChecked(repaired, '  private restoreScanSessionState(): void {', '  private restoreScanSessionState(): void {\n    try {');
  repaired = repaired.slice(0, -4) + `\n    } catch (error) {
      this.autoScanStatus = 'failed'; this.setLastRunLabel('扫描进度读取失败'); showToastSafely((error as Error).message, 2500);
    }
  }`;
  source = replaceChecked(source, restore, repaired);
  source = replaceChecked(source, '    this.imageRepair = GalleryImageStore.migrateHistory(this.filesDir, GalleryStorage.loadScanSession(this.filesDir),',
    "    let session: GalleryScanSession | null = null;\n    try { session = GalleryStorage.loadScanSession(this.filesDir); } catch (error) { this.imageRepair = Promise.resolve(); return; }\n    this.imageRepair = GalleryImageStore.migrateHistory(this.filesDir, session,");
  const continuation = source.match(/  private async continueAutoAnalyze\(\): Promise<void> \{[^]*?\n  \}/)?.[0];
  if (!continuation) throw new Error('Gallery continuation method missing');
  let guarded = replaceChecked(continuation, '  private async continueAutoAnalyze(): Promise<void> {', '  private async continueAutoAnalyze(): Promise<void> {\n    try {');
  guarded = guarded.slice(0, -4) + `\n    } catch (error) { this.analyzing = false; this.autoScanStatus = 'failed'; this.setLastRunLabel('扫描进度读取失败'); showToastSafely((error as Error).message, 2500); }
  }`;
  source = replaceChecked(source, continuation, guarded);
  const pause = source.match(/  private requestPauseAutoAnalyze\(\): void \{[^]*?\n  \}/)?.[0];
  if (!pause) throw new Error('Gallery pause method missing');
  let guardedPause = replaceChecked(pause, '  private requestPauseAutoAnalyze(): void {', '  private requestPauseAutoAnalyze(): void {\n    try {');
  guardedPause = guardedPause.slice(0, -4) + `\n    } catch (error) { this.pauseRequested = true; this.autoScanStatus = 'pausing'; showToastSafely('已停止派发，但保存进度失败：' + (error as Error).message, 2500); }
  }`;
  source = replaceChecked(source, pause, guardedPause);
  source = replaceChecked(source, "        if (!this.analyzing && !this.hdcStarting) { this.hdcSheetOpen = true; }", '        if (!this.analyzing && !this.hdcStarting) { this.startManualAnalyze(); }');
  source = replaceChecked(source, "    return '批量读图';", "    return '新建批次';");
  source = replaceChecked(source, '  private clearAnalysis(): void {',
    "  private clearAnalysis(): void {\n    if (AndroidGalleryScan.busy(this.filesDir)) { showToastSafely('上一批图片仍在收尾，请稍后清空'); return; }");
  return source;
}
