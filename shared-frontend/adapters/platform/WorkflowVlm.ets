import { promptAction } from '@kit.ArkUI';
import Bridge from '@arkui-x.bridge';
import { CloudModelClient, CloudChatCompletionOptions, CloudContentPart } from '../utils/CloudModelClient';
import { PrivacyConsent } from '../utils/PrivacyConsent';
import { AiServiceAccess } from '../utils/AiServiceAccess';
import { ChatImages } from './ChatImages';
import { GalleryPhotoPicker } from '../utils/gallery/GalleryPhotoPicker';
import { GalleryStorage } from '../utils/gallery/GalleryStorage';
import { WorkflowStorage } from '../utils/WorkflowStorage';
import { util } from '@kit.ArkTS';
import fs from '@ohos.file.fs';

export interface WorkflowVlmRequest { id: string; mode: string; question: string; image: string; schema?: Object; }
export class WorkflowVlm {
  static async pickImage(filesDir: string, cancelled: () => boolean): Promise<string> {
    let selected = await new GalleryPhotoPicker().selectImageUris(1);
    if (selected.length === 0 || cancelled()) { return ''; }
    let images = await ChatImages.prepare(selected, cancelled);
    if (cancelled()) { return ''; }
    let data = images[0];
    if (!data.startsWith('data:image/jpeg;base64,') || data.length > 1400000) { throw new Error('图片准备结果无效'); }
    let bytes = new util.Base64Helper().decodeSync(data.slice(data.indexOf(',') + 1)).slice().buffer as ArrayBuffer;
    let directory = filesDir + '/workflows/images';
    WorkflowStorage.ensureDirectory(directory);
    let path = directory + '/image-' + Date.now().toString() + '-' + Math.floor(Math.random() * 1000000).toString() + '.jpg';
    try {
      GalleryStorage.writeBytes(path + '.part', bytes);
      if (fs.statSync(path + '.part').size !== bytes.byteLength) { throw new Error('工作流图片保存不完整'); }
      fs.renameSync(path + '.part', path);
      return 'file://' + path;
    } catch (error) { try { fs.unlinkSync(path + '.part'); } catch (ignored) {} throw error as Error; }
  }
  private client: CloudModelClient = new CloudModelClient();
  private stopped: boolean = false;
  cancel(): void { this.stopped = true; this.client.cancelActiveRequests(); }
  private config(): CloudChatCompletionOptions {
    let baseUrl = (AppStorage.get<string>('cloudDeciderBaseUrl') || '').trim();
    let model = (AppStorage.get<string>('cloudDeciderModel') || '').trim();
    if (!/^https:\/\/[^/?#@\s]+(?::\d+)?(?:\/[^?#\s]*)?$/.test(baseUrl) || model.length === 0) {
      throw new Error('请在“我的 → 云模型”填写 HTTPS Decider 地址和视觉模型名');
    }
    return { baseUrl: baseUrl, model: model, apiKey: AppStorage.get<string>('cloudDeciderApiKey') || AppStorage.get<string>('cloudApiKey') || '',
      messages: [], readTimeout: 30000, maxTokens: 2048, logResponseContent: false, chatTemplateKwargs: { enable_thinking: false } };
  }
  async execute(run: string, request: WorkflowVlmRequest, cancelled: () => boolean, authorize: () => Promise<void>): Promise<string> {
    let bridge = Bridge.createBridge('ClawMatePlatform');
    let ensure = async (): Promise<void> => {
      if (this.stopped || cancelled()) { throw new Error('视觉问答已取消'); }
      if (!await bridge.callMethod('workflowToolPending', run, request.id)) { throw new Error('工作流已结束或当前未在前台解锁'); }
    };
    await ensure();
    if (request.mode !== 'answer' && request.mode !== 'summary') { throw new Error('不支持的视觉问答模式'); }
    if (request.question.length < 1 || request.question.length > 8000 || !request.image.startsWith('file:/')) {
      throw new Error('视觉问答需要问题和应用私有图片引用；请先将图片保存到共享应用');
    }
    let config = this.config();
    await authorize(); await ensure();
    let answer = await promptAction.showDialog({ title: '发送图片进行视觉问答？',
      message: '将发送1张图片和以下完整问题至 ' + config.baseUrl + '，模型 ' + config.model +
        '。\n图片：' + request.image + '\n问题：' + request.question +
        (request.schema ? '\n包含任务中配置的JSON输出要求。' : '') + '\n' +
        (request.mode === 'summary' ? '成功摘要将保存到本机日志。' : '回答将保存在本次运行结果中。'),
      buttons: [{ text: '取消', color: '#65718B' }, { text: '发送图片', color: '#276EF1' }] });
    if (answer.index !== 1) { throw new Error('用户取消视觉问答，未发送图片'); }
    await ensure();
    let latest = this.config();
    if (latest.baseUrl !== config.baseUrl || latest.model !== config.model || latest.apiKey !== config.apiKey) { throw new Error('模型配置已变化，请重新运行并确认'); }
    AiServiceAccess.requireAllowed(); PrivacyConsent.requireAccepted();
    let images = await ChatImages.prepare([request.image], (): boolean => this.stopped || cancelled());
    await ensure();
    AiServiceAccess.requireAllowed(); PrivacyConsent.requireAccepted();
    let finalConfig = this.config();
    if (finalConfig.baseUrl !== config.baseUrl || finalConfig.model !== config.model || finalConfig.apiKey !== config.apiKey) { throw new Error('模型配置已变化，未发送图片'); }
    let question = (request.mode === 'summary' ? '请基于给定图片做总结：' : '') + request.question;
    if (request.schema) { question += '\n请严格输出JSON对象，不要Markdown或额外解释。JSON输出要求：' + JSON.stringify(request.schema); }
    let parts: CloudContentPart[] = [{ type: 'image_url', image_url: { url: images[0] } }, { type: 'text', text: question }];
    config.messages = [{ role: 'user', content: parts }];
    let watching = false;
    let timer = setInterval((): void => {
      if (watching) { return; }
      watching = true;
      ensure().then((): void => {
        let current = this.config();
        AiServiceAccess.requireAllowed(); PrivacyConsent.requireAccepted();
        if (current.baseUrl !== config.baseUrl || current.model !== config.model || current.apiKey !== config.apiKey) { throw new Error('配置已变化'); }
      }).catch((): void => { this.cancel(); }).finally((): void => { watching = false; });
    }, 250);
    let response = '';
    try { response = await this.client.chatCompletionText(config); }
    finally { clearInterval(timer); }
    await ensure();
    AiServiceAccess.requireAllowed(); PrivacyConsent.requireAccepted();
    let after = this.config();
    if (after.baseUrl !== config.baseUrl || after.model !== config.model || after.apiKey !== config.apiKey) { throw new Error('模型配置已变化，已丢弃返回结果'); }
    if (response.trim().length === 0 || response.length > 16000) { throw new Error('视觉模型结果为空或超出限制'); }
    return response;
  }
}
