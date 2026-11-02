import { AppHeader } from '../../components/AppHeader';
import { router } from '@kit.ArkUI';
import Bridge from '@arkui-x.bridge';
import { SettingsStore } from '../../platform/SettingsStore';
import { showToastSafely } from '../../utils/PromptSafe';
@Entry
@Component
struct DesktopCompanionPage {
  @StorageLink('desktopCompanionDownloadUrl') downloadUrl: string = '';
  build() {
    Column({ space: 16 }) {
      AppHeader({ title: '桌面端 App', showBackButton: true, onBackClick: (): void => { router.back(); } })
      Text('ClawMate 桌面端').fontSize(22).fontWeight(FontWeight.Bold)
      Text('填写你信任的桌面端 HTTPS 下载地址。没有内置下载链接或提取口令。').fontSize(14)
      TextInput({ text: this.downloadUrl, placeholder: 'HTTPS 下载地址' })
        .onChange((value: string): void => { this.downloadUrl = value; SettingsStore.scheduleSave(); })
      Button('用浏览器打开').onClick(async (): Promise<void> => {
        if (!/^https:\/\/[^\s]+$/.test(this.downloadUrl.trim())) { showToastSafely('请填写有效的 HTTPS 下载地址'); return; }
        try { await Bridge.createBridge('ClawMatePlatform').callMethod('openUrl', this.downloadUrl.trim()); }
        catch (error) { showToastSafely('无法打开浏览器，请检查链接'); }
      })
    }.padding(20).width('100%').height('100%')
  }
}
