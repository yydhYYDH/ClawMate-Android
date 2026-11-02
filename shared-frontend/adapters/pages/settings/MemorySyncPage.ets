import { common } from '@kit.AbilityKit';
import { router, promptAction } from '@kit.ArkUI';
import { DataManagerFacade } from '../../datamanager/DataManagerFacade';
import { DataPipelineModelClient } from '../../datamanager/DataPipelineModelClient';
import { DataManagerModelConfig, DataManagerProgress } from '../../datamanager/DataManagerTypes';
import { IndexService } from '../../datamanager/IndexService';
import { CloudModelClient } from '../../utils/CloudModelClient';
import { AiServiceAccess } from '../../utils/AiServiceAccess';
import { PrivacyConsentDialog } from '../../components/PrivacyConsentDialog';
import { AppTheme } from '../../utils/AppTheme';

@Entry
@Component
struct MemorySyncPage {
  @State busy: boolean = false;
  @State status: string = '尚未执行同步';
  @State progress: number = 0;
  private active: boolean = false;
  private generation: number = 0;
  private client: CloudModelClient = new CloudModelClient();
  private facade: DataManagerFacade | null = null;
  private ensureActive(): void { if (!this.active) { throw new Error('用户取消记忆同步；已提交的批次保留，下次可继续'); } }
  private config(): DataManagerModelConfig {
    let baseUrl = (AppStorage.get<string>('cloudPlannerBaseUrl') || '').trim();
    let model = (AppStorage.get<string>('cloudPlannerModel') || '').trim();
    if (!baseUrl.startsWith('https://') || model.length === 0) { throw new Error('请先在“模型与智能体”保存 HTTPS 地址和模型名称'); }
    return { baseUrl: baseUrl, model: model, apiKey: AppStorage.get<string>('cloudPlannerApiKey') ||
      AppStorage.get<string>('cloudApiKey') || '', enabled: true, ensureActive: (): void => { this.ensureActive(); } };
  }
  private async sync(): Promise<void> {
    if (this.busy || AppStorage.get<boolean>('androidMemorySyncBusy') === true) { return; }
    this.busy = true; AppStorage.setOrCreate('androidMemorySyncBusy', true);
    let generation = this.generation;
    try {
      AiServiceAccess.requireAllowed(); let config = this.config();
      if (!await PrivacyConsentDialog.show(this.getUIContext())) { return; }
      let choice = await promptAction.showDialog({ title: '同步聊天长期记忆？',
        message: '将向 ' + config.baseUrl + ' 的模型 ' + config.model + ' 发送近7天未处理的云端及本地聊天文字、有限上下文，以及待整理日志。排除工具记录和推理。提取结果写入本机长期记忆；删除聊天不会删除已保存记忆。可取消并保留已完成批次。',
        buttons: [{ text: '取消', color: '#65718B' }, { text: '确认同步', color: '#4F68FF' }] });
      if (choice.index !== 1) { return; }
      if (generation !== this.generation) { throw new Error('页面已关闭，同步未执行'); }
      let latest = this.config();
      if (latest.baseUrl !== config.baseUrl || latest.model !== config.model || latest.apiKey !== config.apiKey) { throw new Error('模型配置发生变化，请重新确认'); }
      this.active = true; this.progress = 0; this.status = '正在检查待同步记录';
      let context = getContext(this) as common.UIAbilityContext;
      let facade = new DataManagerFacade(context.filesDir, new DataPipelineModelClient(this.client));
      this.facade = facade;
      let result = await facade.refresh(config, (value: DataManagerProgress): void => {
        this.ensureActive(); this.status = value.stage + '：' + value.message; this.progress = value.percent;
      });
      this.ensureActive(); this.status = result.message + '\n索引事件：' + result.eventCount.toString(); this.progress = 100;
    } catch (error) { this.status = (error as Error).message; }
    finally { this.active = false; this.busy = false; AppStorage.setOrCreate('androidMemorySyncBusy', false); }
  }
  private async rebuildLocal(): Promise<void> {
    if (this.busy || AppStorage.get<boolean>('androidMemorySyncBusy') === true) { return; }
    this.busy = true; AppStorage.setOrCreate('androidMemorySyncBusy', true);
    try {
      let context = getContext(this) as common.UIAbilityContext;
      let facade = new DataManagerFacade(context.filesDir); facade.ensureReady();
      if (facade.storage.findFiles(facade.paths.outputsRoot, '.md.state.json').length === 0) {
        throw new Error('尚无已整理的长期记忆，请先同步；此操作不会调用模型或把原始聊天当作已整理记录');
      }
      let service = new IndexService(facade.paths, facade.storage, new DataPipelineModelClient());
      let result = await service.build({ baseUrl: '', apiKey: '', enabled: false });
      this.status = '本机索引已重建：' + result.eventCount.toString() + ' 条事件；未调用云服务';
    } catch (error) { this.status = (error as Error).message; }
    finally { this.busy = false; AppStorage.setOrCreate('androidMemorySyncBusy', false); }
  }
  private cancel(): void { this.generation += 1; this.active = false; this.client.cancelActiveRequests(); }
  onPageHide(): void { this.cancel(); }
  aboutToDisappear(): void { this.cancel(); }
  onBackPress(): boolean { this.cancel(); router.back(); return true; }
  build() {
    Column({ space: 18 }) {
      Row() {
        Button('返回').height(36).fontSize(14).backgroundColor(AppTheme.background('#EEF1FF'))
          .fontColor(AppTheme.text('#276EF1')).onClick(() => { this.cancel(); router.back(); })
        Text('聊天长期记忆').fontSize(20).fontWeight(FontWeight.Bold).layoutWeight(1).textAlign(TextAlign.Center).fontColor(AppTheme.text('#11182F'))
      }.width('100%')
      Text('同步使用共享版自己的聊天、日志和索引。不会读取旧版应用的数据。').fontSize(14).fontColor(AppTheme.text('#65718B'))
      Button('同步聊天与采集记忆').height(40).fontSize(14).backgroundColor('#4F68FF').fontColor(Color.White)
        .enabled(!this.busy).onClick(() => { this.sync(); })
      Button('仅重建本机索引（无网络）').height(40).fontSize(14)
        .backgroundColor(AppTheme.background('#EEF1FF')).fontColor(AppTheme.text('#276EF1'))
        .enabled(!this.busy).onClick(() => { this.rebuildLocal(); })
      if (this.busy) {
        Progress({ value: this.progress, total: 100, type: ProgressType.Linear }).width('100%')
        Button('取消同步').onClick(() => { this.cancel(); })
      }
      Text(this.status).fontSize(14).fontColor(AppTheme.text('#11182F')).width('100%').copyOption(CopyOptions.InApp)
    }.width('100%').height('100%').padding(24)
      .backgroundColor(AppTheme.background('#F4F8FF'))
  }
}
