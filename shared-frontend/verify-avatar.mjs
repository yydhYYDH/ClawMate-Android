import assert from 'node:assert/strict';
import fs from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
function module(file,name,ports) {
 const source=fs.readFileSync(file,'utf8').replace(/^import\s+[^;]+;\s*/gm,'');
 return runInNewContext(stripTypeScriptTypes(source).replace(/^export /gm,'')+`\n${name};`,{...ports});
}
let passed=0;
async function check(name,fn){await fn();passed++;console.log('PASS '+name);}
const base='.arkui-x-validation/application/project/entry/src/main/ets/';
function harness(){
 const settings=new Map([['avatarImageBaseUrl','https://avatar-fixture.invalid/images/generations'],['avatarImageApiKey','synthetic-key'],['avatarTextToImageModel','text-model'],['avatarImageToImageModel','reference-model']]);
 const state={allowed:true,choice:1,prepared:0,requests:[],versions:[],assets:[],dialogs:[],savedReference:[],current:{current_version_id:'previous',profile_snapshot_hash:'previous-hash',reference_photo_path:'assets/reference.jpg',status:'current'},prepare:async()=>['data:image/jpeg;base64,/9j/2Q==']};
 const gates={AiServiceAccess:{requireAllowed(){assert.ok(state.allowed,'revoked');}},PrivacyConsent:{requireAccepted(){assert.ok(state.allowed,'revoked');},createHttp(){return{async request(url,options){state.requests.push({url,options});if(state.networkFail)throw Error('controlled provider failure');return{responseCode:200,result:JSON.stringify({id:'provider-fixture',data:[{b64_json:'controlled-generated-image'}]})};}};},destroyHttp(){}}};
 const Provider=module(base+'datamanager/avatar/SeedreamAvatarProvider.ets','SeedreamAvatarProvider',{...gates,http:{RequestMethod:{POST:'POST',GET:'GET'},HttpDataType:{STRING:0,ARRAY_BUFFER:1}},ArkJSON:JSON,parseJsonCompat:JSON.parse,createEmptyRawMetadata:()=>({})});
 const Service=module(base+'datamanager/avatar/AvatarService.ets','AvatarService',{
  SeedreamAvatarProvider:Provider,AvatarForegroundService:class{async ensureForegroundForVersion(){throw Error('segmentation unavailable');}},dataManagerWarn(){},nowIsoLocal:()=> '2026-10-28T12:00:00',avatarErrorMessage:(e)=>e.message,
  ChatImages:{async prepare(){return state.prepare();}},util:{Base64Helper:class{decodeSync(){return Uint8Array.from([9,255,216,255,217,9]).subarray(1,5);}}},
  AvatarPromptBuilder:class{buildSnapshot(){return{};}buildPrompt(){return 'synthetic profile prompt';}snapshotHash(){return 'profile-hash';}},
  AvatarStorage:class{ensureReady(){}loadState(){return state.current;}saveState(v){state.current=v;}saveVersion(v){state.versions.push(v);}loadVersion(){return null;}saveAssetBase64(id,data){state.assets.push(data);return 'assets/'+id+'.png';}homeViewFromState(v){return v;}saveReferencePhotoBytes(bytes){state.savedReference.push([...bytes]);return 'assets/private-reference.jpg';}}
 });
 const service=new Service({},{});
 const facade={buildAvatarGenerateRequest:(...args)=>service.buildGenerateRequest({},1,...args),generateAvatar:(...args)=>service.generate(...args)};
 const Controller=module('shared-frontend/adapters/platform/AndroidAvatar.ets','AndroidAvatar',{...gates,AppStorage:{get:k=>settings.get(k)},ChatImages:{async prepare(uris){state.prepared++;assert.equal(uris.length,1);return state.prepare();}},promptAction:{async showDialog(dialog){state.dialogs.push(dialog);if(state.dialog)await state.dialog();return{index:state.choice};}}});
 return {state,settings,service,run:(mode='image_to_image',source='file:///private/reference.jpg')=>Controller.generate(facade,mode,source,async()=>gates.AiServiceAccess.requireAllowed(),()=> 'approved-at')};
}
await check('confirmed reference uses image model, prepared JPEG and original shared version/provenance storage',async()=>{
 const h=harness();await h.run();const q=JSON.parse(h.state.requests[0].options.extraData);
 assert.equal(q.model,'reference-model');assert.equal(q.image,'data:image/jpeg;base64,/9j/2Q==');assert.equal(q.watermark,true);
 assert.equal(h.state.versions.length,1);const v=h.state.versions[0];assert.equal(v.mode,'image_to_image');assert.equal(v.consent_snapshot.photo_used,true);assert.equal(v.consent_snapshot.photo_source,'file:///private/reference.jpg');assert.equal(v.consent_snapshot.consented_at,'approved-at');
 assert.ok(h.state.dialogs[0].message.includes('synthetic profile prompt'));assert.ok(!h.state.dialogs[0].message.includes('synthetic-key'));
 assert.equal(h.state.current.status,'current');assert.equal(h.state.assets[0],'controlled-generated-image');
});
await check('cancel sends no image and changes no version/state',async()=>{const h=harness();h.state.choice=0;await assert.rejects(h.run(),/取消/);assert.equal(h.state.prepared,0);assert.equal(h.state.requests.length,0);assert.equal(h.state.current.current_version_id,'previous');});
await check('unsafe sources and incomplete service configuration cannot upload',async()=>{
 for(const source of ['', 'https://untrusted.invalid/photo.jpg','data:image/jpeg;base64,abc']){const h=harness();await assert.rejects(h.run('image_to_image',source),/参考照片/);assert.equal(h.state.requests.length,0);}
 for(const endpoint of ['http://avatar.invalid','https://user:secret@avatar.invalid','https://avatar.invalid?key=x']){const h=harness();h.settings.set('avatarImageBaseUrl',endpoint);await assert.rejects(h.run(),/HTTPS/);assert.equal(h.state.prepared,0);}
 const h=harness();h.settings.set('avatarImageToImageModel','');await assert.rejects(h.run(),/生成模型/);assert.equal(h.state.requests.length,0);
});
await check('text-only generation has no photo, no photo consent and selects text model',async()=>{const h=harness();await h.run('text_to_image','');assert.equal(h.state.prepared,0);assert.equal(h.state.dialogs.length,0);const q=JSON.parse(h.state.requests[0].options.extraData);assert.equal(q.model,'text-model');assert.ok(!('image' in q));assert.equal(h.state.versions[0].consent_snapshot.photo_used,false);});
await check('photo-only configuration does not require a text model',async()=>{const h=harness();h.settings.set('avatarTextToImageModel','');await h.run();assert.equal(h.state.versions[0].model,'reference-model');});
await check('configuration change or authorization withdrawal during preparation prevents upload',async()=>{for(const mode of ['config','access']){const h=harness();h.state.prepare=async()=>{if(mode==='config')h.settings.set('avatarImageToImageModel','changed');else h.state.allowed=false;return['data:image/jpeg;base64,/9j/2Q=='];};await assert.rejects(h.run(),/变化|revoked/);assert.equal(h.state.requests.length,0);assert.equal(h.state.versions.length,0);}});
await check('invalid preparation output and decode failures never reach the provider',async()=>{for(const result of ['https://untrusted.invalid/image','data:image/png;base64,AA','data:image/jpeg;base64,'+'A'.repeat(1400000)]){const h=harness();h.state.prepare=async()=>[result];await assert.rejects(h.run(),/准备结果/);assert.equal(h.state.requests.length,0);}});
await check('generation failure retains old current version and next request can retry',async()=>{const h=harness();h.state.networkFail=true;await assert.rejects(h.run(),/provider failure/);assert.equal(h.state.current.status,'failed');assert.equal(h.state.current.current_version_id,'previous');assert.equal(h.state.versions.length,0);h.state.networkFail=false;await h.run();assert.equal(h.state.current.status,'current');assert.equal(h.state.versions.length,1);});
await check('concurrent requests cannot create competing versions',async()=>{const h=harness();let release;const gate=new Promise(r=>release=r);h.state.dialog=()=>gate;const first=h.run();await Promise.resolve();await assert.rejects(h.run(),/正在生成/);release();await first;assert.equal(h.state.versions.length,1);});
await check('saved references use native bounded JPEG preparation and preserve only decoded bytes',async()=>{const h=harness();await h.service.saveReferencePhoto('content://fixture/photo');assert.deepEqual(h.state.savedReference[0],[255,216,255,217]);assert.equal(h.state.requests.length,0);assert.equal(h.state.current.reference_photo_path,'assets/private-reference.jpg');});
console.log(JSON.stringify({passed,scope:'Actual shared avatar controller, provider request and service/version flow with controlled image/network/storage ports; no real provider-quality claim'}));
