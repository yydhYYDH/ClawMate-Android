import { AvatarHomeViewModel } from '../datamanager/avatar/AvatarTypes';

/** Cosmetic local preference only; images never enter chat messages or model requests. */
export class ChatAvatar {
  static style(): string { return AppStorage.get<string>('androidChatAvatarStyle') === 'personal' ? 'personal' : 'mascot'; }
  static publish(view: AvatarHomeViewModel, avatarAssetsRoot: string): void {
    let valid = /^[A-Za-z0-9_-]{1,160}$/.test(view.avatarVersionId);
    let root = 'file://' + avatarAssetsRoot.replace(/\\/g, '/').replace(/\/+$/g, '') + '/' + view.avatarVersionId;
    let hero = valid && view.avatarForegroundUri === root + '-foreground.png' ? view.avatarForegroundUri : '';
    let message = valid && view.avatarMessageForegroundUri === root + '-message-foreground.png' ? view.avatarMessageForegroundUri : '';
    AppStorage.setOrCreate('androidChatAvatarHeroUri', hero || message);
    AppStorage.setOrCreate('androidChatAvatarMessageUri', message || hero);
  }
  static available(): boolean { return (AppStorage.get<string>('androidChatAvatarHeroUri') || '').length > 0; }
  static image(size: string): string {
    if (ChatAvatar.style() !== 'personal') { return ''; }
    return AppStorage.get<string>(size === 'hero' ? 'androidChatAvatarHeroUri' : 'androidChatAvatarMessageUri') || '';
  }
}
