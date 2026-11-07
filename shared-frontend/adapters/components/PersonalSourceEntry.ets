import { router } from '@kit.ArkUI';
import { AppTheme } from '../utils/AppTheme';
import { PersonalSources, MailConfiguration } from '../platform/PersonalSources';
import { PersonalSourceSync } from '../platform/PersonalSourceSync';

@Component
export struct PersonalSourceEntry {
  @Prop kind: string = 'calendar';
  /** 打开的分析页路由；日历与邮箱分别使用独立页面。 */
  @Prop target: string = 'pages/settings/CalendarSourcesPage';
  onOpenMeeting: () => void = () => {};
  @State ready: boolean = false;
  @State busy: boolean = false;
  @State status: string = '';
  private sync: PersonalSourceSync = new PersonalSourceSync();
  private alive: boolean = false;
  aboutToAppear(): void { this.alive = true; this.refresh(); }
  aboutToDisappear(): void { this.alive = false; this.sync.cancel(); }
  private open(): void {
    if (this.busy) { return; }
    if (this.kind === 'meeting') { this.onOpenMeeting(); }
    else { router.pushUrl({ url: this.target, params: { kind: this.kind } }); }
  }
  private async refresh(): Promise<void> {
    if (this.kind === 'meeting') { return; }
    try {
      let options = await PersonalSources.options();
      let config = JSON.parse(await PersonalSources.call('sourcesSettings')) as MailConfiguration;
      if (this.alive) { this.ready = this.kind === 'calendar' ? options.calendarEnabled && options.selected.length > 0 : config.username.length > 0; }
    } catch (e) { this.ready = false; }
  }
  private async read(): Promise<void> {
    if (this.busy || PersonalSources.busy) { return; }
    this.busy = true; this.status = '正在同步…';
    try {
      let snapshot = await this.sync.run(this.kind, (message: string): void => { if (this.alive) { this.status = message; } });
      if (this.alive) { this.status = '已同步 ' + snapshot.items.length + (this.kind === 'calendar' ? ' 项日程' : ' 封邮件') +
        (snapshot.partial ? ' · 部分覆盖，请打开查看' : ' · ' + new Date(snapshot.syncedAt).toLocaleTimeString()); }
    } catch (e) { if (this.alive) { this.status = (e as Error).message; } }
    finally { this.busy = false; }
  }
  build() {
    Column() {
      Row({ space: 10 }) {
        Row({ space: 12 }) {
          Stack() {
            if (this.kind === 'meeting') {
              Image($r('app.media.source_meeting')).width(24).height(24).objectFit(ImageFit.Contain)
            } else {
              Image(this.kind === 'calendar' ? $r('app.media.source_calendar') : $r('app.media.source_mail')).width(24).height(24).objectFit(ImageFit.Contain)
            }
          }.width(38).height(38).borderRadius(12)
            .backgroundColor(AppTheme.background(this.kind === 'meeting' ? '#E8F5F2' : (this.kind === 'calendar' ? '#FBF1E4' : '#EEF0FC')))
          Column({ space: 4 }) {
            Text(this.kind === 'meeting' ? '语音纪要' : (this.kind === 'calendar' ? '日历分析' : '邮箱分析'))
              .fontSize(15).fontWeight(FontWeight.Medium).fontColor(AppTheme.text('#161B28'))
            Text(this.kind === 'meeting' ? '录音、转写与纪要' : (this.kind === 'calendar' ? '日程同步与聊天查询' : '邮箱设置与近三天邮件'))
              .fontSize(11).fontColor(AppTheme.text('#7C879A')).maxLines(2)
          }.alignItems(HorizontalAlign.Start).layoutWeight(1)
        }.layoutWeight(1).constraintSize({ minHeight: 68 }).onClick(() => { this.open(); })
        if (this.kind !== 'meeting') {
          Button(this.busy ? '取消' : '同步').fontSize(12).width(56).height(36).padding({ left: 6, right: 6 }).borderRadius(12)
            .fontColor(AppTheme.text(this.ready || this.busy ? '#6577D9' : '#A5ACB9')).backgroundColor(AppTheme.background('#F3F5FA'))
            .enabled(this.ready || this.busy).accessibilityText((this.kind === 'calendar' ? '日历' : '邮箱') + (this.busy ? '取消同步' : '同步'))
            .onClick(() => { if (this.busy) { this.sync.cancel(); } else { this.read(); } })
        }
        Text('›').fontSize(24).fontColor(AppTheme.text('#A0A8B6')).width(18).textAlign(TextAlign.Center)
          .onClick(() => { this.open(); })
      }.width('100%').padding({ left: 14, right: 14 })
      if (this.status) { Text(this.status).fontSize(12).lineHeight(18).fontColor(AppTheme.text('#68758B')).width('100%').padding({ left: 64, right: 16, bottom: 12 }); }
    }.width('100%').onVisibleAreaChange([0.0], (visible: boolean): void => { if (visible) { this.refresh(); } })
  }
}
