import { common } from '@kit.AbilityKit';
import { router, promptAction } from '@kit.ArkUI';
import { AppHeader } from '../../components/AppHeader';
import { FrostedPage } from '../../components/FrostedPage';
import { AppTheme } from '../../utils/AppTheme';
import { MeetingApi } from '../../platform/MeetingApi';
import { MeetingImport } from '../../platform/MeetingImport';
import { MeetingRecord, MeetingDocument, MeetingHistory, MeetingHistoryItem, MeetingCapture, MeetingModelGroup,
  MeetingModels, MeetingTask, MeetingVersions, MeetingVersion, MeetingSegment, MeetingEvent,
  MeetingHandoffEnvelope, MeetingHandoffPayload, emptyMeeting } from '../../platform/MeetingTypes';

interface CreatedTemplate { id: string; }
interface SpeakerEdit { id: string; name: string; }
interface MeetingActionOptions { label: string; action: () => void; enabled?: boolean; danger?: boolean; }

const MEETING_PROFILE_IDS: string[] = ['local-sensevoice', 'local-firered', 'local-qwen'];
const MEETING_PROFILE_OPTIONS: SelectOption[] = [
  { value: '仅本地 SenseVoice' }, { value: 'FireRed 逐段精修' }, { value: 'Qwen 离线精修' }
];

@Entry
@Component
struct MeetingPage {
  @State message: string = '正在准备本地语音纪要服务…';
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
  private scroller: Scroller = new Scroller();
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
      .catch((error: Error): void => {
        this.message = error.message === 'Method not implemented!'
          ? '语音纪要服务暂不可用。请确认当前版本已包含会议组件。' : error.message;
      });
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
    Text(title).fontSize(13).fontWeight(FontWeight.Medium).fontColor(AppTheme.text('#748096'))
      .width('100%').padding({ left: 16, top: 22, bottom: 8 })
  }

  @Builder private Line() {
    Divider().color(AppTheme.border('#EDF0F5')).margin({ left: 16, right: 16 })
  }

  @Builder private Action($$: MeetingActionOptions) {
    Button($$.label, { type: ButtonType.Normal }).width('100%').height(52).fontSize(15)
      .fontColor(AppTheme.text($$.enabled === false ? '#A0A8B6' : ($$.danger ? '#D94848' : '#3978E8')))
      .backgroundColor(Color.Transparent).enabled($$.enabled !== false).onClick($$.action)
  }

  @Builder private InlineAction($$: MeetingActionOptions) {
    Button($$.label).height(36).fontSize(12).padding({ left: 12, right: 12 }).borderRadius(12)
      .fontColor(AppTheme.text($$.enabled === false ? '#A0A8B6' : '#3978E8'))
      .backgroundColor(AppTheme.background('#EDF3FC')).enabled($$.enabled !== false).onClick($$.action)
  }

  @Builder private Segment(row: MeetingSegment) {
    Column({ space: 8 }) {
      Row() {
        Text(this.meeting.document.speaker_names[row.speaker] || row.speaker || '未确认说话人')
          .fontSize(12).fontColor(AppTheme.text('#7C879A')).layoutWeight(1)
        if (this.meeting.document.audio_id && !this.capture.streaming) {
          this.InlineAction({ label: '回听', action: () => { this.run(async (): Promise<void> => {
            await this.api.play(this.meeting.id, this.meeting.document.audio_id || '', row.start || 0, row.end || 0);
          }); } })
        }
      }.width('100%')
      Text(row.text).fontSize(16).lineHeight(24).fontColor(AppTheme.text('#161B28'))
        .width('100%').copyOption(CopyOptions.LocalDevice)
      if (row.refinement_status === 'draft') { Text('草稿 · 等待逐段精修').fontSize(12).fontColor(AppTheme.text('#89723C')) }
      if (row.refinement_status === 'failed') { Text('精修未完成，已保留草稿').fontSize(12).fontColor(AppTheme.text('#89723C')) }
    }.padding(16).width('100%').backgroundColor(AppTheme.background('#F4F6FA')).borderRadius(12)
  }
  @Builder private CaptureCard() {
    this.Section('录音与导入')
    Column() {
      this.Action({ label: '开始新录音', enabled: this.canRecord(), action: () => { this.run(() => this.draft('recording')); } })
      this.Line()
      this.Action({ label: '导入音频', enabled: this.canRecord(), action: () => { this.run(() => this.draft('upload')); } })
      if (this.capture.streaming) {
        this.Line()
        Text(this.phase() + ' · ' + String(Math.floor(this.capture.audio_seconds)) + ' 秒')
          .width('100%').fontSize(13).fontColor(AppTheme.text('#53617A')).padding(16)
        this.Action({ label: this.capture.paused ? '继续录音' : '暂停录音',
          enabled: this.capture.recording && this.capture.phase !== 'finalizing',
          action: () => { this.run(() => this.api.capture(this.capture.paused ? 'resume' : 'pause')); } })
        this.Line()
        this.Action({ label: '结束录音', enabled: this.capture.phase !== 'finalizing', danger: true,
          action: () => { this.run(() => this.api.capture('stop')); } })
      }
    }.width('100%').backgroundColor(AppTheme.background('#FFFFFF')).borderRadius(20).clip(true)
  }

  @Builder private ModelsCard() {
    this.Section('本地模型')
    Column() {
      Row() {
        Text('精修模式').fontSize(15).fontColor(AppTheme.text('#161B28')).layoutWeight(1)
        Select(MEETING_PROFILE_OPTIONS)
          .selected(MEETING_PROFILE_IDS.indexOf(this.profile))
          .value(MEETING_PROFILE_OPTIONS[MEETING_PROFILE_IDS.indexOf(this.profile)].value)
          .font({ size: 14 }).fontColor(AppTheme.text('#68758B')).backgroundColor(Color.Transparent)
          .onSelect((index: number): void => { this.profile = MEETING_PROFILE_IDS[index]; })
      }.width('100%').padding({ left: 16, right: 8, top: 8, bottom: 8 })
      this.Line()
      Row() {
        Text('录音自动精修').fontSize(15).fontColor(AppTheme.text('#161B28')).layoutWeight(1)
        Toggle({ type: ToggleType.Switch, isOn: this.autoRefine }).selectedColor('#3978E8')
          .onChange((value: boolean): void => { this.autoRefine = value; })
      }.width('100%').padding({ left: 16, right: 16, top: 12, bottom: 12 })
      ForEach(this.models, (group: MeetingModelGroup) => {
        this.Line()
        Row({ space: 12 }) {
          Column({ space: 4 }) {
            Text(this.modelLabel(group.id)).fontSize(15).fontColor(AppTheme.text('#161B28'))
            Text(group.ready ? '已准备' : (group.downloadable ? '未准备' : '未准备 · 暂不提供应用内下载'))
              .fontSize(12).fontColor(AppTheme.text('#7C879A'))
          }.alignItems(HorizontalAlign.Start).layoutWeight(1)
          if (!group.ready && group.downloadable) {
            this.InlineAction({ label: '下载', enabled: !this.busy, action: () => {
              this.run(async (): Promise<void> => {
                await this.api.request<Object>('models_download', { body: { groups: [group.id] } });
                this.message = '正在下载所选模型';
              });
            } })
          }
        }.width('100%').padding({ left: 16, right: 16, top: 12, bottom: 12 })
      }, (group: MeetingModelGroup): string => JSON.stringify(group))
      this.Line()
      this.Action({ label: '释放空闲模型', enabled: this.ready && !this.capture.streaming && !this.busy, action: () => {
        this.run(async (): Promise<void> => {
          await this.api.request<Object>('release_models');
          this.message = '空闲模型已释放，下一次使用时重新加载';
        });
      } })
    }.width('100%').backgroundColor(AppTheme.background('#FFFFFF')).borderRadius(20).clip(true)
  }

  @Builder private HistoryFilter(label: string, view: string) {
    Button(label, { type: ButtonType.Normal }).layoutWeight(1).height(36).fontSize(13).borderRadius(12)
      .fontColor(AppTheme.text(this.view === view ? '#3978E8' : '#68758B'))
      .backgroundColor(AppTheme.background(this.view === view ? '#EDF3FC' : '#F4F6FA'))
      .onClick(() => { this.view = view; this.run(() => this.refreshHistory()); })
  }

  @Builder private HistoryCard() {
    this.Section('纪要记录')
    Column() {
      Row({ space: 8 }) {
        this.HistoryFilter('待整理', 'inbox')
        this.HistoryFilter('已保存', 'meetings')
        this.HistoryFilter('回收站', 'trash')
      }.width('100%').padding(12)
      this.Line()
      Row({ space: 8 }) {
        TextInput({ text: this.query, placeholder: '搜索名称、正文和纪要' })
          .layoutWeight(1).fontSize(15).height(44).fontColor(AppTheme.text('#161B28'))
          .placeholderColor(AppTheme.text('#A0A8B6')).backgroundColor(Color.Transparent)
          .accessibilityText('搜索纪要').onChange((value: string): void => { this.query = value; })
          .onSubmit((): void => { this.run(() => this.refreshHistory()); })
        this.InlineAction({ label: '刷新', enabled: this.ready && !this.busy, action: () => { this.run(() => this.refreshHistory()); } })
      }.width('100%').padding({ left: 4, right: 16, top: 4, bottom: 4 })
      ForEach(this.history, (item: MeetingHistoryItem) => {
        this.Line()
        Row({ space: 12 }) {
          Text(item.title || '待整理录音').fontSize(15).fontColor(AppTheme.text('#161B28')).layoutWeight(1)
            .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text('›').fontSize(22).fontColor(AppTheme.text('#A0A8B6'))
        }.width('100%').constraintSize({ minHeight: 52 }).padding({ left: 16, right: 16, top: 10, bottom: 10 })
          .onClick((): void => { this.run(() => this.open(item.id)); })
      }, (item: MeetingHistoryItem): string => JSON.stringify(item))
      if (this.history.length === 0) {
        this.Line()
        Text('暂无记录').fontSize(13).fontColor(AppTheme.text('#7C879A')).width('100%').padding(16)
      }
    }.width('100%').backgroundColor(AppTheme.background('#FFFFFF')).borderRadius(20).clip(true)
  }

  @Builder private RecordCard() {
    this.Section('当前记录')
    Column() {
      Text('会议名称').width('100%').fontSize(12).fontColor(AppTheme.text('#7C879A'))
        .padding({ left: 16, right: 16, top: 12 })
      TextInput({ text: this.title, placeholder: '会议名称' })
        .fontSize(15).height(44).fontColor(AppTheme.text('#161B28')).placeholderColor(AppTheme.text('#A0A8B6'))
        .backgroundColor(Color.Transparent).padding({ left: 16, right: 16 }).borderRadius(0)
        .accessibilityText('会议名称').onChange((value: string): void => { this.title = value; this.titleDirty = true; })
      this.Line()
      this.Action({ label: '保存会议', enabled: !this.busy && !this.capture.streaming, action: () => { this.run(() => this.saveTitle()); } })
      this.Line()
      this.Action({ label: '放弃名称修改', action: () => { this.titleDirty = false; this.title = this.meeting.title; } })
      this.Line()
      this.Action({ label: this.view === 'trash' ? '恢复记录' : '移入回收站',
        enabled: !this.capture.streaming && !this.busy, danger: this.view !== 'trash', action: () => {
          this.run(async (): Promise<void> => {
            await this.api.request<Object>('history_batch', { body: { ids: [this.meeting.id], action: this.view === 'trash' ? 'restore' : 'trash' } });
            await this.refreshHistory();
          });
        } })
      if (this.meeting.document_audio?.recoverable) {
        this.Line()
        this.Action({ label: '恢复已保存录音', action: () => {
          this.run(async (): Promise<void> => {
            await this.api.request<Object>('recover_audio', { id: this.meeting.id, audio_id: this.meeting.active_audio_id || this.meeting.document.audio_id || '' });
            await this.refreshMeeting();
          });
        } })
      }
    }.width('100%').backgroundColor(AppTheme.background('#FFFFFF')).borderRadius(20).clip(true)
  }

  @Builder private TranscriptCard() {
    this.Section('正文 · 版本 ' + String(this.meeting.document.revision))
    Column() {
      Column({ space: 8 }) {
        if (this.capture.streaming && this.capture.mid === this.meeting.id) {
          ForEach(this.capture.finals, (row: MeetingSegment) => { this.Segment(row) })
          if (this.partial.length > 0) {
            Text(this.partial).fontSize(15).lineHeight(23).fontColor(AppTheme.text('#68758B')).width('100%').padding(4)
          }
        } else {
          ForEach(this.meeting.document.segments, (row: MeetingSegment) => { this.Segment(row) })
        }
      }.width('100%').padding({ left: 12, right: 12, top: 12 })
      this.Action({ label: '停止回听', action: () => { this.api.stopPlayback(); } })
      this.Line()
      if (!this.editing) {
        this.Action({ label: '编辑正文与说话人', enabled: !this.capture.streaming && this.meeting.document.segments.length > 0,
          action: () => { this.beginEdit(); } })
        this.Line()
        this.Action({ label: '历史版本', action: () => { this.run(() => this.showVersions()); } })
      } else {
        Column({ space: 8 }) {
          ForEach(this.speakers, (speaker: SpeakerEdit, index: number) => {
            TextInput({ text: speaker.name, placeholder: speaker.id }).height(44).fontSize(15)
              .backgroundColor(AppTheme.background('#F4F6FA')).fontColor(AppTheme.text('#161B28'))
              .onChange((value: string): void => { this.speakers[index].name = value; })
          }, (speaker: SpeakerEdit): string => speaker.id)
          ForEach(this.edits, (row: MeetingSegment, index: number) => {
            TextArea({ text: row.text }).height(100).fontSize(15)
              .backgroundColor(AppTheme.background('#F4F6FA')).fontColor(AppTheme.text('#161B28'))
              .onChange((value: string): void => { this.edits[index].text = value; })
          }, (row: MeetingSegment, index: number): string => String(index))
        }.width('100%').padding(12)
        this.Action({ label: '保存人工稿', action: () => { this.run(() => this.saveEdit()); } })
        this.Line()
        this.Action({ label: '放弃人工稿', action: () => { this.editing = false; } })
      }
      ForEach(this.versions, (version: MeetingVersion) => {
        this.Line()
        this.Action({ label: '恢复版本 ' + String(version.revision) + ' · ' + version.origin,
          action: () => { this.run(() => this.restore(version.revision)); } })
      }, (version: MeetingVersion): string => String(version.revision))
    }.width('100%').backgroundColor(AppTheme.background('#FFFFFF')).borderRadius(20).clip(true)
  }

  @Builder private OutputCard() {
    this.Section('整理与导出')
    Column() {
      this.Action({ label: '重新精修', enabled: !this.capture.streaming && !this.busy && !!this.meeting.document.audio_id, action: () => {
        this.run(async (): Promise<void> => {
          await this.api.request<Object>('process', { id: this.meeting.id, body: {
            audio_id: this.meeting.document.audio_id, compare: false, offline_model: 'sensevoice', refinement_profile_id: this.profile } });
          this.message = '精修任务已提交';
        });
      } })
      this.Line()
      this.Action({ label: '生成本地纪要', enabled: !this.capture.streaming && !this.busy && this.meeting.document.transcript.length > 0, action: () => {
        this.run(async (): Promise<void> => {
          await this.api.request<Object>('summary', { id: this.meeting.id });
          this.message = '正在生成本地纪要';
        });
      } })
      if (this.meeting.summary) {
        this.Line()
        Text(this.meeting.summary.text).width('100%').fontSize(15).lineHeight(24)
          .fontColor(AppTheme.text('#161B28')).padding(16).copyOption(CopyOptions.LocalDevice)
      }
      ForEach(['export.md', 'export', 'handoff'], (format: string) => {
        this.Line()
        this.Action({ label: format === 'export.md' ? '导出 Markdown' : (format === 'export' ? '导出会议 JSON' : '导出交接副本'),
          action: () => { this.run(async (): Promise<void> => {
            await this.api.export(this.meeting.id, format, this.meeting.document.revision, this.meeting.document.source_hash);
            this.message = '已导出到所选位置';
          }); } })
      })
      this.Line()
      this.Action({ label: '保存到本机记录', enabled: !this.capture.streaming && !this.busy && !this.editing,
        action: () => { this.importPacket = ''; this.run(() => this.saveCopy()); } })
    }.width('100%').backgroundColor(AppTheme.background('#FFFFFF')).borderRadius(20).clip(true)
  }

  @Builder private TasksCard() {
    this.Section('处理进度')
    Column() {
      ForEach(this.tasks.slice(0, 8), (task: MeetingTask, index: number) => {
        if (index > 0) { this.Line() }
        Row({ space: 8 }) {
          Text(task.stage + ' · ' + task.status + (task.error ? '\n' + task.error : '')).layoutWeight(1)
            .fontSize(13).lineHeight(20).fontColor(AppTheme.text('#53617A'))
          if (['running', 'queued', 'failed', 'cancelled', 'interrupted'].includes(task.status)) {
            this.InlineAction({ label: task.status === 'running' || task.status === 'queued' ? '取消' : '重试',
              action: () => { this.run(async (): Promise<void> => {
                await this.api.request<Object>(task.status === 'running' || task.status === 'queued' ? 'cancel_task' : 'retry_task', { id: task.id });
              }); } })
          }
        }.width('100%').padding(16)
      }, (task: MeetingTask): string => JSON.stringify(task))
    }.width('100%').backgroundColor(AppTheme.background('#FFFFFF')).borderRadius(20).clip(true)
  }

  @Builder private ImportCard() {
    this.Section('导入会议副本')
    Column() {
      this.Action({ label: '选择交接 JSON', enabled: !this.busy, action: () => { this.run(() => this.chooseCopy()); } })
      if (this.importPacket.length > 0) {
        this.Line()
        Text(this.importPreview).width('100%').fontSize(14).lineHeight(22)
          .fontColor(AppTheme.text('#53617A')).maxLines(12).padding(16)
        this.Line()
        this.Action({ label: '将此副本保存到本机记录', enabled: !this.busy, action: () => { this.run(() => this.saveCopy()); } })
      }
    }.width('100%').backgroundColor(AppTheme.background('#FFFFFF')).borderRadius(20).clip(true)
    Text('交接副本将文字、纪要和来源信息保存在本机。')
      .fontSize(12).lineHeight(19).fontColor(AppTheme.text('#7C879A')).width('100%')
      .padding({ left: 16, right: 16, top: 8 })
  }

  @Builder private Header() {
    AppHeader({ title: '语音纪要', showBackButton: true, onBackClick: () => { router.back(); } })
  }

  @Builder private Content() {
    Scroll(this.scroller) {
      Column() {
        Text(this.message).fontSize(13).lineHeight(20).fontColor(AppTheme.text('#53617A'))
          .width('100%').padding(16).backgroundColor(AppTheme.background('#EDF3FC')).borderRadius(16)
        this.CaptureCard()
        this.ModelsCard()
        this.HistoryCard()
        if (this.meeting.id.length > 0) {
          this.RecordCard()
          this.TranscriptCard()
          this.OutputCard()
          if (this.tasks.length > 0) { this.TasksCard() }
        }
        this.ImportCard()
      }.width('92%').constraintSize({ maxWidth: 720 }).padding({ top: 16, bottom: 40 })
    }.width('100%').height('100%').scrollBar(BarState.Off)
  }

  build() {
    Column() {
      FrostedPage({ pageBackgroundColor: '#F4F6FA', scrollers: [this.scroller],
        headerBuilder: () => { this.Header(); }, contentBuilder: () => { this.Content(); } })
    }.width('100%').height('100%')
  }
}
