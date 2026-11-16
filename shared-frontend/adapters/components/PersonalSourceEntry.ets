import { router } from '@kit.ArkUI';
import { AppTheme } from '../utils/AppTheme';
import { PersonalSources, MailConfiguration } from '../platform/PersonalSources';
import { PersonalSourceSync } from '../platform/PersonalSourceSync';
import { CollectionSourceRow } from './CollectionFormControls';

@Component
export struct PersonalSourceEntry {
  @Prop kind: string = 'calendar';
  /** 打开的分析页路由；日历与邮箱分别使用独立页面。 */
  @Prop target: string = 'pages/settings/CalendarSourcesPage';
  @State ready: boolean = false;
  @State busy: boolean = false;
  @State status: string = '';
  private sync: PersonalSourceSync = new PersonalSourceSync();
  private alive: boolean = false;
  aboutToAppear(): void { this.alive = true; this.refresh(); }
  aboutToDisappear(): void { this.alive = false; this.sync.cancel(); }
  private open(): void {
    if (this.busy) { return; }
    router.pushUrl({ url: this.target, params: { kind: this.kind } });
  }
  private async refresh(): Promise<void> {
    try {
      if (this.kind === 'calendar') {
        const options = await PersonalSources.options();
        if (this.alive) {
          this.ready = (options.calendarEnabled && options.selected.length > 0) || options.holidayEnabled ||
            options.festivalEnabled === true || options.solarTermEnabled === true;
        }
      } else {
        const config = JSON.parse(await PersonalSources.call('sourcesSettings')) as MailConfiguration;
        if (this.alive) { this.ready = config.username.length > 0; }
      }
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
      CollectionSourceRow({
        title: this.kind === 'calendar' ? '日历分析' : '邮箱分析',
        icon: this.kind === 'calendar' ? $r('app.media.source_calendar') : $r('app.media.source_mail'),
        tint: this.kind === 'calendar' ? '#FF9500' : '#007AFF',
        showSync: true, syncEnabled: this.ready || this.busy, syncLabel: this.busy ? '取消' : '',
        onOpen: (): void => { this.open(); },
        onSync: (): void => {
          if (this.busy) { this.sync.cancel(); } else { this.read(); }
        }
      })
      if (this.status) {
        Text(this.status).fontSize(12).lineHeight(18).fontColor(AppTheme.text('#68758B'))
          .width('100%').padding({ left: 64, right: 16, bottom: 12 })
      }
    }.width('100%').onVisibleAreaChange([0.0], (visible: boolean): void => { if (visible) { this.refresh(); } })
  }
}
