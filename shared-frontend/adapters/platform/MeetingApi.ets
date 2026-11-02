import Bridge from '@arkui-x.bridge';
import { MeetingJob, MeetingPoll, MeetingArgs, MeetingRevision } from './MeetingTypes';

export class MeetingApi {
  private jobs: string[] = [];
  private closed: boolean = false;
  private bridge() { return Bridge.createBridge('ClawMatePlatform'); }
  async initialize(): Promise<void> { this.closed = false; await this.bridge().callMethod('meetingInitialize'); }
  async attach(): Promise<void> { await this.bridge().callMethod('meetingAttach'); }
  async poll(sequence: number): Promise<MeetingPoll> {
    let value = JSON.parse(await this.bridge().callMethod('meetingPoll', String(sequence)) as string) as MeetingPoll;
    if (value.v !== 1) { throw new Error('会议接口版本不兼容'); }
    return value;
  }
  async request<T>(operation: string, args: MeetingArgs = {}): Promise<T> {
    let id = await this.bridge().callMethod('meetingRequest', operation, JSON.stringify(args)) as string;
    return await this.wait<T>(id);
  }
  async wait<T>(id: string): Promise<T> {
    if (this.closed) { await this.bridge().callMethod('meetingCancel', id); throw new Error('会议页面已关闭'); }
    this.jobs.push(id);
    try {
      let deadline = Date.now() + 180000;
      while (!this.closed && Date.now() < deadline) {
        let result = JSON.parse(await this.bridge().callMethod('meetingJob', id) as string) as MeetingJob;
        if (result.v !== 1) { throw new Error('会议任务接口版本不兼容'); }
        if (result.status === 'done') { return JSON.parse(result.payload) as T; }
        if (result.status !== 'running') { throw new Error(result.error || '会议请求未完成'); }
        await new Promise<void>((resolve): void => { setTimeout(resolve, 180); });
      }
      await this.bridge().callMethod('meetingCancel', id);
      throw new Error(this.closed ? '会议页面已关闭' : '会议请求超时，可在会议任务中检查已提交操作');
    } finally { this.jobs = this.jobs.filter((value: string): boolean => value !== id); }
  }
  async record(id: string): Promise<void> { await this.bridge().callMethod('meetingRecord', id); }
  async capture(action: string): Promise<void> { await this.bridge().callMethod('meetingCapture', action); }
  async chooseAudio(id: string, profile: string): Promise<void> {
    let job = await this.bridge().callMethod('meetingChooseAudio', id, profile) as string;
    await this.wait<Object>(job);
  }
  async chooseHandoff(): Promise<string> {
    let job = await this.bridge().callMethod('meetingChooseHandoff') as string;
    return JSON.stringify(await this.wait<Object>(job));
  }
  async export(id: string, format: string, revision: number, hash: string): Promise<void> {
    let input: MeetingRevision = { base_revision: revision, source_hash: hash };
    let job = await this.bridge().callMethod('meetingExport', id, format, JSON.stringify(input)) as string;
    await this.wait<Object>(job);
  }
  async play(id: string, audioId: string, start: number, end: number): Promise<void> {
    await this.bridge().callMethod('meetingPlay', id, audioId, String(start), String(end));
  }
  async stopPlayback(): Promise<void> { await this.bridge().callMethod('meetingStopPlayback'); }
  async validateHandoff(raw: string): Promise<string> { return await this.bridge().callMethod('meetingValidateHandoff', raw) as string; }
  close(): void {
    this.closed = true;
    this.jobs.forEach((id: string): void => { this.bridge().callMethod('meetingCancel', id).catch(() => {}); });
    this.jobs = [];
    this.bridge().callMethod('meetingDetach').catch(() => {});
    this.stopPlayback().catch(() => {});
  }
}
