import { AppTheme } from '../utils/AppTheme';
import { APP_HEADER_HEIGHT } from './AppHeader';

@Component
export struct FrostedPage {
  @Prop pageBackgroundColor: string = '#FFFFFF';
  scrollers: Scroller[] = [];
  @BuilderParam headerBuilder: () => void = this.EmptyContent;
  @BuilderParam contentBuilder: () => void = this.EmptyContent;

  @Builder EmptyContent() {}

  @Builder Title() {
    Column() {
      this.headerBuilder()
    }.width('100%').height(APP_HEADER_HEIGHT).backgroundColor(AppTheme.pageBackground(this.pageBackgroundColor))
  }

  build() {
    Navigation() {
      this.contentBuilder()
    }
    .title({ builder: (): void => { this.Title(); }, height: APP_HEADER_HEIGHT }, {
      barStyle: BarStyle.STANDARD,
      backgroundColor: AppTheme.pageBackground(this.pageBackgroundColor),
      backgroundBlurStyle: BlurStyle.COMPONENT_THIN
    })
    .hideBackButton(true)
    .hideToolBar(true)
    .mode(NavigationMode.Stack)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP])
    .width('100%')
    .height('100%')
    .backgroundColor(AppTheme.pageBackground(this.pageBackgroundColor))
    .clip(true)
  }
}
