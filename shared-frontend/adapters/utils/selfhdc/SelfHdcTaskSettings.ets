import { SettingsStore } from '../../platform/SettingsStore';
export class SelfHdcTaskSettings {
  mode: string = 'phone';
  static load(filesDir: string): SelfHdcTaskSettings {
    let result = new SelfHdcTaskSettings();
    result.mode = AppStorage.get<string>('androidTaskControlMode') === 'pc' ? 'pc' : 'phone';
    return result;
  }
  static saveMode(filesDir: string, mode: string): void {
    if (mode !== 'pc' && mode !== 'phone') { throw new Error('无效控制方式'); }
    AppStorage.setOrCreate('androidTaskControlMode', mode);
    SettingsStore.save();
  }
}
