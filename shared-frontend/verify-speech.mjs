import assert from 'node:assert/strict';
import fs from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
const base='.arkui-x-validation/application/project/entry/src/main/ets/';
const read=name=>fs.readFileSync(base+name,'utf8');
const execute=(source,end,ports={})=>runInNewContext(stripTypeScriptTypes(source.replace(/^import\s+[^;]+;\s*/gm,'')).replace(/^export /gm,'')+'\n'+end,ports);
const presets=execute(read('utils/voice/VoiceTtsPresets.ets'),'({normalizeVoiceTtsPreset});');
const Protocol=execute(read('platform/UpstreamMimoVoice.ets'),'MimoVoiceClient;',{...presets,parseJsonCompat:JSON.parse});
const wav=execute(read('utils/voice/PcmWav.ets'),'({buildPcm16WavBytes,concatPcmChunks});');
const metadata=execute(read('pages/chat/VoiceInteractionTypes.ets'),'({createTtsVoiceRuntimeMetadata,createVoiceErrorMetadata});');
const chunk=bytes=>'data: '+JSON.stringify({choices:[{delta:{audio:{data:Buffer.from(bytes).toString('base64')}}}]});
function harness(){
 const settings=new Map([['voiceTtsBaseUrl','https://voice-fixture.invalid/v1'],['voiceTtsApiKey','synthetic-key'],['voiceTtsModel','fixture-model'],['voiceTtsVoice','mimo_default'],['voiceTtsStylePrompt','calm'],['voiceTtsStreamingEnabled',true]]);
 const state={calls:[],files:new Map(),pcm:[],events:[],allowed:true,exists:true,session:'session-1',message:{id:'message-1'},http:{v:1,status:'done',events:[chunk([1,2,3]),chunk([4,5,6,7,8]),'data: [DONE]'],body:'',error:''},finished:false,playback:'',filePoll:0};
 const storage={sessionExists:()=>state.exists,ensureSessionAssetsDir(){},sessionAssetPath:(dir,session,name)=>dir+'/chat-sessions/assets/'+session+'/'+name,pathExists:p=>state.files.has(p)};
 const sink={OpenMode:{CREATE:1,WRITE_ONLY:2,TRUNC:4},openSync:p=>({fd:p}),closeSync(){},writeSync:(p,buffer)=>{state.files.set(p,Buffer.from(buffer));state.afterWrite?.();},renameSync:(p,d)=>{state.files.set(d,state.files.get(p));state.files.delete(p);},unlinkSync:p=>state.files.delete(p)};
 const Controller=execute(read('platform/CloudSpeech.ets'),'CloudSpeech;',{
  ...metadata,...wav,MimoVoiceClient:Protocol,ChatSessionStorage:storage,fs:sink,
  AppStorage:{get:k=>settings.get(k)},setTimeout:fn=>setTimeout(fn,1),
  AiServiceAccess:{requireAllowed(){assert.ok(state.allowed,'revoked');}},PrivacyConsent:{requireAccepted(){assert.ok(state.allowed,'revoked');}},
  util:{Base64Helper:class{decodeSync(s){return Uint8Array.from(Buffer.from(s,'base64'));}encodeToStringSync(bytes){return Buffer.from(bytes).toString('base64');}}},
  Bridge:{createBridge:()=>({async callMethod(method,...args){
   state.calls.push([method,...args]);
   if(method==='speechRequestStart'){state.wire=JSON.parse(args[2]);state.onStart?.(args[3]);if(state.startGate)await state.startGate;return 'request-1';}
   if(method==='speechRequestPoll'){state.afterPoll?.();return JSON.stringify(state.http);}
   if(method==='speechPcmStart'){state.playback=args[0];state.finished=false;return true;}
   if(method==='speechPcmAppend'){state.pcm.push(Buffer.from(args[1],'base64'));return true;}
   if(method==='speechPcmFinish'){state.finished=true;return true;}
   if(method==='speechFilePlay'){state.playback=args[0];state.finished=true;state.filePoll=0;return true;}
   if(method==='speechPlaybackPoll')return JSON.stringify({v:1,id:state.playback,status:state.finished?'idle':state.pcm.length?'speaking':'synthesizing',error:'',frames:4});
   return true;
  }})}
 });
 const speech=new Controller({filesDir:()=>'/private/files',sessionId:()=>state.session,message:()=>state.message,
  update:(id,voice)=>{state.message.voice=voice;state.events.push(voice?.status||'none');},state:(id,status)=>{state.status=status;},
  authorize:async()=>{if(state.authorizeGate)await state.authorizeGate;assert.ok(state.allowed,'revoked');}});
 return{state,settings,speech,run:(replay=false)=>speech.play('message-1','合成测试文本',replay)};
}
let passed=0;async function check(name,fn){await fn();passed++;console.log('PASS '+name);}
await check('original streaming request/parser, split PCM samples and WAV cache preserve real bytes',async()=>{
 const h=harness();await h.run();assert.equal(h.state.wire.stream,true);assert.equal(h.state.wire.audio.format,'pcm16');assert.equal(h.state.wire.messages[0].role,'user');assert.equal(h.state.wire.messages[1].role,'assistant');
 assert.deepEqual([...Buffer.concat(h.state.pcm)],[1,2,3,4,5,6,7,8]);assert.ok(h.state.pcm.every(p=>p.length%2===0));
 const audio=h.state.files.get('/private/files/chat-sessions/assets/session-1/message-1-tts.wav');assert.equal(audio.toString('ascii',0,4),'RIFF');assert.equal(audio.readUInt32LE(24),24000);assert.deepEqual([...audio.subarray(44)],[1,2,3,4,5,6,7,8]);
 assert.equal(h.state.message.voice.provider,'mimo');assert.equal(h.state.message.voice.status,'ready');assert.ok(h.state.events.includes('playing'));
});
await check('nonstreaming uses original WAV request and response contract',async()=>{const h=harness();h.settings.set('voiceTtsStreamingEnabled',false);h.state.http.body=JSON.stringify({choices:[{message:{audio:{data:Buffer.from(wav.buildPcm16WavBytes(new Uint8Array([0,0,0,0]),{sampleRate:24000,channels:1})).toString('base64')}}}]});await h.run();assert.equal(h.state.wire.audio.format,'wav');assert.equal(h.state.wire.stream,false);assert.ok(h.state.calls.some(c=>c[0]==='speechFilePlay'));});
await check('pre-audio stream failure retains original one-shot WAV fallback with fresh authorization checks',async()=>{const h=harness();h.state.onStart=stream=>{h.state.http=stream?{v:1,status:'failed',error:'stream unsupported',events:[]}:{v:1,status:'done',events:[],body:JSON.stringify({choices:[{message:{audio:{data:Buffer.from(wav.buildPcm16WavBytes(new Uint8Array([0,0,0,0]),{sampleRate:24000,channels:1})).toString('base64')}}}]})};};await h.run();assert.deepEqual(h.state.calls.filter(c=>c[0]==='speechRequestStart').map(c=>c[4]),[true,false]);assert.equal(h.state.message.voice.status,'ready');});
await check('local cached replay needs no cloud consent or configured endpoint',async()=>{const h=harness();h.state.allowed=false;h.settings.clear();h.state.message.voice={provider:'mimo',voice:'mimo_default',audioUri:'file:///private/files/chat-sessions/assets/session-1/message-1-tts.wav'};await h.run(true);assert.ok(!h.state.calls.some(c=>c[0]==='speechRequestStart'));assert.ok(h.state.calls.some(c=>c[0]==='speechFilePlay'));});
await check('unsafe endpoints and incomplete configuration never request or play',async()=>{for(const url of ['http://voice.invalid','https://user:key@voice.invalid','https://voice.invalid?key=x','']){const h=harness();h.settings.set('voiceTtsBaseUrl',url);await assert.rejects(h.run(),/HTTPS/);assert.ok(!h.state.wire);}});
await check('HTTP failure, malformed stream and unmatched final PCM byte never produce ready cache',async()=>{
 for(const patch of [{status:'failed',error:'HTTP 401'},{events:['data: broken','data: [DONE]']},{events:[chunk([1,2,3]),'data: [DONE]']},{events:[chunk([1,2])]}]){
  const h=harness();Object.assign(h.state.http,patch);await assert.rejects(h.run());assert.equal(h.state.files.size,0);assert.equal(h.state.message.voice.status,'error');assert.ok(h.state.calls.some(c=>c[0]==='speechStop'));
 }
});
await check('authorization withdrawal or config changes after receive cancel before audio/cache commit',async()=>{for(const mode of ['revoke','config']){const h=harness();h.state.afterPoll=()=>{if(mode==='revoke')h.state.allowed=false;else h.settings.set('voiceTtsModel','changed');};await assert.rejects(h.run());assert.equal(h.state.pcm.length,0);assert.equal(h.state.files.size,0);assert.ok(h.state.calls.some(c=>c[0]==='speechRequestCancel'));}});
await check('deleted or switched sessions cannot receive late speech metadata or cache',async()=>{for(const mode of ['delete','switch']){const h=harness();h.state.afterPoll=()=>{if(mode==='delete')h.state.exists=false;else h.state.session='other-session';};await h.run();assert.equal(h.state.files.size,0);assert.ok(!h.state.events.includes('ready'));assert.equal(h.speech.ownsVoice(),false);}});
await check('cancel during authorization cannot launch a later request',async()=>{const h=harness();let release;h.state.authorizeGate=new Promise(r=>release=r);const task=h.run();await new Promise(r=>setTimeout(r,0));await h.speech.stop();release();await task;assert.ok(!h.state.wire);assert.equal(h.state.files.size,0);});
await check('cancel while native start is pending disposes its late request ID',async()=>{const h=harness();let release;h.state.startGate=new Promise(r=>release=r);const task=h.run();await new Promise(r=>setTimeout(r,0));await h.speech.stop();release();await task;assert.ok(h.state.calls.some(c=>c[0]==='speechRequestCancel'&&c[1]==='request-1'));assert.equal(h.state.files.size,0);});
await check('untrusted cached URI and invalid WAV response cannot be replayed or marked ready',async()=>{const h=harness();h.state.message.voice={provider:'mimo',audioUri:'https://untrusted.invalid/audio.wav'};await assert.rejects(h.run(true),/不属于/);assert.ok(!h.state.calls.some(c=>c[0]==='speechFilePlay'));const w=harness();w.settings.set('voiceTtsStreamingEnabled',false);w.state.http.body=JSON.stringify({choices:[{message:{audio:{data:Buffer.alloc(50).toString('base64')}}}]});await assert.rejects(w.run(),/WAV/);assert.equal(w.state.files.size,0);});
await check('session deletion during temporary write leaves no partial cache',async()=>{const h=harness();h.state.afterWrite=()=>h.state.exists=false;await h.run();assert.equal(h.state.files.size,0);assert.ok(!h.state.events.includes('ready'));});
console.log(JSON.stringify({passed,scope:'Actual generated MiMO protocol, original WAV encoding and shared CloudSpeech orchestration; transport/audio ports controlled, real HTTPS and AudioTrack checked separately'}));
