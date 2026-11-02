import { CloudModelClient, CloudChatCompletionOptions, CloudChatMessage } from '../utils/CloudModelClient';
import { MemoryQuery } from './MemoryQuery';

export class SharedAgent {
  static async run(client: CloudModelClient, options: CloudChatCompletionOptions,
    query: (raw: string) => string, active: () => boolean,
    delta: (text: string) => void, trace: (message: string) => void): Promise<void> {
    options.tools = [MemoryQuery.definition()];
    options.toolChoice = 'auto';
    let queries = 0;
    for (let turn = 0; turn < 4; turn++) {
      if (!active()) { throw new Error('用户取消请求'); }
      let response = await client.chatCompletionStream(options, { onContentDelta: (text: string): void => { if (active()) { delta(text); } } });
      if (!active()) { throw new Error('用户取消请求'); }
      if (response.toolCalls.length === 0) { return; }
      let assistant: CloudChatMessage = { role: 'assistant', content: response.content, tool_calls: response.toolCalls };
      options.messages.push(assistant);
      for (let call of response.toolCalls) {
        if (++queries > 3) { throw new Error('已达到本轮3次记忆查询上限，请缩小范围后重试'); }
        if (!active()) { throw new Error('用户取消请求'); }
        let result = call.function.name === 'query_events' ? query(call.function.arguments) :
          JSON.stringify({ ok: false, error: '此工具未注册，未执行' });
        trace(call.function.name === 'query_events' ? '已查询本机记忆；仅将有界事实返回配置的模型' : '拒绝未注册工具：' + call.function.name.slice(0, 60));
        options.messages.push({ role: 'tool', content: result, tool_call_id: call.id });
      }
    }
    throw new Error('模型工具轮次已达上限，未继续执行');
  }
}
