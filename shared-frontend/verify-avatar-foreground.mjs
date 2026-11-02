import assert from 'node:assert/strict';
import fs from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
import { inflateSync } from 'node:zlib';
const base='.arkui-x-validation/application/project/entry/src/main/ets/datamanager/avatar/';
const source=['AvatarForegroundCrop','AvatarForegroundThumbnail','AvatarForegroundService'].map(name=>
 fs.readFileSync(base+name+'.ets','utf8').replace(/^import\s+[^;]+;\s*/gm,'')).join('\n');
const js=stripTypeScriptTypes(source).replace(/^export /gm,'');
function harness(){
 const state={foreground:false,message:false,calls:[],saved:[],clock:0,toasts:[],fail:false};
 const rgba=new Uint8Array(4*4*4);
 for(const i of [5,6,9,10])rgba.set([12,34,56,255],i*4);
 state.result={v:1,status:'done',width:4,height:4,rgba:Buffer.from(rgba).toString('base64'),error:''};
 const storage={join:(...parts)=>parts.join('/')};
 const assets={foregroundExists:()=>state.foreground,messageForegroundExists:()=>state.message,
  foregroundDisplayUri:()=> 'file:///avatar/assets/v1-foreground.png',foregroundAssetPath:()=>'/avatar/assets/v1-foreground.png',
  saveForegroundPng:(id,bytes)=>{state.foreground=true;state.saved.push({kind:'foreground',bytes});},
  saveMessageForegroundPng:(id,bytes)=>{state.message=true;state.saved.push({kind:'message',bytes});}};
 const Service=runInNewContext(js+'\nAvatarForegroundService;',{
  showToastSafely:text=>state.toasts.push(text),
  util:{Base64Helper:class{decodeSync(text){return new Uint8Array(Buffer.from(text,'base64'));}}},
  Date:{now:()=>state.clock},setTimeout:fn=>{state.clock+=46000;fn();},
  Bridge:{createBridge:()=>({async callMethod(method,...args){state.calls.push([method,...args]);
   if(method==='avatarForegroundStart')return 'job';if(method==='avatarForegroundCancel')return true;
   if(state.fail)return JSON.stringify({v:1,status:'failed',error:'未检测到清晰人物'});
   return JSON.stringify(state.result);
  }})}
 });
 const service=new Service({avatarRoot:'/avatar'},storage,assets);
 return{state,run:(id='v1',path='assets/source.png')=>service.ensureForegroundForVersion(id,path)};
}
function png(bytes){
 const buffer=Buffer.from(bytes);assert.equal(buffer.subarray(0,8).toString('hex'),'89504e470d0a1a0a');
 const width=buffer.readUInt32BE(16),height=buffer.readUInt32BE(20);let offset=8,parts=[];
 while(offset<buffer.length){const n=buffer.readUInt32BE(offset);if(buffer.toString('ascii',offset+4,offset+8)==='IDAT')parts.push(buffer.subarray(offset+8,offset+8+n));offset+=n+12;}
 return{width,height,scanlines:inflateSync(Buffer.concat(parts))};
}
let passed=0;async function check(name,fn){await fn();passed++;console.log('PASS '+name);}
await check('real shared crop and PNG/thumbnail functions preserve foreground alpha and original canvas',async()=>{
 const h=harness();assert.match(await h.run(),/foreground.png$/);assert.equal(h.state.saved.length,2);
 const full=png(h.state.saved[0].bytes);assert.equal(full.width,2);assert.equal(full.height,2);
 assert.deepEqual([...full.scanlines.subarray(1,5)],[12,34,56,255]);
 const thumb=png(h.state.saved[1].bytes);assert.equal(thumb.width,168);assert.equal(thumb.height,216);
 assert.ok(thumb.scanlines.subarray(1,168*4+1).every(x=>x===0));
 assert.deepEqual(h.state.calls[0],['avatarForegroundStart','file:///avatar/assets/source.png',true]);
});
await check('complete caches skip all native work',async()=>{const h=harness();h.state.foreground=true;h.state.message=true;await h.run();assert.equal(h.state.calls.length,0);assert.equal(h.state.saved.length,0);});
await check('thumbnail backfill decodes existing alpha without rerunning person inference',async()=>{
 const h=harness();h.state.foreground=true;await h.run();assert.deepEqual(h.state.calls[0],['avatarForegroundStart','file:///avatar/assets/v1-foreground.png',false]);assert.deepEqual(h.state.saved.map(x=>x.kind),['message']);
});
await check('concurrent ensures are serialized and second observes completed caches',async()=>{const h=harness();await Promise.all([h.run(),h.run()]);assert.equal(h.state.calls.filter(x=>x[0]==='avatarForegroundStart').length,1);});
await check('model failure keeps original assets and next request can retry',async()=>{
 const h=harness();h.state.fail=true;await assert.rejects(h.run(),/清晰人物/);assert.equal(h.state.saved.length,0);assert.equal(h.state.toasts.length,1);
 assert.equal(h.state.calls.at(-1)[0],'avatarForegroundCancel');h.state.fail=false;await h.run();assert.equal(h.state.saved.length,2);
});
await check('empty person output never creates fake transparent caches',async()=>{
 const h=harness();h.state.result.rgba=Buffer.alloc(4*4*4).toString('base64');await assert.rejects(h.run(),/未检测到/);assert.equal(h.state.saved.length,0);
});
await check('untrusted protocol dimensions and truncated pixels cannot be persisted',async()=>{
 for(const patch of [{v:2},{width:513},{height:1.5},{rgba:'AA=='},{rgba:'A'.repeat(1400001)}]){
  const h=harness();Object.assign(h.state.result,patch);await assert.rejects(h.run(),/不兼容|无效|不完整/);assert.equal(h.state.saved.length,0);
 }
});
await check('invalid version IDs and remote references never start native processing',async()=>{
 for(const [id,path]of [['../escape','assets/source.png'],['v1','https://example.invalid/photo.png'],['v1','data:image/png;base64,AA']]){
  const h=harness();await assert.rejects(h.run(id,path),/无效|本机/);assert.equal(h.state.calls.length,0);
 }
});
await check('timeout releases native job and saves no cache',async()=>{
 const h=harness();h.state.result.status='running';await assert.rejects(h.run(),/超时/);assert.equal(h.state.calls.at(-1)[0],'avatarForegroundCancel');assert.equal(h.state.saved.length,0);
});
console.log(JSON.stringify({passed,scope:'Actual generated shared service/crop/PNG/thumbnail with controlled native masks; real model tested separately on device'}));
