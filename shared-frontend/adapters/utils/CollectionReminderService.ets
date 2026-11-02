import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import Bridge from '@arkui-x.bridge';
import { CollectionReminderState, CollectionReminderIntent, collectionReminderCanClaim } from './CollectionReminderPolicy';

export const COLLECTION_REMINDER_VERSION_KEY: string = 'collectionReminderVersion';
export const COLLECTION_REMINDER_READY_KEY: string = 'collectionReminderReady';
export const COLLECTION_REMINDER_REQUEST_KEY: string = 'collectionReminderRequest';
class NativeReminderState extends CollectionReminderState { v: number = 1; version: number = 0; }

// Android owns scheduling and durable reminder state. This cache serves the original synchronous panel.
export class CollectionReminderService {
  private static cached: CollectionReminderState = new CollectionReminderState();
  private static ready: Promise<void> | null = null;
  private static nativeVersion: number = -1;
  private static refreshing: boolean = false;
  private static rootVisible: boolean = false;
  static initialize(context: common.UIAbilityContext): void {
    if (CollectionReminderService.ready !== null) { return; }
    CollectionReminderService.cached.errorText = '正在读取 Android 采集提醒…';
    let old = preferences.getPreferencesSync(context, { name: 'android_reminder_preferences' });
    let hour = Number(old.getSync('hour', 21)); let minute = Number(old.getSync('minute', 0));
    if (!Number.isInteger(hour) || hour < 0 || hour > 23) { hour = 21; }
    if (!Number.isInteger(minute) || minute < 0 || minute > 59) { minute = 0; }
    CollectionReminderService.ready = CollectionReminderService.invoke('reminderInitialize', String(hour), String(minute))
      .then((): void => {}).catch((): void => {});
  }
  private static changed(): void {
    AppStorage.setOrCreate(COLLECTION_REMINDER_VERSION_KEY, (AppStorage.get<number>(COLLECTION_REMINDER_VERSION_KEY) || 0) + 1);
  }
  private static apply(raw: string): void {
    let value = JSON.parse(raw) as NativeReminderState;
    if (value.v !== 1) { throw new Error('Android 提醒协议版本不兼容'); }
    CollectionReminderService.cached = value; CollectionReminderService.nativeVersion = value.version;
    CollectionReminderService.changed();
  }
  private static async invoke(method: string, first: string = '', second: string = ''): Promise<void> {
    let bridge = Bridge.createBridge('ClawMatePlatform');
    try {
      let raw = method === 'reminderInitialize' || method === 'reminderSetTime' ? await bridge.callMethod(method, first, second) :
        method === 'reminderSetEnabled' ? await bridge.callMethod(method, first === 'true') : await bridge.callMethod(method);
      CollectionReminderService.apply(raw as string);
    } catch (error) {
      try { CollectionReminderService.apply(await bridge.callMethod('reminderState') as string); } catch (ignored) {}
      if (CollectionReminderService.cached.errorText.length === 0) { CollectionReminderService.cached.errorText = (error as Error).message || '提醒更新失败，请允许通知后重试'; }
      CollectionReminderService.changed();
      throw error as Error;
    }
  }
  static load(): CollectionReminderState { return CollectionReminderService.cached; }
  static async refresh(): Promise<void> {
    await CollectionReminderService.ready;
    await CollectionReminderService.invoke('reminderRefresh');
  }
  static async setEnabled(enabled: boolean): Promise<void> {
    await CollectionReminderService.ready;
    await CollectionReminderService.invoke('reminderSetEnabled', String(enabled));
    if (CollectionReminderService.cached.enabled !== enabled) { throw new Error(CollectionReminderService.cached.errorText || '提醒未开启，请允许通知后重试'); }
    AppStorage.setOrCreate(COLLECTION_REMINDER_REQUEST_KEY, '');
  }
  static async setTime(hour: number, minute: number): Promise<void> {
    if (!Number.isInteger(hour) || !Number.isInteger(minute) || hour < 0 || hour > 23 || minute < 0 || minute > 59) { throw new Error('提醒时间无效'); }
    await CollectionReminderService.ready;
    await CollectionReminderService.invoke('reminderSetTime', String(hour), String(minute));
    AppStorage.setOrCreate(COLLECTION_REMINDER_REQUEST_KEY, '');
  }
  static setWindowReady(visible: boolean): void {
    CollectionReminderService.rootVisible = visible;
    if (!visible) { AppStorage.setOrCreate(COLLECTION_REMINDER_READY_KEY, false); }
  }
  static acceptRuntime(version: number, request: string, unlocked: boolean): void {
    AppStorage.setOrCreate(COLLECTION_REMINDER_READY_KEY, unlocked && CollectionReminderService.rootVisible);
    AppStorage.setOrCreate(COLLECTION_REMINDER_REQUEST_KEY, request);
    if (version !== CollectionReminderService.nativeVersion && !CollectionReminderService.refreshing) {
      CollectionReminderService.refreshing = true;
      CollectionReminderService.refresh().catch(() => {}).finally((): void => { CollectionReminderService.refreshing = false; });
    }
  }
  static pendingIntent(request: string): CollectionReminderIntent | null {
    try {
      if (request.length === 0) { return null; }
      let value = JSON.parse(request) as CollectionReminderIntent;
      return collectionReminderCanClaim(CollectionReminderService.load(), value, Date.now()) ? value : null;
    } catch (error) { return null; }
  }
  static clearRequest(request: string): void {
    if (AppStorage.get<string>(COLLECTION_REMINDER_REQUEST_KEY) !== request) { return; }
    AppStorage.setOrCreate(COLLECTION_REMINDER_REQUEST_KEY, '');
    Bridge.createBridge('ClawMatePlatform').callMethod('reminderClear', request).catch(() => {});
  }
  static async claim(request: string): Promise<boolean> {
    if (AppStorage.get<string>(COLLECTION_REMINDER_REQUEST_KEY) !== request || CollectionReminderService.pendingIntent(request) === null) { return false; }
    let claimed = await Bridge.createBridge('ClawMatePlatform').callMethod('reminderClaim', request) as boolean;
    if (claimed) { CollectionReminderService.clearRequest(request); await CollectionReminderService.refresh(); }
    return claimed;
  }
  static async recordSuccessfulCollection(): Promise<void> {
    await CollectionReminderService.ready;
    await CollectionReminderService.invoke('reminderRecordCollection');
  }
}
