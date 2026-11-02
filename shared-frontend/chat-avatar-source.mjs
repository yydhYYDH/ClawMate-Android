import { replaceChecked } from './source-patches.mjs';

// Checked overlays keep the original visual states/tap animation and trace click owner.
export function buildChatAvatarView(source) {
  source = replaceChecked(source, '  @Prop useAssistantMascot: boolean = false;',
    "  @Prop useAssistantMascot: boolean = false;\n  @Prop useChatAvatar: boolean = false;\n" +
    "  @StorageProp('androidChatAvatarStyle') @Watch('onAvatarForegroundUriChanged') private chatAvatarStyle: string = 'mascot';\n" +
    "  @StorageProp('androidChatAvatarHeroUri') @Watch('onAvatarForegroundUriChanged') private chatAvatarHero: string = '';\n" +
    "  @StorageProp('androidChatAvatarMessageUri') @Watch('onAvatarForegroundUriChanged') private chatAvatarMessage: string = '';");
  source = replaceChecked(source, 'this.useAssistantMascot', 'this.assistantMascotActive()', { all: true });
  source = replaceChecked(source, 'this.avatarForegroundUri', 'this.effectiveForegroundUri()', { all: true });
  source = replaceChecked(source, '  private hasForegroundImage(): boolean {',
    "  private effectiveForegroundUri(): string {\n    let custom = this.chatAvatarStyle === 'personal' ? (this.avatarSize === 'hero' ? this.chatAvatarHero : this.chatAvatarMessage) : '';\n    return (this.useChatAvatar ? custom : '') || this.avatarForegroundUri;\n  }\n" +
    "  private assistantMascotActive(): boolean {\n    return this.useAssistantMascot && (this.effectiveForegroundUri().length === 0 || this.imageLoadFailed);\n  }\n" +
    '  private hasForegroundImage(): boolean {');
  return source;
}

export function buildChatAvatarPage(source) {
  source = replaceChecked(source, 'digitalAvatarContainerWidth, digitalAvatarVisualStateForVoiceStatus', 'digitalAvatarContainerWidth, digitalAvatarContainerHeight, digitalAvatarVisualStateForVoiceStatus');
  source = replaceChecked(source, 'export struct ChatPage {',
    "export struct ChatPage {\n  @StorageProp('androidChatAvatarStyle') private chatAvatarStyle: string = 'mascot';\n" +
    "  @StorageProp('androidChatAvatarHeroUri') private chatAvatarHero: string = '';\n" +
    '  onToggleChatAvatar: () => Promise<void> = async () => {};');
  source = replaceChecked(source, 'useAssistantMascot: true,', 'useAssistantMascot: true, useChatAvatar: true,', { all: true, expected: 2 });
  source = replaceChecked(source, ".height(digitalAvatarContainerWidth('hero', true) + 8)", ".height((this.chatAvatarStyle === 'personal' && this.chatAvatarHero.length > 0 ? digitalAvatarContainerHeight('hero', true) : digitalAvatarContainerWidth('hero', true)) + 8)");
  source = replaceChecked(source, '      if (this.debugEnabled) {',
    "      Text(this.chatAvatarStyle === 'personal' ? '我的分身' : '默认形象')\n" +
    "        .fontSize(12).fontColor(AppTheme.text('#5968FF')).width(64).height(36).textAlign(TextAlign.Center)\n" +
    "        .accessibilityText(this.chatAvatarStyle === 'personal' ? '聊天形象：我的分身，点击切回默认助手' : '聊天形象：默认助手，点击使用我的分身')\n" +
    '        .onClick(async () => { await this.onToggleChatAvatar(); })\n      if (this.debugEnabled) {');
  return source;
}

export function buildChatAvatarMessages(source) {
  source = replaceChecked(source, 'useAssistantMascot: true,', 'useAssistantMascot: true, useChatAvatar: true,', { all: true, expected: 2 });
  source = replaceChecked(source, 'struct ChatMessageAvatarView {',
    "struct ChatMessageAvatarView {\n  @StorageProp('androidChatAvatarStyle') @Watch('resetTraceAvatar') private chatAvatarStyle: string = 'mascot';\n" +
    "  @StorageProp('androidChatAvatarMessageUri') @Watch('resetTraceAvatar') private chatAvatarMessage: string = '';\n" +
    "  @State traceImageFailed: boolean = false;\n  private resetTraceAvatar(): void { this.traceImageFailed = false; }\n" +
    "  private traceAvatarUri(): string { return this.chatAvatarStyle === 'personal' && !this.traceImageFailed ? this.chatAvatarMessage : ''; }\n" +
    "  private traceAvatarSource(): string | Resource { return this.traceAvatarUri().length > 0 ? this.traceAvatarUri() : $r('app.media.chat_assistant_avatar'); }\n");
  source = replaceChecked(source, "Image($r('app.media.chat_assistant_avatar'))", 'Image(this.traceAvatarSource())');
  source = replaceChecked(source, ".objectFit(ImageFit.Contain).accessibilityText('ClawMate 卡通猫助手，点击查看任务过程')",
    ".objectFit(ImageFit.Contain).accessibilityText(this.traceAvatarUri().length > 0 ? '个人生成形象，点击查看任务过程' : 'ClawMate 卡通猫助手，点击查看任务过程')\n" +
    '            .onError(() => { this.traceImageFailed = true; })');
  return source;
}
