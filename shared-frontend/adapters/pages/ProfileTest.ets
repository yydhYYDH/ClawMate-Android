import { AppHeader } from '../components/AppHeader';
import { router } from '@kit.ArkUI';
@Entry
@Component
struct ProfileTest {
  build() {
    Column({ space: 16 }) {
      AppHeader({ title: '原生性能分析', showBackButton: true, onBackClick: (): void => { router.back(); } })
      Text('安卓尚未接入 MNN 性能分析').fontSize(20).padding(20)
      Text('没有运行基准测试，也没有生成耗时或内存报告。需要接入原生推理模块后才能使用。').padding(20)
      Button('返回').onClick(() => { router.back(); })
    }.width('100%').height('100%')
  }
}
