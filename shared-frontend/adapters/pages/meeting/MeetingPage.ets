import { common } from '@kit.AbilityKit';
import { router, promptAction } from '@kit.ArkUI';
import { AppTheme } from '../../utils/AppTheme';
import { MeetingApi } from '../../platform/MeetingApi';
import { MeetingImport } from '../../platform/MeetingImport';
import { MeetingRecord, MeetingDocument, MeetingHistory, MeetingHistoryItem, MeetingCapture, MeetingModelGroup,
  MeetingModels, MeetingTask, MeetingVersions, MeetingVersion, MeetingSegment, MeetingEvent,
  MeetingHandoffEnvelope, MeetingHandoffPayload, emptyMeeting } from '../../platform/MeetingTypes';

interface CreatedTemplate { id: string; }
interface SpeakerEdit { id: string; name: string; }

@Entry
@Component
struct MeetingPage {
  @State message: string = '正在准备本机会议引擎…';
  @State ready: boolean = false;
  @State busy: boolean = false;
  @State query: string = '';
  @State view: string = 'inbox';
  @State history: MeetingHistoryItem[] = [];
  @State meeting: MeetingRecord = emptyMeeting();
  @State title: string = '';
  @State titleDirty: boolean = false;
  @State capture: MeetingCapture = { streaming: false, recording: false, paused: false, audio_seconds: 0, phase: 'idle', finals: [] };
  @State partial: string = '';
  @State models: MeetingModelGroup[] = [];
  @State tasks: MeetingTask[] = [];
  @State versions: MeetingVersion[] = [];
  @State profile: string = 'local-sensevoice';
  @State autoRefine: boolean = false;
  @State editing: boolean = false;
  @State edits: MeetingSegment[] = [];
  @State speakers: SpeakerEdit[] = [];
  @State importPacket: string = '';
  @State importPreview: string = '';
  private editRevision: number = 0;
  private editHash: string = '';
  private api: MeetingApi = new MeetingApi();
  private active: boolean = false;
  private timer: number = -1;
  private sequence: number = 0;
  private loaded: boolean = false;
  private tickCount: number = 0;
  private initialized: boolean = false;
  private polling: boolean = false;

  aboutToAppear(): void {
    this.active = true;
    this.api.initialize().then((): void => { this.initialized = true; this.api.attach().then((): void => { this.tick(); }); })
      .catch((error: Error): void => { this.message = error.message; });
  }
  aboutToDisappear(): void { this.active = false; if (this.timer >= 0) { clearTimeout(this.timer); } this.api.close(); }
  onPageHide(): void { this.active = false; if (this.timer >= 0) { clearTimeout(this.timer); } }
  onPageShow(): void { this.active = true; if (this.initialized) { this.api.attach().then((): void => { this.tick(); }); } }
  private async tick(): Promise<void> {
    if (!this.active || this.polling) { return; }
    this.polling = true;
    try {
      let state = await this.api.poll(this.sequence);
      this.sequence = state.sequence; this.capture = state.capture;
      this.ready = state.engine.phase === 'ready';
      if (this.ready && this.capture.streaming && this.capture.mid && this.meeting.id.length === 0) {
        await this.open(this.capture.mid);
      }
      if (state.engine.error) { this.message = state.engine.error; }
      state.events.forEach((raw: string): void => {
        let event = JSON.parse(raw) as MeetingEvent;
        if (event.type === 'partial') { this.partial = event.text || ''; }
        if (event.type === 'final' || event.type === 'capture_closed') { this.partial = ''; }
      });
      if (state.overflowed) { this.partial = ''; }
      if (this.ready && (!this.loaded || this.tickCount % 4 === 0)) {
        await this.refreshModels();
        if (!this.loaded) { await this.refreshHistory(); this.loaded = true; this.message = '会议数据保存在本机。先录音或导入音频，再保存为会议。'; }
        if (this.meeting.id.length > 0) { await this.refreshMeeting(); }
      }
      this.tickCount += 1;
    } catch (error) { if (this.active) { this.message = (error as Error).message; } }
    this.polling = false;
    if (this.active) { this.timer = setTimeout((): void => { this.tick(); }, 500); }
  }
  private async run(work: () => Promise<void>): Promise<void> {
    if (this.busy) { return; }
    this.busy = true;
    try { await work(); } catch (error) { this.message = (error as Error).message; }
    finally { this.busy = false; }
  }
  private async refreshModels(): Promise<void> {
    let data = await this.api.request<MeetingModels>('models_status'); this.models = data.groups;
    if (data.state === 'downloading' || data.state === 'failed') { this.message = data.message; }
  }
  private async refreshHistory(): Promise<void> {
    let data = await this.api.request<MeetingHistory>('history', { query: { view: this.view, q: this.query, page_size: '30' } });
    this.history = data.items;
  }
  private async refreshMeeting(): Promise<void> {
    let id = this.meeting.id;
    let value = await this.api.request<MeetingRecord>('meeting', { id: id });
    if (id !== this.meeting.id) { return; }
    this.meeting = value;
    if (!this.titleDirty) { this.title = value.title; }
    this.tasks = await this.api.request<MeetingTask[]>('tasks', { query: { meeting_id: id } });
    if (value.error) { this.message = value.error; }
  }
  private async open(id: string): Promise<void> {
    if (this.editing || this.titleDirty) { throw new Error('请先保存或放弃当前修改'); }
    if (this.capture.streaming && this.capture.mid !== id) { throw new Error('请先结束当前录音'); }
    await this.api.stopPlayback();
    this.meeting = await this.api.request<MeetingRecord>('meeting', { id: id });
    this.title = this.meeting.title; this.titleDirty = false; this.versions = []; this.partial = '';
    await this.refreshMeeting();
  }
  private canRecord(): boolean {
    return this.ready && !this.busy && !this.capture.streaming && !this.editing &&
      this.models.some((item: MeetingModelGroup): boolean => item.id === 'basic' && item.ready) &&
      this.models.some((item: MeetingModelGroup): boolean => item.id === 'speakers' && item.ready);
  }
  private async draft(source: string): Promise<void> {
    if (this.editing || this.titleDirty) { throw new Error('请先保存或放弃当前修改'); }
    let record = await this.api.request<MeetingRecord>('create_draft', { body: { source: source } });
    this.meeting = record; this.title = record.title; this.titleDirty = false; this.versions = [];
    let template = await this.api.request<CreatedTemplate>('create_template', { body: {
      name: 'ClawMate 本机会议', refinement_profile_id: this.profile, auto_refine: this.autoRefine, summary_instructions: '' } });
    try { await this.api.request<Object>('apply_template', { id: record.id, body: { template_id: template.id } }); }
    finally { await this.api.request<Object>('delete_template', { id: template.id }); }
    if (source === 'recording') { await this.api.record(record.id); this.message = '录音由前台服务保持；离开页面仍会继续，可从通知结束。'; }
    else { await this.api.chooseAudio(record.id, this.profile); this.message = '音频已提交，可在任务区查看处理状态。'; }
    await this.refreshHistory();
  }
  private async saveTitle(): Promise<void> {
    if (this.title.trim().length === 0) { throw new Error('请填写会议名称'); }
    await this.api.request<Object>(this.meeting.draft ? 'promote' : 'update_meeting',
      { id: this.meeting.id, body: { title: this.title.trim(), tags: this.meeting.tags } });
    this.titleDirty = false; await this.refreshMeeting(); await this.refreshHistory(); this.message = '会议已保存';
  }
  private beginEdit(): void {
    this.editing = true; this.editRevision = this.meeting.document.revision; this.editHash = this.meeting.document.source_hash;
    this.edits = this.meeting.document.segments.map((row: MeetingSegment): MeetingSegment => ({ text: row.text, speaker: row.speaker }));
    let names = this.meeting.document.speaker_names;
    let ids = Array.from(new Set<string>(this.edits.map((row: MeetingSegment): string => row.speaker)));
    this.speakers = ids.map((id: string): SpeakerEdit => ({ id: id, name: names[id] || id }));
  }
  private async saveEdit(): Promise<void> {
    let names: Record<string, string> = {};
    this.speakers.forEach((row: SpeakerEdit): void => { names[row.id] = row.name; });
    await this.api.request<MeetingDocument>('save_document', { id: this.meeting.id, body: {
      base_revision: this.editRevision, source_hash: this.editHash, segments: this.edits, speaker_names: names } });
    this.editing = false; await this.refreshMeeting(); this.message = '人工稿已保存为新版本';
  }
  private async showVersions(): Promise<void> {
    this.versions = (await this.api.request<MeetingVersions>('versions', { id: this.meeting.id })).versions.slice(0, 20);
  }
  private async restore(revision: number): Promise<void> {
    if (this.editing) { throw new Error('请先保存或放弃人工稿'); }
    await this.api.request<Object>('restore_version', { id: this.meeting.id, body: {
      revision: revision, base_revision: this.meeting.document.revision } });
    await this.refreshMeeting(); await this.showVersions();
  }
  private async saveCopy(): Promise<void> {
    if (this.editing) { throw new Error('请先保存人工稿'); }
    let raw = this.importPacket;
    if (raw.length === 0) {
      raw = JSON.stringify(await this.api.request<MeetingHandoffEnvelope>('handoff', { id: this.meeting.id, body: {
        base_revision: this.meeting.document.revision, source_hash: this.meeting.document.source_hash, selection: 'both' } }));
    }
    let context = getContext(this) as common.UIAbilityContext;
    let duplicate = await MeetingImport.save(context.filesDir, raw, this.api);
    this.message = duplicate ? '这一版本已保存，未重复导入' : '已保存到本机记录；原会议后续修改不会覆盖此副本';
    promptAction.showToast({ message: this.message });
  }
  private async chooseCopy(): Promise<void> {
    this.importPacket = await this.api.chooseHandoff();
    let packet = JSON.parse(this.importPacket) as MeetingHandoffEnvelope;
    let value = JSON.parse(packet.payload) as MeetingHandoffPayload;
    this.importPreview = value.title + ' · 正文版本 ' + String(value.source.revision) + '\n' + value.text;
  }
  private phase(): string {
    if (this.capture.phase === 'paused') { return '已暂停'; }
    if (this.capture.phase === 'preparing') { return '正在准备录音'; }
    if (this.capture.phase === 'finalizing') { return '录音已保存，正在完成精修'; }
    return this.capture.streaming ? '正在录音' : '未录音';
  }
  private modelLabel(id: string): string {
    if (id === 'basic') { return '基础转写'; } if (id === 'speakers') { return '说话人区分'; }
    if (id === 'firered') { return 'FireRed 逐段精修'; } if (id === 'summary') { return '本地纪要'; }
    return id === 'qwen_asr' ? 'Qwen 精修' : 'Paraformer';
  }
  @Builder private Section(title: string) {
    Text(title).fontSize(19).fontWeight(FontWeight.Bold).fontColor(AppTheme.text('#243A33')).width('100%').margin({ top: 16, bottom: 8 })
  }
  @Builder private Segment(row: MeetingSegment) {
    Column({ space: 5 }) {
      Row() {
        Text(this.meeting.document.speaker_names[row.speaker] || row.speaker || '未确认说话人').fontSize(12).fontColor(AppTheme.text('#64756B'))
        Blank()
        if (this.meeting.document.audio_id && !this.capture.streaming) {
          Button('回听').fontSize(12).onClick((): void => { this.run(async (): Promise<void> => {
            await this.api.play(this.meeting.id, this.meeting.document.audio_id || '', row.start || 0, row.end || 0);
          }); })
        }
      }.width('100%')
      Text(row.text).fontSize(16).fontColor(AppTheme.text('#243A33')).width('100%').copyOption(CopyOptions.LocalDevice)
      if (row.refinement_status === 'draft') { Text('草稿 · 等待逐段精修').fontSize(11).fontColor('#89723C') }
      if (row.refinement_status === 'failed') { Text('精修未完成，已保留草稿').fontSize(11).fontColor('#89723C') }
    }.padding(12).width('100%').backgroundColor(AppTheme.background('#F1F5F0')).borderRadius(12).margin({ bottom: 8 })
  }
  build() {
    Column() {
      Row() {
        Button('返回').onClick((): void => { router.back(); })
        Text('本机会议').fontSize(22).fontWeight(FontWeight.Bold).layoutWeight(1).margin({ left: 14 })
        Button('刷新').enabled(this.ready && !this.busy).onClick((): void => { this.run(async (): Promise<void> => { await this.refreshHistory(); }); })
      }.width('100%').padding(16)
      Scroll() {
        Column({ space: 8 }) {
          Text(this.message).fontSize(13).fontColor(AppTheme.text('#64756B')).width('100%')
          Row({ space: 10 }) {
            Button('开始新录音').enabled(this.canRecord()).onClick((): void => { this.run(async (): Promise<void> => { await this.draft('recording'); }); })
            Button('导入音频').enabled(this.canRecord()).onClick((): void => { this.run(async (): Promise<void> => { await this.draft('upload'); }); })
          }.width('100%')
          if (this.capture.streaming) {
            Text(this.phase() + ' · ' + String(Math.floor(this.capture.audio_seconds)) + ' 秒').fontColor('#367355')
            Row({ space: 10 }) {
              Button(this.capture.paused ? '继续录音' : '暂停录音').enabled(this.capture.recording && this.capture.phase !== 'finalizing')
                .onClick((): void => { this.run(async (): Promise<void> => { await this.api.capture(this.capture.paused ? 'resume' : 'pause'); }); })
              Button('结束录音').enabled(this.capture.phase !== 'finalizing').onClick((): void => { this.run(async (): Promise<void> => { await this.api.capture('stop'); }); })
            }
          }
          this.Section('本地模型')
          Select([{ value: '仅本地 SenseVoice' }, { value: 'FireRed 逐段精修' }, { value: 'Qwen 离线精修' }]).selected(0)
            .onSelect((index: number): void => { this.profile = ['local-sensevoice', 'local-firered', 'local-qwen'][index]; })
          Row({ space: 12 }) { Text('录音自动精修'); Toggle({ type: ToggleType.Switch, isOn: this.autoRefine }).onChange((value: boolean): void => { this.autoRefine = value; }) }
          ForEach(this.models, (group: MeetingModelGroup) => {
            Row() {
              Text(this.modelLabel(group.id) + (group.ready ? ' · 已准备' : ' · 未准备')).layoutWeight(1).fontSize(13)
              if (!group.ready && group.downloadable) { Button('下载').enabled(!this.busy).fontSize(12).onClick((): void => {
                this.run(async (): Promise<void> => { await this.api.request<Object>('models_download', { body: { groups: [group.id] } }); this.message = '正在下载所选模型'; });
              }) } else if (!group.ready) { Text('暂不提供应用内下载').fontSize(11) }
            }.width('100%')
          }, (group: MeetingModelGroup): string => JSON.stringify(group))
          Button('释放空闲模型').enabled(this.ready && !this.capture.streaming && !this.busy).onClick((): void => {
            this.run(async (): Promise<void> => { await this.api.request<Object>('release_models'); this.message = '空闲模型已释放，下一次使用时重新加载'; });
          })
          this.Section('会议库')
          Row({ space: 6 }) {
            Button('待整理').onClick((): void => { this.view = 'inbox'; this.run(async (): Promise<void> => { await this.refreshHistory(); }); })
            Button('已保存').onClick((): void => { this.view = 'meetings'; this.run(async (): Promise<void> => { await this.refreshHistory(); }); })
            Button('回收站').onClick((): void => { this.view = 'trash'; this.run(async (): Promise<void> => { await this.refreshHistory(); }); })
          }
          TextInput({ text: this.query, placeholder: '搜索名称、正文和纪要' }).onChange((value: string): void => { this.query = value; })
            .onSubmit((): void => { this.run(async (): Promise<void> => { await this.refreshHistory(); }); })
          ForEach(this.history, (item: MeetingHistoryItem) => {
            Button(item.title || '待整理录音').width('100%').onClick((): void => { this.run(async (): Promise<void> => { await this.open(item.id); }); })
          }, (item: MeetingHistoryItem): string => JSON.stringify(item))
          if (this.history.length === 0) { Text('暂无记录').fontSize(13) }
          if (this.meeting.id.length > 0) {
            this.Section('当前会议')
            TextInput({ text: this.title, placeholder: '会议名称' }).onChange((value: string): void => { this.title = value; this.titleDirty = true; })
            Row({ space: 8 }) {
              Button('保存会议').enabled(!this.busy && !this.capture.streaming).onClick((): void => { this.run(async (): Promise<void> => { await this.saveTitle(); }); })
              Button('放弃名称修改').onClick((): void => { this.titleDirty = false; this.title = this.meeting.title; })
              Button(this.view === 'trash' ? '恢复记录' : '移入回收站').enabled(!this.capture.streaming && !this.busy).onClick((): void => {
                this.run(async (): Promise<void> => { await this.api.request<Object>('history_batch', { body: { ids: [this.meeting.id], action: this.view === 'trash' ? 'restore' : 'trash' } }); await this.refreshHistory(); });
              })
            }
            if (this.meeting.document_audio?.recoverable) {
              Button('恢复已保存录音').onClick((): void => { this.run(async (): Promise<void> => {
                await this.api.request<Object>('recover_audio', { id: this.meeting.id, audio_id: this.meeting.active_audio_id || this.meeting.document.audio_id || '' }); await this.refreshMeeting();
              }); })
            }
            this.Section('正文 · 版本 ' + String(this.meeting.document.revision))
            if (this.capture.streaming && this.capture.mid === this.meeting.id) {
              ForEach(this.capture.finals, (row: MeetingSegment) => { this.Segment(row) })
              Text(this.partial).fontSize(15).fontColor('#7D8F82').width('100%')
            } else { ForEach(this.meeting.document.segments, (row: MeetingSegment) => { this.Segment(row) }) }
            Button('停止回听').onClick((): void => { this.api.stopPlayback(); })
            if (!this.editing) {
              Row({ space: 8 }) {
                Button('编辑正文与说话人').enabled(!this.capture.streaming && this.meeting.document.segments.length > 0).onClick((): void => { this.beginEdit(); })
                Button('历史版本').onClick((): void => { this.run(async (): Promise<void> => { await this.showVersions(); }); })
              }
            } else {
              ForEach(this.speakers, (speaker: SpeakerEdit, index: number) => {
                TextInput({ text: speaker.name, placeholder: speaker.id }).onChange((value: string): void => { this.speakers[index].name = value; })
              }, (speaker: SpeakerEdit): string => speaker.id)
              ForEach(this.edits, (row: MeetingSegment, index: number) => {
                TextArea({ text: row.text }).onChange((value: string): void => { this.edits[index].text = value; }).height(100)
              }, (row: MeetingSegment, index: number): string => String(index))
              Row({ space: 8 }) {
                Button('保存人工稿').onClick((): void => { this.run(async (): Promise<void> => { await this.saveEdit(); }); })
                Button('放弃人工稿').onClick((): void => { this.editing = false; })
              }
            }
            ForEach(this.versions, (version: MeetingVersion) => {
              Button('恢复版本 ' + String(version.revision) + ' · ' + version.origin).onClick((): void => { this.run(async (): Promise<void> => { await this.restore(version.revision); }); })
            }, (version: MeetingVersion): string => String(version.revision))
            this.Section('整理与导出')
            Row({ space: 8 }) {
              Button('重新精修').enabled(!this.capture.streaming && !this.busy && !!this.meeting.document.audio_id).onClick((): void => {
                this.run(async (): Promise<void> => { await this.api.request<Object>('process', { id: this.meeting.id, body: {
                  audio_id: this.meeting.document.audio_id, compare: false, offline_model: 'sensevoice', refinement_profile_id: this.profile } }); this.message = '精修任务已提交'; });
              })
              Button('生成本地纪要').enabled(!this.capture.streaming && !this.busy && this.meeting.document.transcript.length > 0).onClick((): void => {
                this.run(async (): Promise<void> => { await this.api.request<Object>('summary', { id: this.meeting.id }); this.message = '正在生成本地纪要'; });
              })
            }
            if (this.meeting.summary) { Text(this.meeting.summary.text).width('100%').copyOption(CopyOptions.LocalDevice) }
            ForEach(['export.md', 'export', 'handoff'], (format: string) => {
              Button(format === 'export.md' ? '导出 Markdown' : format === 'export' ? '导出会议 JSON' : '导出交接副本').onClick((): void => {
                this.run(async (): Promise<void> => { await this.api.export(this.meeting.id, format, this.meeting.document.revision, this.meeting.document.source_hash); this.message = '已导出到所选位置'; });
              })
            })
            Button('保存到本机记录').enabled(!this.capture.streaming && !this.busy && !this.editing).onClick((): void => {
              this.importPacket = ''; this.run(async (): Promise<void> => { await this.saveCopy(); });
            })
            ForEach(this.tasks.slice(0, 8), (task: MeetingTask) => {
              Row() {
                Text(task.stage + ' · ' + task.status + (task.error ? '\n' + task.error : '')).layoutWeight(1).fontSize(12)
                if (['running', 'queued', 'failed', 'cancelled', 'interrupted'].includes(task.status)) {
                Button(task.status === 'running' || task.status === 'queued' ? '取消' : '重试').onClick((): void => {
                  this.run(async (): Promise<void> => { await this.api.request<Object>(task.status === 'running' || task.status === 'queued' ? 'cancel_task' : 'retry_task', { id: task.id }); });
                })
                }
              }.width('100%')
            }, (task: MeetingTask): string => JSON.stringify(task))
          }
          this.Section('从其他应用导入会议副本')
          Button('选择交接 JSON').enabled(!this.busy).onClick((): void => { this.run(async (): Promise<void> => { await this.chooseCopy(); }); })
          if (this.importPacket.length > 0) {
            Text(this.importPreview).width('100%').fontSize(14).maxLines(12)
            Button('将此副本保存到本机记录').enabled(!this.busy).onClick((): void => { this.run(async (): Promise<void> => { await this.saveCopy(); }); })
          }
          Text('保存副本不包含录音，不会自动跟随原会议更新，也不会自动发送到云端。').fontSize(12).fontColor(AppTheme.text('#64756B')).margin({ top: 12, bottom: 32 })
        }.width('100%').padding({ left: 16, right: 16 })
      }.layoutWeight(1).width('100%')
    }.width('100%').height('100%').backgroundColor(AppTheme.pageBackground('#FFFFFF'))
  }
}
