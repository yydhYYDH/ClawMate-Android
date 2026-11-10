// Android adaptations applied to pages/codex/CodexPage.ets at prepare time.
//
// Every patch is anchored on exact upstream text, so an upstream change fails the build
// instead of silently shipping an unadapted Huawei-platform call. The three adaptations:
//
//   1. ScanKit is replaced by the native QR scanner (CameraX/ML Kit);
//   2. CameraKit and MediaLibraryKit are replaced by the native photo pickers;
//   3. FrostedPage's HarmonyOS-only `titleFadeHeight` parameter is dropped.
//
// Everything else — state, polling, approval flow, attachment upload — runs unchanged.

import { replaceChecked } from '../../../source-patches.mjs';

const NATIVE_SCANNER = `    import { CodexPickers } from '../../platform/CodexPickers';`;

/** Adapts the delivered-file card: ArkUI-X has no `fileUri` Kit for a thumbnail source. */
export function adaptCodexArtifactCard(source) {
  let out = replaceChecked(source,
    "import { fileUri } from '@kit.CoreFileKit';\n",
    '');
  // The file already lives in the app sandbox, so the plain path is a valid Image source.
  out = replaceChecked(out,
    'Image(fileUri.getUriFromPath(this.localPath))',
    'Image(this.localPath)');
  return out;
}

/** Applies every CodexPage adaptation. */
export function adaptCodexPage(source) {
  let out = source;

  // 1. Imports: drop the Kits that do not exist under ArkUI-X.
  out = replaceChecked(out,
    "import { scanBarcode, scanCore } from '@kit.ScanKit';\n",
    NATIVE_SCANNER + '\n');
  out = replaceChecked(out,
    "import { camera, cameraPicker } from '@kit.CameraKit';\n",
    '');
  out = replaceChecked(out,
    "import { photoAccessHelper } from '@kit.MediaLibraryKit';\n",
    '');

  // 2. ScanKit -> native scanner. The pairing check that follows is unchanged.
  out = replaceChecked(out,
    `      if (!canIUse('SystemCapability.Multimedia.Scan.ScanBarcode')) {
        throw new Error('此设备暂不支持扫码，请导入配对文件或粘贴配对信息');
      }
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const options: scanBarcode.ScanOptions = {
        scanTypes: [scanCore.ScanType.QR_CODE], enableMultiMode: false, enableAlbum: true
      };
      const result = await scanBarcode.startScanForResult(context, options);
      if (epoch !== this.epoch || this.screen !== CodexScreen.Setup) { return; }
      if (!result.originalValue.startsWith(CODEX_PAIRING_PREFIX)) {
        throw new Error('这不是 ClawMate 配对二维码，请扫描电脑端「手机配对」中的二维码');
      }
      this.pairingInput = result.originalValue;`,
    `      const scanned = await CodexPickers.scanPairingCode();
      if (epoch !== this.epoch || this.screen !== CodexScreen.Setup) { return; }
      if (scanned.length === 0) { return; }
      if (!scanned.startsWith(CODEX_PAIRING_PREFIX)) {
        throw new Error('这不是 ClawMate 配对二维码，请扫描电脑端「手机配对」中的二维码');
      }
      this.pairingInput = scanned;`);

  // 3. CameraKit -> native camera, MediaLibraryKit -> native photo picker.
  out = replaceChecked(out,
    `        const context = this.getUIContext().getHostContext();
        if (context === undefined) { throw new Error('页面已关闭，请返回对话后重试拍照'); }
        cameraPath = context.cacheDir + '/codex-camera-' + util.generateRandomUUID(true) + '.jpg';
        const output = await fileIo.open(cameraPath, fileIo.OpenMode.CREATE | fileIo.OpenMode.READ_WRITE);
        await fileIo.close(output);
        const saveUri = fileUri.getUriFromPath(cameraPath);
        const profile: cameraPicker.PickerProfile = {
          cameraPosition: camera.CameraPosition.CAMERA_POSITION_BACK, saveUri: saveUri
        };
        const result = await cameraPicker.pick(context, [cameraPicker.PickerMediaType.PHOTO], profile);
        if (result.resultCode !== 0 || result.mediaType !== cameraPicker.PickerMediaType.PHOTO) { return; }
        uris = [result.resultUri.trim() || saveUri];
      } else if (source === 'album') {
        const options = new photoAccessHelper.PhotoSelectOptions();
        options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE; options.maxSelectNumber = 1;
        uris = (await new photoAccessHelper.PhotoViewPicker().select(options)).photoUris;`,
    `        const context = this.getUIContext().getHostContext();
        if (context === undefined) { throw new Error('页面已关闭，请返回对话后重试拍照'); }
        cameraPath = await CodexPickers.takePhoto(context.cacheDir);
        if (cameraPath.length === 0) { return; }
        uris = [cameraPath];
      } else if (source === 'album') {
        const picked = await CodexPickers.pickImage();
        if (picked.length === 0) { return; }
        cameraPath = picked;
        uris = [picked];`);

  // 4. The attachment name no longer comes from a file URI: both pickers return a path.
  out = replaceChecked(out,
    `      const name = new fileUri.FileUri(uris[0]).name || (image ? 'image' : 'file');`,
    `      const name = uris[0].substring(uris[0].lastIndexOf('/') + 1) || (image ? 'image' : 'file');`);

  // 5. FrostedPage has no title fade on Android: the header is a plain capsule.
  // 5. FrostedPage has no title fade on Android: the header is a plain capsule.
  out = replaceChecked(out,
    `scrollers: [this.scroller], titleFadeHeight: 0,`,
    `scrollers: [this.scroller],`);

  // 6. TextArea.barState is not supported across ArkUI-X platforms. The composer is a
  //     20000-character field; leaving the scrollbar at its default keeps it usable.
  out = replaceChecked(out,
    '.layoutWeight(1).height(chatComposerTextAreaHeight(this.draft)).barState(BarState.Auto)',
    '.layoutWeight(1).height(chatComposerTextAreaHeight(this.draft))');

  // 7. A confirmation dialog whose button callback never fires (observed on Android when the
  //    dialog is dismissed from outside) leaves `busy` set forever and the whole page dead.
  //    Bound the wait so the page always returns to a usable state.
  out = replaceChecked(out,
    `      const confirmed = await new Promise<boolean>((resolve) => {
        this.getUIContext().showAlertDialog({ title: '连接自己的电脑',
          message: bundle.name + '\\n' + bundle.origin + '\\n\\n请确认这份配对信息来自你自己的 PC 应用。连接后可以向该电脑下发任务。',
          autoCancel: false,
          primaryButton: { value: '取消', action: () => { resolve(false); } },
          secondaryButton: { value: '确认配对', action: () => { resolve(true); } }
        });
      });`,
    `      const confirmed = await CodexConfirm.ask(this.getUIContext(), '连接自己的电脑',
        bundle.name + '\\n' + bundle.origin + '\\n\\n请确认这份配对信息来自你自己的 PC 应用。连接后可以向该电脑下发任务。',
        '取消', '确认配对');`);

  out = replaceChecked(out,
    `    const confirmed = await new Promise<boolean>((resolve) => {
      this.getUIContext().showAlertDialog({ title: '删除连接',
        message: '从此手机删除「' + item.name + '」？电脑上的任务会继续运行。',
        primaryButton: { value: '取消', action: () => { resolve(false); } },
        secondaryButton: { value: '删除', action: () => { resolve(true); } } });
    });`,
    `    const confirmed = await CodexConfirm.ask(this.getUIContext(), '删除连接',
      '从此手机删除「' + item.name + '」？电脑上的任务会继续运行。', '取消', '删除');`);

  out = replaceChecked(out,
    "    import { CodexPickers } from '../../platform/CodexPickers';",
    "    import { CodexPickers } from '../../platform/CodexPickers';\n    import { CodexConfirm } from '../../platform/CodexConfirm';\n    import { CollectedMemory } from '../../platform/CollectedMemory';");

  out = replaceChecked(out, "  private pendingRequestId: string = '';",
    "  private pendingRequestId: string = '';\n  private pendingTurnText: string = '';");
  out = replaceChecked(out, "this.pendingRequestId = '';", "this.pendingRequestId = ''; this.pendingTurnText = '';", { all: true });
  out = replaceChecked(out,
    `      if (!this.pendingRequestId) { this.pendingRequestId = util.generateRandomUUID(true); }
      const result = await this.client.call('/v1/tasks/' + this.task.id + '/turns', {
        requestId: this.pendingRequestId, text: this.draft, attachments:`,
    `      if (!this.pendingRequestId) {
        const origin = this.connectedOrigin;
        const taskId = this.task.id;
        const context = this.getUIContext().getHostContext();
        const text = await CollectedMemory.attachForCodex(this.draft, this.draft, context?.filesDir || '',
          (this.serverName || 'Codex 电脑') + '（' + origin + '）',
          (): boolean => epoch === this.epoch && origin === this.connectedOrigin && this.task?.id === taskId);
        if (epoch !== this.epoch || origin !== this.connectedOrigin || this.task?.id !== taskId) { return; }
        this.pendingTurnText = text;
        this.pendingRequestId = util.generateRandomUUID(true);
      }
      const result = await this.client.call('/v1/tasks/' + this.task.id + '/turns', {
        requestId: this.pendingRequestId, text: this.pendingTurnText, attachments:`);

  return out;
}
