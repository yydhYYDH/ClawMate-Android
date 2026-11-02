import { AppLogger } from '../AppLogger';
export interface AndroidControlStatus { running: boolean; stage: string; }
export class SelfHdcTestRunner {
  private static instance: SelfHdcTestRunner = new SelfHdcTestRunner();
  static shared(): SelfHdcTestRunner { return SelfHdcTestRunner.instance; }
  snapshot(): AndroidControlStatus { return { running: false, stage: '安卓自动控制尚未接入' }; }
  recordExternal(text: string): void { AppLogger.info('AndroidControl', text); }
}
