import { promptAction } from '@kit.ArkUI';
import { DataManagerFacade } from '../datamanager/DataManagerFacade';
import { AvatarGenerationMode, AvatarProviderConfig } from '../datamanager/avatar/AvatarTypes';
import { ChatImages } from './ChatImages';
import { AiServiceAccess } from '../utils/AiServiceAccess';
import { PrivacyConsent } from '../utils/PrivacyConsent';

/** Keep generation/version storage in the shared implementation. */
export class AndroidAvatar {
  private static busy: boolean = false;
  private static config(): AvatarProviderConfig {
    return { imageBaseUrl: (AppStorage.get<string>('avatarImageBaseUrl') || '').trim(),
      apiKey: AppStorage.get<string>('avatarImageApiKey') || '',
      textToImageModel: (AppStorage.get<string>('avatarTextToImageModel') || '').trim(),
      imageToImageModel: (AppStorage.get<string>('avatarImageToImageModel') || '').trim(),
      size: AppStorage.get<string>('avatarImageSize') || '1024x1024', responseFormat: 'b64_json', outputFormat: 'png',
      sequentialImageGeneration: 'disabled', stream: false, watermark: true, timeoutMs: 120000 };
  }
  static async generate(facade: DataManagerFacade, mode: AvatarGenerationMode, source: string,
    authorize: () => Promise<void>, consentTime: () => string): Promise<void> {
    if (AndroidAvatar.busy) { throw new Error('数字分身正在生成，请等待本次完成'); }
    if (mode !== 'text_to_image' && mode !== 'image_to_image') { throw new Error('不支持的数字分身生成方式'); }
    if (mode === 'image_to_image' && !source.startsWith('file:/')) { throw new Error('请先选择并保存本机参考照片'); }
    AndroidAvatar.busy = true;
    try {
      await authorize();
      let config = AndroidAvatar.config();
      let model = mode === 'image_to_image' ? config.imageToImageModel : config.textToImageModel;
      if (!/^https:\/\/[^/?#@\s]+(?:\/[^?#\s]*)?$/.test(config.imageBaseUrl) || model.length === 0 || config.apiKey.trim().length === 0) {
        throw new Error('请在“个人与隐私”配置 HTTPS 图片服务、API Key 和本次使用的生成模型');
      }
      let image = '';
      let request = facade.buildAvatarGenerateRequest(mode, mode === 'image_to_image' ? source : '', '', mode === 'image_to_image' ? source : '');
      if (mode === 'image_to_image') {
        let answer = await promptAction.showDialog({ title: '发送参考照片生成分身？',
          message: '将发送当前参考照片的去元数据 JPEG 副本和当前个人画像提示词至 ' + config.imageBaseUrl +
            '，模型 ' + model + '。仅用于本次生成，生成结果将保存在本机。\n画像提示词：\n' + request.prompt,
          buttons: [{ text: '取消', color: '#65718B' }, { text: '发送并生成', color: '#276EF1' }] });
        if (answer.index !== 1) { throw new Error('已取消数字分身生成，未发送照片'); }
        AiServiceAccess.requireAllowed(); PrivacyConsent.requireAccepted();
        image = (await ChatImages.prepare([source], (): boolean => false))[0];
        if (!image.startsWith('data:image/jpeg;base64,') || image.length > 1400000) { throw new Error('参考照片准备结果无效'); }
      }
      AiServiceAccess.requireAllowed(); PrivacyConsent.requireAccepted();
      let current = AndroidAvatar.config();
      if (JSON.stringify(current) !== JSON.stringify(config)) { throw new Error('图片服务配置已变化，请重新确认'); }
      request.sourceImage = image;
      request.consent.consented_at = consentTime();
      await facade.generateAvatar(config, request);
    } finally { AndroidAvatar.busy = false; }
  }
}
