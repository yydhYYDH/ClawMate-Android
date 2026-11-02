import assert from 'node:assert/strict';
import fs from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
import { buildAndroidGallerySource } from './gallery-source.mjs';
const read = file => fs.readFileSync(file,'utf8');
function module(source,name,ports={}) {
  const code=source.replace(/^import\s+[^;]+;\s*/gm,'').replace(/^export /gm,'');
  return runInNewContext(stripTypeScriptTypes(code)+`\n${name};`, {setTimeout,clearTimeout,setInterval,clearInterval,console,...ports});
}
const hash='a'.repeat(64), other='b'.repeat(64), root='/fixture';
const input=(h=hash)=>({id:'gallery_android_'+h,uri:root+'/gallery-log/android-images/'+h+'.jpg',date:'2026-10-27'});
const item=(h=hash,status='queued')=>({...input(h),uri:'file://'+input(h).uri,status,error:'',skipReason:''});
const session=(items=[item()])=>({id:'gallery_scan_1',source:'android-picker',status:'paused',createdAt:1,updatedAt:1,total:99,processed:88,skipped:77,failed:66,items});
const path='/fixture/gallery-log/scan-session.json';
let passed=0;
async function check(name,test){await test();passed++;console.log('PASS '+name);}
function disk() {
  const files=new Map(), handles=new Map();let handle=0;
  const driver={files,short:false,renameError:false,fs:{
    OpenMode:{READ_ONLY:1,CREATE:2,WRITE_ONLY:4,TRUNC:8},
    accessSync:p=>files.has(p),statSync:p=>({size:files.get(p)?.byteLength??0}),
    openSync:p=>{handles.set(++handle,p);return{fd:handle};},closeSync(){},
    readSync:(fd,data)=>{const b=files.get(handles.get(fd));new Uint8Array(data).set(b);return b.length;},
    writeSync:(fd,data)=>{const b=new Uint8Array(data);files.set(handles.get(fd),b.slice(0,driver.short?b.length-1:b.length));},
    renameSync:(a,b)=>{if(driver.renameError)throw Error('rename failed');files.set(b,files.get(a));files.delete(a);},
    unlinkSync:p=>files.delete(p)
  }};return driver;
}
function checkpoint(d=disk()) {
  const Checkpoint=module(read('shared-frontend/adapters/platform/GalleryScanCheckpoint.ets'),'GalleryScanCheckpoint',{
    fs:d.fs,encodeUtf8Compat:t=>new TextEncoder().encode(t),decodeUtf8Compat:b=>new TextDecoder().decode(b)
  });return {Checkpoint,d};
}
await check('checkpoints derive all counters from receipts and preserve failed retry state',()=>{
  const {Checkpoint}=checkpoint();const value=Checkpoint.validate(session([item(hash,'done'),item(other,'failed')]),root);
  assert.equal(value.total,2);assert.equal(value.processed,1);assert.equal(value.failed,1);assert.equal(value.skipped,0);
});
await check('malformed snapshots, duplicate identities and foreign image paths fail closed',()=>{
  const {Checkpoint}=checkpoint();
  for(const value of [null,{},session([item(),item()]),{...session(),items:{}},{...session(),status:'success'},
    session([{...item(),uri:'file:///etc/hosts'}]),session([{...item(),id:'../outside'}]),session([{...item(),status:'running'}]),session([{...item(),date:'2026-02-30'}])]) {
    assert.throws(()=>Checkpoint.validate(value,root));
  }
});
await check('atomic UTF8 checkpoint roundtrip does not trust stale counters',()=>{
  const {Checkpoint,d}=checkpoint();const value=session([{...item(),status:'failed',error:'合成错误'}]);
  Checkpoint.write(path,value,root);assert.equal(d.files.size,1);assert.ok(!d.files.has(path+'.part'));
  assert.equal(Checkpoint.read(path,root).items[0].error,'合成错误');assert.equal(Checkpoint.read(path,root).processed,0);
});
await check('short writes and failed rename preserve the last committed checkpoint',()=>{
  for(const fail of ['short','renameError']) {
    const {Checkpoint,d}=checkpoint();Checkpoint.write(path,session(),root);const before=d.files.get(path).slice();
    d[fail]=true;assert.throws(()=>Checkpoint.write(path,session([item(hash,'done')]),root));
    assert.deepEqual(d.files.get(path),before);assert.equal(d.files.has(path+'.part'),false);
  }
});
await check('missing, corrupt and oversized checkpoints are distinguished',()=>{
  const {Checkpoint,d}=checkpoint();assert.equal(Checkpoint.read(path,root),null);
  d.files.set(path,new TextEncoder().encode('{broken'));assert.throws(()=>Checkpoint.read(path,root),/无法解析/);
  d.files.set(path,new Uint8Array(4000001));assert.throws(()=>Checkpoint.read(path,root),/无效/);
});
await check('legacy cached skips normalize to completion without becoming keyword skips',()=>{
  const {Checkpoint}=checkpoint();const value=session([{...item(),status:'skipped',skipReason:'已存在可用缓存'}]);delete value.source;
  Checkpoint.validate(value,root);assert.equal(value.items[0].status,'done');assert.equal(value.processed,1);assert.equal(value.skipped,0);
});
function importer() {
  const d=disk();let calls=0;const state={ready:true,stopped:false,hashes:[hash,hash],digests:new Map()};
  const Scan=module(read('shared-frontend/adapters/platform/AndroidGalleryScan.ets'),'AndroidGalleryScan',{
    fs:d.fs,NativeCapabilities:{runtime:async()=>({windowReady:state.ready})},
    ChatImages:{snapshot:async()=>{if(state.gate)await state.gate;return{images:['data:image/jpeg;base64,/9j/2Q=='],fingerprints:[state.hashes[calls++]]};}},
    GalleryStorage:{rootDir:r=>r+'/gallery-log',ensureWorkspace(){},ensureDirectory(){},writeBytes:(p,b)=>d.files.set(p,new Uint8Array(b))},
    util:{Base64Helper:class{decodeSync(){return Uint8Array.from([1,2,255,216,255,217,3]).subarray(2,6);}}},
    Bridge:{createBridge:()=>({callMethod:async(_,uri)=>state.digests.get(uri)??uri.split('/').at(-1).slice(0,-4)})}
  });return {d,state,Scan};
}
await check('private snapshots deduplicate selected content and save only exact JPEG bytes',async()=>{
  const {d,state,Scan}=importer();const values=await Scan.importImages(root,['content://one','content://same'],()=>state.stopped);
  assert.equal(values.length,1);assert.equal(values[0].id,'gallery_android_'+hash);assert.equal(d.files.size,1);
  assert.deepEqual([...d.files.values()][0],Uint8Array.from([255,216,255,217]));
});
await check('background import cannot read selected images or publish a snapshot',async()=>{
  const {d,state,Scan}=importer();state.ready=false;
  await assert.rejects(Scan.importImages(root,['content://one'],()=>false),/停止/);assert.equal(d.files.size,0);
});
await check('failed import removes only new uncommitted assets and retains prior referenced assets',async()=>{
  const {d,state,Scan}=importer();state.hashes=[hash,other];
  d.files.set(input().uri,Uint8Array.from([7]));state.digests.set('file://'+input(other).uri,'c'.repeat(64));
  await assert.rejects(Scan.importImages(root,['one','two'],()=>false),/校验失败/);
  assert.equal(d.files.size,1);assert.deepEqual(d.files.get(input().uri),Uint8Array.from([7]));
});
await check('foreign references are rejected before requesting a private file digest',async()=>{
  const {Scan}=importer();await assert.rejects(Scan.verify(root,{...input(),uri:'file:///etc/hosts'}),/校验失败/);
});
await check('explicit re-selection atomically repairs a corrupted referenced snapshot',async()=>{
  const {d,state,Scan}=importer();const path=input().uri;
  d.files.set(path,Uint8Array.from([7]));state.digests.set('file://'+path,'c'.repeat(64));
  const rename=d.fs.renameSync;d.fs.renameSync=(a,b)=>{rename(a,b);state.digests.delete('file://'+b);};
  await Scan.importImages(root,['one'],()=>false);assert.deepEqual(d.files.get(path),Uint8Array.from([255,216,255,217]));
});
await check('a new page cannot release another page execution lease',()=>{
  const {Scan}=importer();Scan.begin(root);assert.throws(()=>Scan.begin(root),/收尾/);assert.equal(Scan.busy(root),true);
  Scan.end(root);assert.equal(Scan.busy(root),false);
});
await check('image import also owns execution permission until its uncommitted files are settled',async()=>{
  const {Scan,state}=importer();let release;state.gate=new Promise(resolve=>release=resolve);
  const work=Scan.importImages(root,['one'],()=>state.stopped);await new Promise(resolve=>setTimeout(resolve,0));
  assert.equal(Scan.busy(root),true);await assert.rejects(Scan.importImages(root,['two'],()=>false),/收尾/);assert.equal(Scan.busy(root),true);
  state.stopped=true;release();await assert.rejects(work,/停止/);assert.equal(Scan.busy(root),false);
});
await check('image snapshot protocol requires complete validated fingerprints',async()=>{
  for(const fingerprints of [undefined,[],['invalid']]) {
    const Images=module(read('shared-frontend/adapters/platform/ChatImages.ets'),'ChatImages',{
      Bridge:{createBridge:()=>({callMethod:async method=>method==='imagePrepareStart'?'id':JSON.stringify({v:1,status:'done',images:['jpeg'],fingerprints})})}
    });await assert.rejects(Images.snapshot(['content://one'],()=>false),/校验/);
  }
});
const source=buildAndroidGallerySource(read('.arkui-x-validation/reference/entry/src/main/ets/pages/gallery/GalleryPage.ets'));
const names=['pendingInputs','markScanItem','recountScanSession','normalizeScanItemStatus','applyScanSession','effectiveScanStatus','restoreScanSessionState','requestPauseAutoAnalyze','runAndroidScanCore','runScanSession','repairHistoricalImages','continueAutoAnalyze'];
const methods=names.map(name=>{
  const match=source.match(new RegExp('  private (?:async )?'+name+'\\([^]*?\\n  \\}'));
  assert.ok(match,'Missing method '+name);return match[0];
}).join('\n');
function page() {
  let stored=session();let started=0;let modelCalls=0;let ready=true;let choice=1;let waiting=[];let originalCache=false;const results=new Map();
  const {Checkpoint}=checkpoint();
  const store={loadScanSession:()=>structuredClone(stored),saveScanSession:(_,value)=>{Checkpoint.validate(value,root);stored=structuredClone(value);},loadResults:()=>[...results.values()],loadResult:(_,date,id)=>results.get(id),itemPathById:(_,date,id)=>date+'/'+id,formatDate:()=> '2026-10-27'};
  const Queue=module(read('.arkui-x-validation/reference/entry/src/main/ets/utils/gallery/GalleryWorkQueue.ets'),'runGalleryWorkQueue');
  const Analyzer=module(read('.arkui-x-validation/application/project/entry/src/main/ets/utils/gallery/GalleryAnalyzer.ets'),'GalleryAnalyzer',{
    runGalleryWorkQueue:Queue,GalleryOcrService:class{async release(){}},GalleryReverseGeocoder:class{},
    GalleryStorage:store,GalleryImageStore:{persist:async(_,input,result)=>{results.set(input.id,result);return result;}},
    GalleryExifUtils:{isValidLocation:()=>false},DebugSettings:{isEnabled:()=>false},AppLogger:{info(){},debug(){},error(){}}
  });
  Analyzer.prototype.logInfo=()=>{};Analyzer.prototype.logError=()=>{};
  const analyzeOne=Analyzer.prototype.analyzeOne;
  Analyzer.prototype.analyzeOne=async function(input,...args){started++;if(originalCache)return analyzeOne.call(this,input,...args);if(!results.has(input.id)){modelCalls++;await new Promise(resolve=>waiting.push(resolve));}
    return {cached:results.has(input.id),result:results.get(input.id)??{...input,id:input.id,error:'',summary:'controlled result'}};};
  const {Scan}=importer();
  const Page=module('class Page {\n'+methods+'\n}','Page',{
    GalleryStorage:store,GalleryAnalyzer:Analyzer,GalleryScanCheckpoint:Checkpoint,
    AndroidGalleryScan:{begin:()=>Scan.begin(root),end:()=>Scan.end(root),windowReady:async()=>ready,verify:async()=>{}},
    PrivacyConsent:{requireAccepted(){},isAccepted:()=>true},promptAction:{showDialog:async()=>({index:choice})},showToastSafely(){},
    GalleryImageStore:{migrateHistory:()=>Promise.resolve()},AppLogger:{errorCause(){}},GALLERY_RESULT_PAGE_SIZE:40,setInterval:()=>1,clearInterval(){}
  });
  const p=new Page();Object.assign(p,{filesDir:root,pageActive:true,analyzing:false,pauseRequested:false,hdcBatch:false,hdcReader:null,
    scanRevision:0,progressRevision:0,autoScanStatus:'paused',cloudClient:{},imageRepairGeneration:0,
    progressPercentFromUnits:(a,b)=>b?Math.floor(a/b*100):0,setLastRunLabel(){},setLastRun(){},
    buildAnalyzerConfig:()=>({apiBaseUrl:'https://fixture.invalid',model:'fixture',ocrConcurrency:1,modelConcurrency:1}),
    updateAutoProgress(){},mergeResult(){},replaceResults(){}});
  return {p,store,results,get stored(){return stored;},set stored(v){stored=v;},get started(){return started;},get modelCalls(){return modelCalls;},
    get waiting(){return waiting;},drain(){const batch=waiting;waiting=[];batch.forEach(resolve=>resolve());},useOriginalCache(){originalCache=true;},setReady(v){ready=v;},setChoice(v){choice=v;}};
}
const tick=()=>new Promise(resolve=>setTimeout(resolve,0));
await check('actual analyzer queue soft pause commits in-flight item and leaves later images queued',async()=>{
  const h=page();h.stored=session([item(),item(other),item('c'.repeat(64))]);
  const work=h.p.runScanSession(h.store.loadScanSession());await tick();assert.equal(h.started,1);
  h.p.requestPauseAutoAnalyze();h.drain();await work;
  assert.equal(h.started,1);assert.equal(h.stored.status,'paused');assert.equal(h.stored.processed,1);assert.equal(h.stored.items[1].status,'queued');
});
await check('process restart restores running and pausing checkpoints without auto-starting any work',()=>{
  for(const status of ['running','pausing']) {
    const h=page();h.stored={...session([item(hash,'done'),item(other)]),status};h.p.restoreScanSessionState();
    assert.equal(h.stored.status,'paused');assert.equal(h.p.scanProcessed,1);assert.equal(h.p.scanPending,1);assert.equal(h.started,0);
  }
});
await check('continued batch skips completed receipts and retries failed items without resetting baseline',async()=>{
  const h=page();h.stored=session([item(hash,'done'),item(other,'failed')]);
  const work=h.p.runScanSession(h.store.loadScanSession());await tick();assert.equal(h.started,1);h.drain();await work;
  assert.equal(h.stored.processed,2);assert.equal(h.stored.failed,0);assert.equal(h.stored.status,'completed');
});
await check('persisted result with an uncommitted receipt is reused rather than issuing model work twice',async()=>{
  const h=page();h.results.set(item().id,{...item(),error:'',summary:'already committed'});
  h.useOriginalCache();
  await h.p.runScanSession(h.store.loadScanSession());assert.equal(h.modelCalls,0);assert.equal(h.stored.processed,1);
});
await check('background batch refuses dispatch and keeps unstarted work resumable',async()=>{
  const h=page();const work=h.p.runScanSession(h.store.loadScanSession());await tick();h.p.pageActive=false;h.p.requestPauseAutoAnalyze();h.drain();await work;
  assert.equal(h.stored.status,'paused');assert.equal(h.started,1);
});
await check('declined resume leaves all receipts intact and releases execution ownership',async()=>{
  const h=page();h.stored=session([item(hash,'done'),item(other)]);h.setChoice(0);
  await h.p.runScanSession(h.store.loadScanSession());assert.equal(h.started,0);assert.equal(h.stored.processed,1);assert.equal(h.stored.status,'paused');
});
await check('corrupt checkpoint reports failure while page restoration and history repair remain usable',async()=>{
  const h=page();h.store.loadScanSession=()=>{throw Error('corrupt checkpoint');};
  assert.doesNotThrow(()=>h.p.restoreScanSessionState());assert.equal(h.p.autoScanStatus,'failed');
  assert.doesNotThrow(()=>h.p.repairHistoricalImages());await h.p.imageRepair;
  await h.p.continueAutoAnalyze();assert.equal(h.started,0);
});
await check('pause still stops dispatch when checkpoint storage cannot be read',()=>{
  const h=page();h.p.analyzing=true;h.store.loadScanSession=()=>{throw Error('disk failure');};
  assert.doesNotThrow(()=>h.p.requestPauseAutoAnalyze());assert.equal(h.p.pauseRequested,true);assert.equal(h.p.autoScanStatus,'pausing');
});
await check('changed source breaks the checked adaptation instead of silently retaining HDC dispatch',()=>{
  assert.throws(()=>buildAndroidGallerySource(read('.arkui-x-validation/reference/entry/src/main/ets/pages/gallery/GalleryPage.ets').replace('private async startManualAnalyze','private async upstreamChanged')),/drift/);
});
await check('clearing from another page cannot remove assets while an old batch is settling',()=>{
  const method=source.match(/  private clearAnalysis\([^]*?\n  \}/)[0];
  const Page=module('class Page {\n'+method+'\n}','Page',{
    AndroidGalleryScan:{busy:()=>true},GalleryStorage:{clear(){assert.fail('no delete');}},showToastSafely(){}
  });const p=new Page();p.filesDir=root;assert.doesNotThrow(()=>p.clearAnalysis());
});
console.log(JSON.stringify({passed,scope:'Actual checkpoint serializer, original gallery queue/analyzer orchestration and page continuation with controlled OCR/model/filesystem ports'}));
