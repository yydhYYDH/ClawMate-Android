import { replaceChecked } from './source-patches.mjs';

// Reuse the pinned HarmonyOS orchestration, not a second summary algorithm.
// Only its configuration, transport and cancellation ports differ on Android.
export function buildChatContextSource(index) {
  const declarations = index.match(/type CloudChatContextCompressionAction =[^]*?\ninterface CloudChatContextPreparation \{[^]*?\n\}/)?.[0];
  if (!declarations) throw new Error('Upstream chat context result contract changed');
  const names = ['isCurrentRequestAtCloudContextTail', 'compactContextSummarySource', 'roleNameForContextSummary',
    'formatChatMessagesForContextSummary', 'olderMessagesAfterSummaryCoverage', 'olderMessagesAfterTailSummaryCoverage',
    'requestCloudChatContextSummary', 'buildCloudChatContextSummaryFromMessages', 'buildCloudChatContextSummary',
    'prepareCloudChatContext'];
  let methods = names.map(name => {
    const method = index.match(new RegExp(`  private (?:async )?${name}\\([^]*?\\n  \\}`))?.[0];
    if (!method) throw new Error('Upstream context method changed: ' + name);
    return method;
  }).join('\n\n');
  methods = replaceChecked(methods, 'this.currentChatContextBudgetSettings()', 'this.settings');
  methods = replaceChecked(methods, 'this.cloudChatSystemMessage()', 'this.systemMessage');
  methods = replaceChecked(methods, /    let config = this.getCloudConfig\(\);[^]*?    return summary.trim\(\);/,
    '    this.ensureActive();\n    let summary = await this.summarize(messages);\n    this.ensureActive();\n    return summary.trim().slice(0, 16000);');
  methods = replaceChecked(methods, '      if (this.cloudChatCancelRequested) {\n        throw e as Error;\n      }',
    '      this.ensureActive();');
  methods = replaceChecked(methods,
    "      AppLogger.warnCause('CloudAgent', 'chat context summary request failed, fallback to local summary', e as Error);", '');
  methods = replaceChecked(methods, '    return buildLocalExtractiveChatSummary(previousSummary, newOlderMessages);',
    '    this.ensureActive();\n    this.usedLocalFallback = true;\n    return buildLocalExtractiveChatSummary(previousSummary, newOlderMessages);');
  methods = replaceChecked(methods, 'summary = buildLocalExtractiveChatSummary(summary, deltaOlderMessages);',
    'this.usedLocalFallback = true;\n          summary = buildLocalExtractiveChatSummary(summary, deltaOlderMessages);', { all: true });
  methods = replaceChecked(methods, '  private async prepareCloudChatContext(', '  async prepare(');
  return `import { ChatMessage, ChatSessionRecord } from '../pages/chat/ChatSessionTypes';
import { CloudChatMessage } from '../utils/CloudModelClient';
import { ChatContextBudgetSettings, ChatContextCompressionSplit, messagesWithoutCurrentRequest,
  buildCloudContextMessagesWithCurrentRequest, estimateCloudContextTokens,
  messagesAfterKnownContextSummaryCoverage, messagesAfterExistingContextSummaryCoverage,
  buildCloudContextMessagesAfterExistingSummaryCoverage, nonRetreatingContextSummaryUntilMessageId,
  contextSummaryMessageCountForCoverage, splitChatMessagesForContextCompressionExact,
  shouldCompressCloudContext, buildLocalExtractiveChatSummary,
  shouldAppendContextCompressionNotice } from '../pages/chat/ChatContextBudget';
${declarations.replace('type CloudChatContextCompressionAction', 'export type CloudChatContextCompressionAction')
    .replace('interface CloudChatContextPreparation', 'export interface CloudChatContextPreparation')}
export class UpstreamChatContext {
  usedLocalFallback: boolean = false;
  private settings: ChatContextBudgetSettings;
  private systemMessage: CloudChatMessage;
  private summarize: (messages: CloudChatMessage[]) => Promise<string>;
  private ensureActive: () => void;
  constructor(settings: ChatContextBudgetSettings, systemMessage: CloudChatMessage,
    summarize: (messages: CloudChatMessage[]) => Promise<string>, ensureActive: () => void) {
    this.settings = settings; this.systemMessage = systemMessage;
    this.summarize = summarize; this.ensureActive = ensureActive;
  }
${methods}
}
`;
}
