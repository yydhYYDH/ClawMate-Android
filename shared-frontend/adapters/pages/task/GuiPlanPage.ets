import Bridge from '@arkui-x.bridge';
import { common } from '@kit.AbilityKit';
import { router, promptAction } from '@kit.ArkUI';
import { util } from '@kit.ArkTS';
import fs from '@ohos.file.fs';
import { GuiPlan } from '../../platform/GuiPlan';
import { PrivacyConsentDialog } from '../../components/PrivacyConsentDialog';
import { AiServiceAccess } from '../../utils/AiServiceAccess';
import { AppTheme } from '../../utils/AppTheme';

interface ScreenshotPoll { v: number; status: string; dataUrl: string; width: number; height: number; }
interface GuiControlSnapshot { v: number; supported: boolean; connected: boolean; message: string; }
@Entry
@Component
struct GuiPlanPage {
  @State task: string = '';
  @State preview: string = '';
  @State status: string = '尚未截图；此页仅提供单步建议，不执行动作。';
  @State busy: boolean = false;
  @State controlStatus: string = '跨应用手动控制需要Android 14及以上，以及系统无障碍授权。';
  private image: string = '';
  private shotWidth: number = 0;
  private shotHeight: number = 0;
  private operation: string = '';
  private generation: number = 0;
  private client: GuiPlan = new GuiPlan();
  private async control(method: string): Promise<void> {
    try { if (method !== 'guiControlStatus') { await Bridge.createBridge('ClawMatePlatform').callMethod(method); }
      let value = JSON.parse(await Bridge.createBridge('ClawMatePlatform').callMethod('guiControlStatus') as string) as GuiControlSnapshot;
      if (value.v !== 1) { throw new Error('控制接口版本不兼容，请更新应用'); }
      this.controlStatus = (value.supported ? value.connected ? '无障碍服务已连接；仍需选择应用并按次授权。' : '无障碍服务未连接，当前不能跨应用操作。' : '此设备需要Android 14及以上才能跨应用控制。') + '\n' + value.message;
    } catch (error) { this.controlStatus = (error as Error).message; }
  }
  private clear(): void {
    this.generation++; this.client.cancel();
    if (this.operation) { Bridge.createBridge('ClawMatePlatform').callMethod('guiScreenshotCancel', this.operation).catch(() => {}); this.operation = ''; }
    if (this.preview) { try { fs.unlinkSync(this.preview.slice(7)); } catch (ignored) {} }
    this.image = ''; this.preview = ''; this.shotWidth = 0; this.shotHeight = 0;
  }
  private async capture(): Promise<void> {
    if (this.busy) { return; }
    this.clear(); this.busy = true; let generation = this.generation;
    let bridge = Bridge.createBridge('ClawMatePlatform');
    try {
      let id = await bridge.callMethod('guiScreenshotStart') as string;
      if (generation !== this.generation) { await bridge.callMethod('guiScreenshotCancel', id); return; }
      this.operation = id; let deadline = Date.now() + 8000;
      while (Date.now() < deadline) {
        let value = JSON.parse(await bridge.callMethod('guiScreenshotPoll', id) as string) as ScreenshotPoll;
        if (generation !== this.generation) { return; }
        if (value.v !== 1) { throw new Error('截图接口版本不兼容'); }
        if (value.status === 'done') {
          if (!/^data:image\/jpeg;base64,[A-Za-z0-9+/=]+$/.test(value.dataUrl) || value.dataUrl.length > 1400000) { throw new Error('截图数据无效'); }
          let path = (getContext(this) as common.UIAbilityContext).cacheDir + '/gui-preview-' + String(Date.now()) + '.jpg';
          let bytes = new util.Base64Helper().decodeSync(value.dataUrl.split(',')[1]);
          try {
            let file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
            try { fs.writeSync(file.fd, bytes.slice().buffer); } finally { fs.closeSync(file); }
          } catch (error) { try { fs.unlinkSync(path); } catch (ignored) {} throw error as Error; }
          this.preview = 'file://' + path; this.image = value.dataUrl; this.shotWidth = value.width; this.shotHeight = value.height;
          this.status = '已截取本应用界面，尚未上传。' + String(value.width) + '×' + String(value.height); return;
        }
        if (value.status !== 'running') { throw new Error('截图失败或已失效，请在前台重试'); }
        await new Promise<void>((resolve): void => { setTimeout(resolve, 80); });
      }
      throw new Error('截图超时');
    } catch (error) { if (generation === this.generation) { this.status = (error as Error).message; } }
    finally { if (this.operation) { await bridge.callMethod('guiScreenshotCancel', this.operation); this.operation = ''; } this.busy = false; }
  }
  private async plan(): Promise<void> {
    if (this.busy || !this.image || !this.task.trim()) { return; }
    this.busy = true; let generation = this.generation;
    try {
      AiServiceAccess.requireAllowed(); let config = this.client.config();
      if (!await PrivacyConsentDialog.show(this.getUIContext())) { return; }
      let choice = await promptAction.showDialog({ title: '发送界面截图进行规划？',
        message: '将发送预览中的1张本应用截图和任务：' + this.task + '\n至 ' + config.baseUrl + '，模型 ' + config.model + '。截图可能包含私人内容。仅生成建议，不执行动作；结果只在此页显示。',
        buttons: [{ text: '取消', color: '#65718B' }, { text: '发送截图', color: '#276EF1' }] });
      if (choice.index !== 1 || generation !== this.generation) { return; }
      let result = await this.client.plan(this.task, this.image, this.shotWidth, this.shotHeight, config, (): boolean => generation === this.generation);
      if (generation === this.generation) { this.status = '模型建议（未执行，非任务成功回执）：\n' + JSON.stringify(result, null, 2); }
    } catch (error) { if (generation === this.generation) { this.status = (error as Error).message; } }
    finally { this.busy = false; }
  }
  onPageHide(): void { this.clear(); }
  aboutToDisappear(): void { this.clear(); }
  onBackPress(): boolean { this.clear(); router.back(); return true; }
  build() {
    Scroll() {
      Column({ space: 14 }) {
        Button('返回').onClick(() => { this.clear(); router.back(); })
        Text('本应用截图与单步规划').fontSize(21).fontWeight(FontWeight.Bold)
        Text('此处截取ClawMate当前界面，确认后才发送给Decider，只生成建议。跨应用手动控制使用下方独立入口；模型动作执行与规划循环尚未接入。').fontSize(14)
        TextInput({ text: this.task, placeholder: '描述界面任务（最多2000字）' }).maxLength(2000).enabled(!this.busy).onChange((value: string): void => { this.task = value; })
        Button('截取本应用界面').enabled(!this.busy).onClick(() => { this.capture(); })
        if (this.preview) { Image(this.preview).width('100%').height(280).objectFit(ImageFit.Contain).accessibilityText('待确认上传的本应用截图').onError((): void => { this.clear(); this.status = '截图预览失败，请重新截图；未发送图片。'; }) }
        Button('确认截图并请求单步规划').enabled(!this.busy && this.preview.length > 0 && this.task.trim().length > 0).onClick(() => { this.plan(); })
        Button(this.busy ? '取消并清除截图' : '清除截图').onClick(() => { this.clear(); this.status = '已取消并清除本页截图，未执行动作。'; })
        Text(this.status).fontSize(14).width('100%').copyOption(CopyOptions.InApp)
        Text('跨应用手动控制').fontSize(18).fontWeight(FontWeight.Bold)
        Text('选择一个应用，按次开启120秒/20次动作会话。截图仅本机预览；手动点按、上下滑动和返回逐次确认。不自动执行模型建议，不支持文字输入。').fontSize(14)
        Button('无障碍权限说明').enabled(!this.busy).onClick(() => { this.control('guiControlSettings'); })
        Button('选择应用并开启手动控制').enabled(!this.busy).onClick(() => { this.control('guiControlOpen'); })
        Button('停止跨应用控制').onClick(() => { this.control('guiControlStop'); })
        Button('刷新控制状态').onClick(() => { this.control('guiControlStatus'); })
        Text(this.controlStatus).fontSize(13).width('100%')
      }.padding(24).width('100%').backgroundColor(AppTheme.background('#F4F8FF'))
    }.width('100%').height('100%')
  }
}
