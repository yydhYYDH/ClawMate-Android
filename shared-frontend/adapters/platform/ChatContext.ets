import { CloudModelClient, CloudChatCompletionOptions, CloudChatMessage } from '../utils/CloudModelClient';
import { ChatMessage, ChatSessionRecord, CHAT_MODE_CLOUD_AGENT } from '../pages/chat/ChatSessionTypes';
import { ChatContextBudgetSettings } from '../pages/chat/ChatContextBudget';
import { UpstreamChatContext, CloudChatContextPreparation } from './UpstreamChatContext';

export interface PreparedChatContext { result: CloudChatContextPreparation; localExtraction: boolean; }
export interface ChatSummaryState { text: string; updatedAt: number; until: string; count: number; }

export class ChatContext {
  static async prepare(record: ChatSessionRecord, current: ChatMessage, settings: ChatContextBudgetSettings,
    system: CloudChatMessage, client: CloudModelClient, config: CloudChatCompletionOptions,
    ensureActive: () => void): Promise<PreparedChatContext> {
    ensureActive();
    if (record.mode !== CHAT_MODE_CLOUD_AGENT) { throw new Error('本地会话不能进入云端摘要；请新建云端会话'); }
    let previous = (record.contextSummary || '').trim();
    let coverage = record.contextSummaryUntilMessageId || '';
    if (previous.length > 0 && (coverage.length === 0 || !record.messages.some((message: ChatMessage): boolean => message.id === coverage))) {
      throw new Error('已有摘要的覆盖点缺失，未重新上传旧历史；请新建会话，原文仍保存在本机');
    }
    let engine = new UpstreamChatContext(settings, system, async (messages: CloudChatMessage[]): Promise<string> => {
      ensureActive();
      let options: CloudChatCompletionOptions = { baseUrl: config.baseUrl, model: config.model, apiKey: config.apiKey,
        messages: messages, temperature: 0.7, maxTokens: 4096, readTimeout: 120000,
        top_p: 0.8, top_k: 20, min_p: 0.0, presence_penalty: 1.5, repetition_penalty: 1.0,
        logResponseContent: false, chatTemplateKwargs: { enable_thinking: false } };
      let summary = await client.chatCompletionText(options);
      ensureActive();
      return summary;
    }, ensureActive);
    let result = await engine.prepare(record, current);
    ensureActive();
    if (!result.currentRequestAtTail || result.overBudgetAfterCompression) {
      throw new Error('摘要后当前请求仍超出上下文预算，请减少消息或附件，或新建会话；历史未删除');
    }
    return { result: result, localExtraction: engine.usedLocalFallback };
  }
  static capture(record: ChatSessionRecord): ChatSummaryState {
    return { text: record.contextSummary || '', updatedAt: record.contextSummaryUpdatedAt || 0,
      until: record.contextSummaryUntilMessageId || '', count: record.contextSummaryMessageCount || 0 };
  }
  static restore(record: ChatSessionRecord, state: ChatSummaryState): void {
    record.contextSummary = state.text; record.contextSummaryUpdatedAt = state.updatedAt;
    record.contextSummaryUntilMessageId = state.until; record.contextSummaryMessageCount = state.count;
  }
  static apply(record: ChatSessionRecord, prepared: PreparedChatContext, now: number): void {
    let value = prepared.result;
    if (value.summaryUpdated) {
      record.contextSummary = value.summary; record.contextSummaryUpdatedAt = now;
      record.contextSummaryUntilMessageId = value.summaryUntilMessageId; record.contextSummaryMessageCount = value.summaryMessageCount;
    }
  }
  static sourceText(prepared: PreparedChatContext): string {
    if (prepared.result.compressionAction === 'reused') { return '已复用本机会话保存的上下文摘要；完整历史仍保留'; }
    return prepared.localExtraction ? '上下文摘要含本地抽取，可能省略细节；完整历史仍保留在本机' :
      '已使用当前模型压缩较早历史；摘要仅用于续接对话，完整历史仍保留在本机';
  }
}
