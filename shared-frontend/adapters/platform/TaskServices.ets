import { WorkflowStep, WorkflowTask } from '../utils/WorkflowTypes';

export const ANDROID_PC_TASK_PENDING: string = '电脑控制待接入 Android 设备桥接。可切换到「手机自己控制」运行本机工作流。';

export function isBuiltInAlipayCollection(task: WorkflowTask): boolean {
  return task.id === 'default_alipay_bill' && task.isUserModified !== true;
}

/** Describes services a task may call, including steps inside conditions and loops. */
export function taskServiceLabel(steps: WorkflowStep[]): string {
  let pending: WorkflowStep[][] = [steps];
  let visual = false;
  let other = false;
  let count = 0;
  while (pending.length > 0) {
    let group = pending.pop();
    if (!Array.isArray(group)) { return '检查任务配置'; }
    for (let step of group) {
      if (!step || ++count > 256) { return '检查任务配置'; }
      if (step.type === 'gui_task' || (step.type === 'gui_action' && step.action !== 'sleep')) {
        return '自动 GUI · 待接入';
      }
      if (step.type === 'tool') {
        if (step.tool_name === 'vlm_qa') { visual = true; }
        else if (step.tool_name !== 'echo' && step.tool_name !== 'summary') { other = true; }
      } else if (step.type !== 'gui_action' && step.type !== 'loop' && step.type !== 'if') {
        other = true;
      }
      for (let branch of [step.steps, step.then_steps, step.else_steps]) {
        if (branch !== undefined) { pending.push(branch); }
      }
    }
  }
  if (other) { return '含待适配步骤'; }
  return visual ? '视觉问答 · 发送前确认' : '本机工作流';
}
