import { common } from '@kit.AbilityKit';
export const PERSONAL_MEMORY_A2A_STATE_KEY: string = 'personalMemoryA2aEnabled';
export class PersonalMemoryAccess {
  static async isEnabled(context: common.Context): Promise<boolean> { return false; }
  static async setEnabled(context: common.Context, enabled: boolean): Promise<boolean> {
    if (enabled) { throw new Error('安卓尚未接入小艺 A2A；个人记忆不会通过此入口对外提供'); }
    AppStorage.setOrCreate(PERSONAL_MEMORY_A2A_STATE_KEY, false);
    return false;
  }
}
