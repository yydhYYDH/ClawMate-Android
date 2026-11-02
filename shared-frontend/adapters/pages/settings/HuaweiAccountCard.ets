import { AppTheme } from '../../utils/AppTheme';
import { SettingsStore } from '../../platform/SettingsStore';
import { PrivacyConsentDialog } from '../../components/PrivacyConsentDialog';
import { PrivacyConsent } from '../../utils/PrivacyConsent';

@Component
export struct HuaweiAccountCard {
  @Link sessionActive: boolean;
  build() {
    Column({ space: 10 }) {
      Text('安卓云服务授权').fontSize(18).fontWeight(FontWeight.Bold).fontColor(AppTheme.text('#172033'))
      Text('华为账号尚未接入。授权后可使用你在下方配置的模型服务；不会创建或登录任何账号。')
        .fontSize(13).fontColor(AppTheme.text('#65718B'))
      Button(this.sessionActive ? '撤回云服务授权' : '授权使用自行配置的云服务')
        .onClick(async (): Promise<void> => {
          if (this.sessionActive) { this.sessionActive = false; await PrivacyConsent.choose(false); }
          else { this.sessionActive = await PrivacyConsentDialog.show(this.getUIContext()); }
          SettingsStore.save();
        })
    }.padding(18).width('100%').backgroundColor(AppTheme.background('#FFFFFF')).borderRadius(18)
  }
}
