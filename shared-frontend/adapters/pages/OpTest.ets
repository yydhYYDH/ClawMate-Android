import { AppHeader } from '../components/AppHeader';
import { router } from '@kit.ArkUI';
@Entry
@Component
struct OpTest {
  build() {
    Column({ space: 16 }) {
      AppHeader({ title: '原生推理诊断', showBackButton: true, onBackClick: (): void => { router.back(); } })
      Text('安卓尚未链接 MNN 推理与算子诊断库').fontSize(20).padding(20)
      Text('本页未运行任何推理或性能测试。需要集成对应 CPU 架构的原生库后才能使用。').padding(20)
      Button('返回').onClick(() => { router.back(); })
    }.width('100%').height('100%')
  }
}
