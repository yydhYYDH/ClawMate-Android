import { AppHeader } from '../../components/AppHeader';
import { FrostedPage } from '../../components/FrostedPage';
import { router } from '@kit.ArkUI';
@Entry
@Component
struct SelfHdcTestPage {
  @Builder Header() { AppHeader({ title: '安卓自动控制', showBackButton: true, onBackClick: (): void => { router.back(); } }) }
  @Builder Content() {
    Column({ space: 16 }) {
      Text('安卓自动控制适配尚未接入').fontSize(20).fontWeight(FontWeight.Bold)
      Text('鸿蒙 HDC 协议无法直接用于安卓。当前没有配对、授权、读取其他应用或执行自动控制。')
      Text('可以返回任务页浏览、编辑和保存工作流；需要后续接入 Android 控制适配器后才能执行。')
      Button('返回任务和设置').onClick(() => { router.back(); })
    }.width('100%').padding(24)
  }
  build() {
    Column() {
      FrostedPage({ headerBuilder: (): void => { this.Header(); }, contentBuilder: (): void => { this.Content(); } })
    }.width('100%').height('100%')
  }
}
