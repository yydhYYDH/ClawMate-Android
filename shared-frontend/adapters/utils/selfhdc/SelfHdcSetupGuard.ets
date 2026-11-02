import { UIContext } from '@kit.ArkUI';
import { showToastSafely } from '../PromptSafe';
export class SelfHdcSetupGuard {
  static async ensure(context: UIContext, filesDir: string, operation: string, showPrompt: boolean = true): Promise<boolean> {
    showToastSafely('安卓尚未接入手机自动控制，请在“我的”查看能力说明');
    return false;
  }
}
