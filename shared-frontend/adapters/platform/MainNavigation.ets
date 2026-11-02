import { AppTheme } from '../utils/AppTheme';
import { MAIN_TAB_ITEMS, MainTabItem } from '../pages/main/MainTabConfig';
import { TAB_BAR_HEIGHT, TAB_BAR_BOTTOM_MARGIN, TAB_BAR_SIDE_MARGIN } from './NavigationStyle';

// Preserve the source capsule geometry with standard ArkUI. HDS system material
// is not available on Android; resource colors and component blur are used here.
@Component
export struct MainNavigation {
  @Link selectedIndex: number;
  @Prop pageBackgroundColor: string = '#FFFFFF';
  @Prop showBar: boolean = true;
  @BuilderParam contentBuilder: () => void = this.EmptyContent;
  @State availableWidth: number = 360;
  @Builder EmptyContent() {}

  private barWidth(): number {
    let width = Math.max(0, this.availableWidth - TAB_BAR_SIDE_MARGIN * 2);
    return Math.min(width, this.availableWidth >= 840 ? 560 : this.availableWidth >= 600 ? 480 : width);
  }
  @Builder private Item(item: MainTabItem) {
    Column({ space: 2 }) {
      Image($r('app.media.' + item.icon)).width(24).height(24)
        .fillColor(AppTheme.text(this.selectedIndex === item.index ? '#5B5CE2' : '#747B87'))
      Text(item.label).fontSize(12).fontWeight(FontWeight.Medium).maxLines(1)
        .fontColor(AppTheme.text(this.selectedIndex === item.index ? '#5B5CE2' : '#747B87'))
    }.layoutWeight(1).height(TAB_BAR_HEIGHT).justifyContent(FlexAlign.Center)
      .padding({ left: 2, right: 2, top: 6, bottom: 6 })
      .accessibilityGroup(true).accessibilityText(item.label)
      .onClick((): void => { this.selectedIndex = item.index; })
  }
  build() {
    Stack({ alignContent: Alignment.Bottom }) {
      Tabs({ index: this.selectedIndex, barPosition: BarPosition.End }) { this.contentBuilder() }
        .barHeight(0).barMode(BarMode.Fixed)
        .onChange((index: number): void => { this.selectedIndex = index; })
        .width('100%').height('100%')
      if (this.showBar) {
        Row() {
          ForEach(MAIN_TAB_ITEMS, (item: MainTabItem) => { this.Item(item) }, (item: MainTabItem): string => item.label)
        }.width(this.barWidth()).height(TAB_BAR_HEIGHT)
          .backgroundColor(AppTheme.background('#B3FFFFFF'))
          .backgroundBlurStyle(BlurStyle.COMPONENT_REGULAR)
          .border({ width: 1, color: AppTheme.border('#66FFFFFF') })
          .borderRadius(TAB_BAR_HEIGHT / 2)
          .shadow({ radius: 20, color: AppTheme.shadow('#22000000'), offsetY: 4 })
          .margin({ bottom: TAB_BAR_BOTTOM_MARGIN })
      }
    }.width('100%').height('100%')
      .backgroundColor(AppTheme.pageBackground(this.pageBackgroundColor))
      .onAreaChange((_old: Area, current: Area): void => { this.availableWidth = parseFloat(current.width.toString()); })
  }
}
