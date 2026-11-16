import { CloudModelClient, CloudChatCompletionOptions, CloudChatMessage, CloudChatToolDefinition } from '../utils/CloudModelClient';
import { MemoryQuery } from './MemoryQuery';
import { PersonalSources } from './PersonalSources';
import { CollectedMemory } from './CollectedMemory';
import { runCodexTaskDefinition, parseCodexTaskArguments, codexTaskToolPayload, CodexRunOutcome, CodexToolRequest } from './CodexTool';

/**
 * Callbacks the chat page supplies so a Codex delegation can be launched and shown.
 *
 * Deliberately optional: when the page does not provide them, the tool is not offered to
 * the model at all rather than being registered and failing at call time.
 */
export interface CodexDelegation {
  /** Runs one delegation and resolves with its bounded outcome. */
  run: (request: CodexToolRequest) => Promise<CodexRunOutcome>;
  /** Called once a run id exists so the transcript can host a synchronisable card. */
  onCreated: (runId: string) => void;
}

/** Separate budgets so a slow delegation cannot consume the memory-query allowance. */
export interface SharedAgentBudgets {
  maxTurns: number;
  maxQueries: number;
  maxDelegations: number;
}

export const DEFAULT_SHARED_AGENT_BUDGETS: SharedAgentBudgets = { maxTurns: 4, maxQueries: 3, maxDelegations: 1 };

function phoneTaskDefinition(): CloudChatToolDefinition {
  const definition: CloudChatToolDefinition = {
    type: 'function',
    function: {
      name: 'run_phone_task',
      description: '仅当用户当前这条消息明确要求实际操作手机上的 App 时使用。用户将在手机上选择应用并确认。整条原始请求会交给手机助手，无需参数。',
      parameters: JSON.parse('{"type":"object","properties":{},"additionalProperties":false}') as Object
    }
  };
  return definition;
}

export class SharedAgent {
  static async run(client: CloudModelClient, options: CloudChatCompletionOptions,
    query: (raw: string) => string, active: () => boolean,
    delta: (text: string) => void, trace: (message: string) => void,
    codex?: CodexDelegation, budgets: SharedAgentBudgets = DEFAULT_SHARED_AGENT_BUDGETS,
    collections?: (raw: string) => Promise<string>, phone?: () => Promise<string>): Promise<void> {
    options.tools = [MemoryQuery.definition(), PersonalSources.definition('calendar'), PersonalSources.definition('mail')];
    if (collections !== undefined) { options.tools.push(CollectedMemory.definition()); }
    // The tool is only advertised when a delegation sink exists, so the model cannot call
    // something that would fail.
    if (codex !== undefined) { options.tools.push(runCodexTaskDefinition()); }
    if (phone !== undefined) { options.tools.push(phoneTaskDefinition()); }
    options.toolChoice = 'auto';
    let queries = 0;
    let delegations = 0;
    let phoneUsed = false;
    for (let turn = 0; turn < budgets.maxTurns; turn++) {
      if (!active()) { throw new Error('用户取消请求'); }
      let response = await client.chatCompletionStream(options, { onContentDelta: (text: string): void => { if (active()) { delta(text); } } });
      if (!active()) { throw new Error('用户取消请求'); }
      if (response.toolCalls.length === 0) { return; }
      let assistant: CloudChatMessage = { role: 'assistant', content: response.content, tool_calls: response.toolCalls };
      options.messages.push(assistant);
      for (let call of response.toolCalls) {
        if (!active()) { throw new Error('用户取消请求'); }
        if (call.function.name === 'run_phone_task') {
          let result = JSON.stringify({ status: 'blocked', message: '本轮未开启手机控制或已尝试过一次' });
          if (phone !== undefined && !phoneUsed) {
            phoneUsed = true;
            trace('等待确认 Mika 手机控制');
            result = await phone();
            trace('Mika 手机任务已结束');
          }
          if (!active()) { throw new Error('用户取消请求'); }
          options.messages.push({ role: 'tool', content: result, tool_call_id: call.id });
          continue;
        }
        if (call.function.name === 'run_codex_task') {
          let result: string;
          if (codex === undefined) {
            result = codexTaskToolPayload(unavailableOutcome());
            trace('拒绝未注册工具：run_codex_task');
          } else if (++delegations > budgets.maxDelegations) {
            result = codexTaskToolPayload(overBudgetOutcome(delegations, budgets.maxDelegations));
            trace('本轮 Codex 委托已达上限');
          } else {
            result = await SharedAgent.delegate(codex, call.function.arguments, active, trace);
          }
          if (!active()) { throw new Error('用户取消请求'); }
          options.messages.push({ role: 'tool', content: result, tool_call_id: call.id });
          continue;
        }
        // Memory, calendar and mail share one allowance; Codex has its own.
        if (++queries > budgets.maxQueries) { throw new Error('已达到本轮' + budgets.maxQueries.toString() + '次记忆查询上限，请缩小范围后重试'); }
        let source = call.function.name === 'query_calendar' ? 'calendar' : (call.function.name === 'query_mail' ? 'mail' : '');
        let collectionQuery = call.function.name === 'query_collections' && collections !== undefined;
        let result = source ? await PersonalSources.query(source, call.function.arguments, active, options) : call.function.name === 'query_events' ? query(call.function.arguments) :
          collectionQuery && collections !== undefined ? await collections(call.function.arguments) :
          JSON.stringify({ ok: false, error: '此工具未注册，未执行' });
        if (!active()) { throw new Error('用户取消请求'); }
        trace(collectionQuery ? '已处理本机采集查询，授权与覆盖情况见工具结果' : source ? '已处理' + (source === 'calendar' ? '日历' : '邮箱') + '只读查询；授权与覆盖情况见工具结果' : call.function.name === 'query_events' ? '已查询本机记忆；仅将有界事实返回配置的模型' : '拒绝未注册工具：' + call.function.name.slice(0, 60));
        options.messages.push({ role: 'tool', content: result, tool_call_id: call.id });
      }
    }
    throw new Error('模型工具轮次已达上限，未继续执行');
  }

  /** Parses the arguments and runs one delegation, reporting failures as a tool payload. */
  private static async delegate(codex: CodexDelegation, raw: string, active: () => boolean,
    trace: (message: string) => void): Promise<string> {
    let request: CodexToolRequest;
    try { request = parseCodexTaskArguments(raw); }
    catch (e) { return codexTaskToolPayload(invalidArgumentsOutcome((e as Error).message)); }
    if (!active()) { throw new Error('用户取消请求'); }
    trace(request.continueRunId.length > 0 ? '正在继续此前的 Codex 委托' : '正在委托 Codex 电脑执行任务');
    try {
      let outcome = await codex.run(request);
      if (outcome.runId.length > 0) { codex.onCreated(outcome.runId); }
      trace(outcome.ok ? 'Codex 委托完成' : 'Codex 委托未完成：' + outcome.status);
      return codexTaskToolPayload(outcome);
    } catch (e) {
      trace('Codex 委托中断，结果待同步');
      return codexTaskToolPayload(failedOutcome((e as Error).message));
    }
  }
}

function baseOutcome(): CodexRunOutcome {
  return { ok: false, status: '', runId: '', computer: '', answer: '', files: [], receivedIds: [], error: '' };
}

function invalidArgumentsOutcome(message: string): CodexRunOutcome {
  let outcome = baseOutcome(); outcome.status = 'invalid_arguments'; outcome.error = message; return outcome;
}

function unavailableOutcome(): CodexRunOutcome {
  let outcome = baseOutcome(); outcome.status = 'unavailable';
  outcome.error = '当前聊天入口未接入 Codex 委托，请使用 App 云端聊天并先在 Codex 页保存电脑连接。';
  return outcome;
}

function overBudgetOutcome(used: number, limit: number): CodexRunOutcome {
  let outcome = baseOutcome(); outcome.status = 'rejected';
  outcome.error = '本轮最多委托 ' + limit.toString() + ' 次 Codex 任务，已调用 ' + used.toString() + ' 次。';
  return outcome;
}

function failedOutcome(message: string): CodexRunOutcome {
  let outcome = baseOutcome(); outcome.status = 'failed'; outcome.error = message; return outcome;
}
