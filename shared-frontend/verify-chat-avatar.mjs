import assert from 'node:assert/strict';
import fs from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
import { buildChatAvatarView, buildChatAvatarPage, buildChatAvatarMessages } from './chat-avatar-source.mjs';
const base='.arkui-x-validation/application/project/entry/src/main/ets/';
const read=path=>fs.readFileSync(path,'utf8');
const execute=(source,tail,ports)=>runInNewContext(stripTypeScriptTypes(source.replace(/^import\s+[^;]+;\s*/gm,'')).replace(/^export /gm,'')+'\n'+tail,ports);
const values=new Map(),AppStorage={get:key=>values.get(key),setOrCreate:(key,value)=>values.set(key,value)};
const Avatar=execute(read('shared-frontend/adapters/platform/ChatAvatar.ets'),'ChatAvatar;',{AppStorage});
const assets='/private/files/workflows/avatar/assets';
const root='file://'+assets+'/v1';
const profile=()=>({avatarVersionId:'v1',avatarForegroundUri:root+'-foreground.png',avatarMessageForegroundUri:root+'-message-foreground.png'});
function reset(){values.clear();Avatar.publish(profile(),assets);}
let passed=0;async function check(name,fn){await fn();passed++;console.log('PASS '+name);}
await check('default is mascot; opting in selects original full image and message cache separately',()=>{reset();assert.equal(Avatar.style(),'mascot');assert.equal(Avatar.image('hero'),'');values.set('androidChatAvatarStyle','personal');assert.equal(Avatar.image('hero'),root+'-foreground.png');assert.equal(Avatar.image('message'),root+'-message-foreground.png');});
await check('asset root comes from original facade paths, without guessing Android/ArkUI-X files directory nesting',()=>{reset();Avatar.publish(profile(),assets+'/');values.set('androidChatAvatarStyle','personal');assert.equal(Avatar.image('hero'),root+'-foreground.png');assert.ok(read('shared-frontend/app/Index.ets').includes('ChatAvatar.publish(this.profile, this.facade.paths.avatarAssetsRoot)'));});
await check('untrusted version or URI cannot become chat image or network content',()=>{
 for(const patch of [{avatarVersionId:'../escape'},{avatarForegroundUri:'https://example.invalid/avatar.png',avatarMessageForegroundUri:'data:image/png;base64,AA'},{avatarForegroundUri:root+'/../v1-foreground.png',avatarMessageForegroundUri:'file:///another/app/private.png'}]){
  reset();Avatar.publish({...profile(),...patch},assets);assert.equal(Avatar.available(),false);
 }
});
await check('partial caches use existing local counterpart; removed version clears derived image state',()=>{reset();Avatar.publish({...profile(),avatarMessageForegroundUri:''},assets);values.set('androidChatAvatarStyle','personal');assert.equal(Avatar.image('message'),root+'-foreground.png');Avatar.publish({...profile(),avatarVersionId:''},assets);assert.equal(Avatar.available(),false);assert.equal(Avatar.image('hero'),'');});
await check('unknown stored choices fall back to mascot and updated current version replaces both old caches',()=>{reset();values.set('androidChatAvatarStyle','future-option');assert.equal(Avatar.image('hero'),'');values.set('androidChatAvatarStyle','personal');Avatar.publish({avatarVersionId:'v2',avatarForegroundUri:root.replace('v1','v2')+'-foreground.png',avatarMessageForegroundUri:root.replace('v1','v2')+'-message-foreground.png'},assets);assert.ok(Avatar.image('message').includes('/v2-message-'));});

const generated=read(base+'pages/chat/DigitalAvatarView.ets');
function method(source,name){const found=source.match(new RegExp(`  private (?:async )?${name}\\([^]*?\\n  \\}`));assert.ok(found,name);return found[0];}
const methods=['effectiveForegroundUri','assistantMascotActive','hasForegroundImage','hasAvatarFrame','foregroundImageSource'];
const View=execute('class View {\n'+methods.map(name=>method(generated,name)).join('\n')+'\n}','View;',{$r:path=>'resource:'+path});
await check('actual view methods choose custom images across visual states and recover to mascot on load failure',()=>{
 const v=new View();Object.assign(v,{useChatAvatar:true,useAssistantMascot:true,chatAvatarStyle:'personal',chatAvatarHero:'full',chatAvatarMessage:'thumb',avatarForegroundUri:'',imageLoadFailed:false,avatarSize:'hero'});
 for(const state of ['idle','listening','thinking','speaking','interrupted']){v.visualState=state;assert.equal(v.foregroundImageSource(),'full');assert.equal(v.assistantMascotActive(),false);}
 v.avatarSize='message';assert.equal(v.foregroundImageSource(),'thumb');v.imageLoadFailed=true;assert.equal(v.assistantMascotActive(),true);assert.equal(v.hasForegroundImage(),false);
 v.imageLoadFailed=false;v.chatAvatarStyle='mascot';assert.match(v.foregroundImageSource(),/^resource:/);
 v.useChatAvatar=false;v.useAssistantMascot=false;v.avatarForegroundUri='explicit';assert.equal(v.foregroundImageSource(),'explicit');
});
await check('voice mapping animates only the active message and keeps original tap/timer behavior',()=>{
 const types=execute(read(base+'pages/chat/DigitalAvatarTypes.ets'),'({digitalAvatarVisualStateForVoiceStatus,digitalAvatarVisualStateForMessageVoice});',{});
 for(const [voice,state]of [['listening','listening'],['synthesizing','thinking'],['speaking','speaking'],['paused','interrupted']]){
  assert.equal(types.digitalAvatarVisualStateForVoiceStatus(voice),state);assert.equal(types.digitalAvatarVisualStateForMessageVoice(voice,'a','a'),state);assert.equal(types.digitalAvatarVisualStateForMessageVoice(voice,'a','b'),'idle');
 }
 const original=read('.arkui-x-validation/reference/entry/src/main/ets/pages/chat/DigitalAvatarView.ets');
 for(const name of ['handleAvatarClick','clearTapFrameTimers','clearTapUnlockTimer'])assert.equal(method(generated,name),method(original,name));
});
await check('checked source overlays reach welcome, empty, message, thinking and static task trace without intercepting trace click',()=>{
 const page=read(base+'pages/chat/ChatPage.ets'),messages=read(base+'pages/chat/ChatMessageList.ets');
 assert.equal((page.match(/useChatAvatar: true/g)||[]).length,2);assert.equal((messages.match(/useChatAvatar: true/g)||[]).length,2);
 assert.ok(messages.includes('Image(this.traceAvatarSource())'));assert.ok(messages.includes('this.onToggleTaskTrace(this.messageId);'));
 assert.ok(page.includes("from '../../platform/InputMethod'"));assert.ok(page.includes('PlatformUi.clearFocus()'));
 for(const build of [buildChatAvatarView,buildChatAvatarPage,buildChatAvatarMessages])assert.throws(()=>build('source changed'),/drift/);
});
await check('style persists through actual SettingsStore save/reinitialization; derived private image URIs stay transient',()=>{
 reset();const saved=new Map();const store={hasSync:k=>saved.has(k),getSync:(k,d)=>saved.get(k)??d,putSync:(k,v)=>saved.set(k,v),flushSync(){}};
 const keys=execute(read(base+'platform/SettingsKeys.ets'),'({SETTINGS_STRING_KEYS,SETTINGS_BOOLEAN_KEYS});',{});
 const Settings=execute(read('shared-frontend/adapters/platform/SettingsStore.ets'),'SettingsStore;',{...keys,AppStorage,preferences:{getPreferencesSync:()=>store}});
 Settings.initialize({});values.set('androidChatAvatarStyle','personal');Settings.save();assert.equal(saved.get('androidChatAvatarStyle'),'personal');assert.ok(!saved.has('androidChatAvatarHeroUri'));values.clear();Settings.initialize({});assert.equal(Avatar.style(),'personal');
});
await check('actual host toggle refuses unavailable assets and rolls preference back on persistence failure',async()=>{
 const toasts=[];let fail=false;const Host=execute('class Host {\n'+method(read('shared-frontend/app/Index.ets'),'toggleChatAvatar')+'\n}','Host;',{AppStorage,ChatAvatar:Avatar,SettingsStore:{save(){if(fail)throw Error('disk failure');}},showToastSafely:text=>toasts.push(text)});
 const h=new Host();h.restoreAvatarForeground=async()=>{};values.clear();await h.toggleChatAvatar();assert.equal(Avatar.style(),'mascot');assert.match(toasts.at(-1),/首页生成/);
 reset();await h.toggleChatAvatar();assert.equal(Avatar.style(),'personal');fail=true;await h.toggleChatAvatar();assert.equal(Avatar.style(),'personal');fail=false;await h.toggleChatAvatar();assert.equal(Avatar.style(),'mascot');
});
console.log(JSON.stringify({passed,scope:'Actual shared selection, settings and generated view methods; native rendered image and trace interaction verified separately'}));
