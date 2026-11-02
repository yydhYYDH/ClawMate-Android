import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { SETTINGS_STRING_KEYS, SETTINGS_BOOLEAN_KEYS } from './SettingsKeys';
import { showToastSafely } from '../utils/PromptSafe';

export class SettingsStore {
  private static store: preferences.Preferences | null = null;
  private static timer: number = -1;
  private static lastSnapshot: string = '';

  static initialize(context: common.Context): void {
    let store = preferences.getPreferencesSync(context, { name: 'shared_frontend_settings' });
    SettingsStore.store = store;
    SETTINGS_STRING_KEYS.forEach((key: string): void => {
      if (store.hasSync(key)) { AppStorage.setOrCreate(key, String(store.getSync(key, ''))); }
    });
    SETTINGS_BOOLEAN_KEYS.forEach((key: string): void => {
      if (store.hasSync(key)) { AppStorage.setOrCreate(key, store.getSync(key, false) === true); }
    });
  }

  static scheduleSave(): void {
    if (SettingsStore.timer >= 0) { clearTimeout(SettingsStore.timer); }
    SettingsStore.timer = setTimeout((): void => {
      SettingsStore.timer = -1;
      try { SettingsStore.save(); } catch (error) { showToastSafely('设置保存失败，请重试'); }
    }, 300);
  }

  static save(): void {
    let store = SettingsStore.store;
    if (store === null) { throw new Error('设置存储尚未就绪'); }
    let readyStore: preferences.Preferences = store;
    let strings: string[] = [];
    SETTINGS_STRING_KEYS.forEach((key: string): void => {
      let value = AppStorage.get<string>(key);
      if (typeof value === 'string') { strings.push(key + ':' + value); }
    });
    SETTINGS_BOOLEAN_KEYS.forEach((key: string): void => {
      strings.push(key + ':' + String(AppStorage.get<boolean>(key)));
    });
    let snapshot = JSON.stringify(strings);
    if (snapshot === SettingsStore.lastSnapshot) { return; }
    SETTINGS_STRING_KEYS.forEach((key: string): void => {
      let value = AppStorage.get<string>(key);
      if (typeof value === 'string') { readyStore.putSync(key, value); }
    });
    SETTINGS_BOOLEAN_KEYS.forEach((key: string): void => {
      let value = AppStorage.get<boolean>(key);
      if (typeof value === 'boolean') { readyStore.putSync(key, value); }
    });
    readyStore.flushSync();
    SettingsStore.lastSnapshot = snapshot;
  }
}
