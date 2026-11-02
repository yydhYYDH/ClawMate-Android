import Bridge from '@arkui-x.bridge';
import { WorkflowTask } from '../utils/WorkflowTypes';
import { WorkflowStorage } from '../utils/WorkflowStorage';
import { WorkflowCancellation } from '../utils/WorkflowCancellation';
import { DataManagerFacade } from '../datamanager/DataManagerFacade';
import { CollectionReminderService } from '../utils/CollectionReminderService';
import { WorkflowVlm, WorkflowVlmRequest } from './WorkflowVlm';

interface ExecutionResult { status: string; message: string; resultSummary: string; steps: Object[]; }
interface ExecutionPoll { v: number; status: string; result: string; tool?: WorkflowVlmRequest; }
interface SummaryTaskMetadata { name: string; task_scene_id: number; source_kind: string; }
interface SummaryMetadata { workflow_metadata: SummaryTaskMetadata; latest_entry_index: number; }
interface SharedRunRecord { source_kind: string; workflow_file: string; run_dir: string; status: string; message: string; result_summary: string; steps: Object[]; }
export class AndroidWorkflow {
  static async run(filesDir: string, task: WorkflowTask, cancellation: WorkflowCancellation,
    authorize: () => Promise<void> = async (): Promise<void> => { throw new Error('请在共享任务页确认云端授权'); }): Promise<void> {
    let bridge = Bridge.createBridge('ClawMatePlatform');
    let id = '';
    let complete = false;
    let model: WorkflowVlm | null = null;
    let usedModel = false;
    WorkflowStorage.updateTaskRun(filesDir, task.id, '运行中', '正在执行 Android 有界工作流');
    try {
      cancellation.throwIfCancelled();
      id = await bridge.callMethod('workflowStart', WorkflowStorage.readTaskWorkflowJson(filesDir, task)) as string;
      cancellation.setHandler((): void => { model?.cancel(); bridge.callMethod('workflowCancel', id).catch(() => {}); });
      let deadline = Date.now() + 65000;
      while (Date.now() < deadline) {
        cancellation.throwIfCancelled();
        let poll = JSON.parse(await bridge.callMethod('workflowPoll', id) as string) as ExecutionPoll;
        if (poll.v !== 1) { throw new Error('工作流接口版本不兼容'); }
        cancellation.throwIfCancelled();
        if (poll.status === 'running' && poll.tool) {
          if (model === null) { model = new WorkflowVlm(); }
          usedModel = true;
          let response = ''; let error = '';
          try { response = await model.execute(id, poll.tool, (): boolean => cancellation.isCancelled(), authorize); }
          catch (failure) { error = (failure as Error).message.slice(0, 500) || '视觉问答失败'; }
          cancellation.throwIfCancelled();
          if (!await bridge.callMethod('workflowToolReply', id, poll.tool.id, JSON.stringify({ response: response, error: error }))) {
            // Keep the user's refusal or transport error even if the bounded
            // native task expired while its confirmation dialog was open.
            let expired = '视觉问答所属任务已结束，未提交迟到结果';
            throw new Error(error.length > 0 ? error + '；' + expired : expired);
          }
          continue;
        }
        if (poll.status === 'done') {
          let result = JSON.parse(poll.result) as ExecutionResult;
          let facade = new DataManagerFacade(filesDir); facade.ensureReady();
          // ArkTS owns this package's files. The native runner returns results
          // without touching a second repository or generating fabricated logs.
          let runDir = WorkflowStorage.runsDir(filesDir) + '/android-' + Date.now().toString();
          facade.storage.ensureDir(runDir);
          let source = usedModel ? 'android_workflow_model' : 'android_workflow';
          let record: SharedRunRecord = { source_kind: source, workflow_file: task.configFileName, run_dir: runDir,
            status: result.status, message: result.message, result_summary: result.resultSummary, steps: result.steps };
          facade.storage.writeJsonAtomic(runDir + '/run_summary.json', record as Object);
          if (result.status === 'success' && result.resultSummary.trim().length > 0) {
            let day = new Date();
            let date = day.getFullYear().toString() + '-' + String(day.getMonth() + 1).padStart(2, '0') + '-' + String(day.getDate()).padStart(2, '0');
            let name = task.id.replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 80);
            let path = facade.paths.dailyLogRoot + '/' + date + '/android-' + name + '.md';
            let previous = facade.storage.readText(path, '');
            let count = (previous.match(/^\d+\. /gm) || []).length;
            if (previous.length + result.resultSummary.length > 200000 || count >= 60) { throw new Error('当日日志已达保存上限'); }
            let body = previous.replace(/<!-- DAILY_LOG_METADATA[\s\S]*?-->/, '');
            let logSource = usedModel || /"source_kind"\s*:\s*"android_workflow_model"/.test(previous) ? 'android_workflow_model' : source;
            let envelope: SummaryMetadata = { workflow_metadata: { name: task.title, task_scene_id: task.sceneId, source_kind: logSource }, latest_entry_index: count + 1 };
            let metadata = JSON.stringify(envelope);
            facade.storage.writeTextAtomic(path, '<!-- DAILY_LOG_METADATA\n' + metadata + '\n-->\n' + body + '\n' + String(count + 1) + '. ' + result.resultSummary.slice(0, 2000) + '\n\n');
            CollectionReminderService.recordSuccessfulCollection().catch(() => {});
          }
          WorkflowStorage.updateTaskRun(filesDir, task.id, result.status, result.message +
            (result.resultSummary.length > 0 ? '\n' + result.resultSummary : ''));
          complete = true;
          if (result.status !== 'success') { throw new Error(result.message); }
          return;
        }
        if (poll.status !== 'running') { throw new Error('工作流未完成或已取消'); }
        await new Promise<void>((resolve): void => { setTimeout(resolve, 120); });
      }
      throw new Error('工作流超过60秒执行上限');
    } catch (error) {
      if (!complete) { WorkflowStorage.updateTaskRun(filesDir, task.id, cancellation.isCancelled() ? 'cancelled' : 'failed', (error as Error).message); }
      throw error as Error;
    } finally {
      model?.cancel();
      cancellation.setHandler(null);
      if (!complete && id.length > 0) { await bridge.callMethod('workflowCancel', id); }
    }
  }
}
