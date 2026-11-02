import { AppHeader } from '../components/AppHeader';
import { router } from '@kit.ArkUI';
@Entry
@Component
struct LogView {
  @StorageProp('cloudAgentLogs') cloudLogs: string = '';
  @StorageProp('galleryLogs') galleryLogs: string = '';
  build() {
    Column() {
      AppHeader({ title: '运行日志', showBackButton: true, onBackClick: (): void => { router.back(); } })
      Scroll() {
        Text(this.cloudLogs.length + this.galleryLogs.length > 0 ? this.cloudLogs + '\n' + this.galleryLogs : '暂无本次运行日志；Debug 默认关闭。MNN 原生日志尚未接入。')
          .fontSize(13).copyOption(CopyOptions.InApp).padding(20).width('100%')
      }.layoutWeight(1)
    }.width('100%').height('100%')
  }
}
