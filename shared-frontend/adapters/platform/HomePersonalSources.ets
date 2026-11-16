import { HomeRecommendationViewModel } from '../datamanager/DataManagerTypes';
import { MailConfiguration, PersonalSources, SourceItem } from './PersonalSources';
import { SettingsStore } from './SettingsStore';

interface QuickCalendar {
  items: SourceItem[];
  systemEnabled: boolean;
  systemGranted: boolean;
}

interface HomeCalendar {
  available: boolean;
  items: HomeRecommendationViewModel[];
}

export class HomePersonalSources {
  calendarError: string = '';
  private calendarLoad: Promise<HomeCalendar> | null = null;

  async refresh(update: (calendar: boolean, mail: boolean, items: HomeRecommendationViewModel[]) => void): Promise<void> {
    if (this.calendarLoad === null) { this.calendarLoad = this.calendar(); }
    const pending = this.calendarLoad;
    const mail = PersonalSources.call('sourcesSettings').then((raw: string): boolean => {
      return (JSON.parse(raw) as MailConfiguration).username.length > 0;
    }).catch((): boolean => false);
    try {
      const calendar = await pending;
      update(calendar.available, await mail, calendar.items);
    } finally {
      if (this.calendarLoad === pending) { this.calendarLoad = null; }
    }
  }

  private completedIds(): string[] {
    try {
      const ids = JSON.parse(AppStorage.get<string>('androidCalendarCompletedIds') || '[]') as string[];
      return Array.isArray(ids) ? ids : [];
    }
    catch (e) { return []; }
  }

  complete(items: HomeRecommendationViewModel[], id: string, completed: boolean): HomeRecommendationViewModel[] {
    const item = items.find((value: HomeRecommendationViewModel): boolean => value.id === id);
    if (!item) { return items; }
    const ids = this.completedIds().filter((value: string): boolean => value !== id);
    if (completed) { ids.push(id); }
    const previous = AppStorage.get<string>('androidCalendarCompletedIds') || '[]';
    AppStorage.setOrCreate('androidCalendarCompletedIds', JSON.stringify(ids.slice(-512)));
    try { SettingsStore.save(); }
    catch (e) {
      AppStorage.setOrCreate('androidCalendarCompletedIds', previous);
      throw new Error((e as Error).message);
    }
    item.completed = completed;
    return items.slice();
  }

  private async calendar(): Promise<HomeCalendar> {
    let available = false;
    this.calendarError = '';
    try {
      const options = await PersonalSources.options();
      available = options.calendarEnabled || options.holidayEnabled ||
        options.festivalEnabled === true || options.solarTermEnabled === true;
      if (!available) { return { available: false, items: [] }; }
      const raw = await PersonalSources.operation('calendar', '{"quick":true}');
      const snapshot = JSON.parse(raw) as QuickCalendar;
      if (snapshot.systemEnabled && !snapshot.systemGranted) {
        this.calendarError = '系统日历尚未授权，请前往日历设置。';
      }
      const completed = this.completedIds();
      const items = snapshot.items.map((item: SourceItem): HomeRecommendationViewModel => {
        const id = 'calendar:android:' + item.id;
        const tag = item.source === 'holiday' ? '常用节假日' :
          item.source === 'festival' ? '节日' : item.source === 'solarTerm' ? '二十四节气' : '系统日历';
        return {
          id: id, domain: '日历', tag: tag, title: item.title, desc: '', meta: tag, color: '#6372D8',
          imageRefs: [], thumbnailUri: '', sourceEventIds: [], detail: item.body || tag,
          agentTask: '', completed: completed.includes(id),
          calendarStart: item.start, calendarEnd: item.end, calendarAllDay: item.allDay,
          calendarLocation: item.location
        };
      });
      return { available: available, items: items };
    } catch (e) {
      this.calendarError = (e as Error).message || '日历读取失败，请重试。';
      return { available: available, items: [] };
    }
  }
}
