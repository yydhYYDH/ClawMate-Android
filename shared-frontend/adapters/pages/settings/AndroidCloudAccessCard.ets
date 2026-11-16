import { AppTheme } from '../../utils/AppTheme';
import { SettingsStore } from '../../platform/SettingsStore';
import { PrivacyConsentDialog } from '../../components/PrivacyConsentDialog';
import { PrivacyConsent } from '../../utils/PrivacyConsent';

@Component
export struct AndroidCloudAccessCard {
  @Link sessionActive: boolean;
  build() {
    Column({ space: 10 }) {
      Text('安卓云服务授权').fontSize(18).fontWeight(FontWeight.Bold).fontColor(AppTheme.text('#172033'))
      Text('授权后可使用你自行配置的云端模型服务。')
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
