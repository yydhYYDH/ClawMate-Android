import { common } from '@kit.AbilityKit';
import { router } from '@kit.ArkUI';
import { AppHeader, APP_HEADER_CONTENT_GAP } from '../../components/AppHeader';
import { FrostedPage } from '../../components/FrostedPage';
import { DataManagerFacade } from '../../datamanager/DataManagerFacade';
import { AvatarGenerationMode, AvatarVersionViewModel } from '../../datamanager/avatar/AvatarTypes';
import { nowIsoLocal } from '../../datamanager/DataManagerUtils';
import { AndroidAvatar } from '../../platform/AndroidAvatar';
import { AiServiceAccess } from '../../utils/AiServiceAccess';
import { PrivacyConsentDialog } from '../../components/PrivacyConsentDialog';
import { GalleryPhotoPicker } from '../../utils/gallery/GalleryPhotoPicker';

@Entry
@Component
struct AvatarManagementPage {
  @State private reference: string = '';
  @State private versions: AvatarVersionViewModel[] = [];
  @State private busy: boolean = false;
  @State private status: string = '';
  private facade: DataManagerFacade | null = null;
  private scroller: Scroller = new Scroller();

  aboutToAppear(): void {
    try {
      this.facade = new DataManagerFacade((getContext(this) as common.UIAbilityContext).filesDir);
      this.reload();
    } catch (error) { this.status = (error as Error).message; }
  }

  onBackPress(): boolean { router.back(); return true; }

  private reload(): void {
    if (!this.facade) { return; }
    this.reference = this.facade.loadHomeViewModel().avatarReferencePhotoUri;
    this.versions = this.facade.listAvatarVersions();
  }

  private async pickReference(): Promise<void> {
    if (!this.facade || this.busy) { return; }
    this.busy = true;
    try {
      const images = await new GalleryPhotoPicker().selectImageUris(1);
      if (images.length === 0) { return; }
      const result = await this.facade.saveAvatarReferencePhoto(images[0]);
      this.reference = result.avatarReferencePhotoUri;
      this.status = '参考照片已保存';
    } catch (error) { this.status = (error as Error).message; }
    finally { this.busy = false; }
  }

  private async generate(mode: AvatarGenerationMode): Promise<void> {
    if (!this.facade || this.busy) { return; }
    this.busy = true;
    this.status = '正在生成…';
    try {
      await AndroidAvatar.generate(this.facade, mode, mode === 'image_to_image' ? this.reference : '', async (): Promise<void> => {
        AiServiceAccess.requireAllowed();
        if (!await PrivacyConsentDialog.show(this.getUIContext())) { throw new Error('未同意云端处理，未生成分身'); }
      }, (): string => nowIsoLocal());
      this.reload();
      this.status = '已生成，保存在本机';
    } catch (error) { this.status = (error as Error).message; }
    finally { this.busy = false; }
  }

  @Builder private Header() {
    AppHeader({ title: '数字分身', showBackButton: true, onBackClick: (): void => { router.back(); } })
  }

  @Builder private Content() {
    Scroll(this.scroller) {
      Column({ space: 14 }) {
        Text('使用「模型与智能体」中配置的分身图片服务。')
          .fontSize(14).fontColor($r('app.color.ui_text_secondary')).width('100%')
        if (this.reference) {
          Image(this.reference).width(88).height(88).objectFit(ImageFit.Cover).borderRadius(12)
            .accessibilityText('已保存的参考照片')
        }
        Button(this.reference ? '更换参考照片' : '选择参考照片').enabled(!this.busy)
          .onClick((): void => { this.pickReference(); })
        Button('使用参考照片生成').enabled(!this.busy && this.reference.length > 0)
          .onClick((): void => { this.generate('image_to_image'); })
        Button('使用个人画像生成').enabled(!this.busy)
          .onClick((): void => { this.generate('text_to_image'); })
        if (this.status) { Text(this.status).fontSize(14).width('100%').fontColor($r('app.color.ui_text_secondary')) }
        Text('生成历史').fontSize(18).fontColor($r('app.color.ui_text_primary')).width('100%')
        if (this.versions.length === 0) { Text('暂无生成记录').fontSize(14).fontColor($r('app.color.ui_text_secondary')) }
        ForEach(this.versions, (version: AvatarVersionViewModel) => {
          Row({ space: 14 }) {
            Image(version.imageUri).width(84).height(84).objectFit(ImageFit.Cover).borderRadius(12)
            Column({ space: 6 }) {
              Text(version.mode === 'image_to_image' ? '参考照片生成' : '个人画像生成')
                .fontColor($r('app.color.ui_text_primary'))
              Text(version.createdAt).fontSize(12).fontColor($r('app.color.ui_text_secondary'))
            }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          }.width('100%').padding(12).backgroundColor($r('app.color.ui_surface')).borderRadius(16)
        }, (version: AvatarVersionViewModel): string => version.versionId)
      }.width('92%').constraintSize({ maxWidth: 720 }).alignItems(HorizontalAlign.Start)
        .padding({ top: APP_HEADER_CONTENT_GAP, bottom: 24 })
    }.width('100%').height('100%').align(Alignment.Top)
  }

  build() {
    Column() {
      FrostedPage({ pageBackgroundColor: '#F4F8FF', scrollers: [this.scroller],
        headerBuilder: (): void => { this.Header(); }, contentBuilder: (): void => { this.Content(); } })
    }.width('100%').height('100%')
  }
}
