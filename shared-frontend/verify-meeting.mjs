import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const files = new Map();
let failReceipt = false, builds = 0;
const storage = {
  readText: (key, fallback) => files.get(key) ?? fallback,
  readJson: (key, fallback) => files.has(key) ? JSON.parse(files.get(key)) : fallback,
  exists: key => files.has(key),
  writeTextAtomic: (key, value) => files.set(key, value),
  writeJsonAtomic: (key, value) => {
    if (failReceipt && key.endsWith('.json') && key.includes('/meeting_imports/') && !key.endsWith('.handoff.json')) throw Error('receipt disk failure');
    files.set(key, JSON.stringify(value));
  },
};
class DataManagerFacade {
  constructor() { this.storage = storage; this.paths = { rootDir: '/host/data', outputsRoot: '/host/data/outputs' }; }
  ensureReady() {}
}
class IndexService { async build(config) { assert.equal(config.enabled, false); builds++; } }
const memory = new Map();
let source = fs.readFileSync(path.join(repo, 'shared-frontend/adapters/platform/MeetingImport.ets'), 'utf8')
  .replace(/^import .*;\r?\n/gm, '').replace('export class MeetingImport', 'class MeetingImport');
const MeetingImport = runInNewContext(stripTypeScriptTypes(source) + '\nMeetingImport', {
  DataManagerFacade, IndexService, DataPipelineModelClient: class {}, createStringMap: () => ({}),
  AppStorage: { get: key => memory.get(key), setOrCreate: (key, value) => memory.set(key, value) },
});
const api = { async validateHandoff(raw) {
  const value = JSON.parse(raw);
  assert.equal(createHash('sha256').update(value.payload).digest('hex'), value.export_id); return raw;
} };
function packet(revision, text) {
  const payload = JSON.stringify({ v: 1, title: '公开会议样例', created: '2026-10-26T08:00:00Z', tags: [],
    source: { namespace: 'on-device-meeting-notes', instance_id: 'a'.repeat(32), meeting_id: 'b'.repeat(32), revision, source_hash: 'c'.repeat(64) },
    text, selection: 'both', summary_included: false });
  return JSON.stringify({ v: 1, export_id: createHash('sha256').update(payload).digest('hex'), payload });
}
const first = packet(1, '真实转写，不生成待办或补充事实');
assert.equal(await MeetingImport.save('/host', first, api), false);
assert.equal(await MeetingImport.save('/host', first, api), true);
assert.equal([...files.keys()].filter(key => key.endsWith('.state.json')).length, 1);
console.log('PASS same handoff is imported once with preserved provenance');
const oldState = [...files.entries()].find(([key]) => key.endsWith('.state.json'));
assert.deepEqual(JSON.parse(oldState[1]).records[0].workflow_scene_ids, [8]);
assert.equal(JSON.parse(oldState[1]).records[0].summary, JSON.parse(JSON.parse(first).payload).text);
assert.equal(await MeetingImport.save('/host', packet(2, '人工修订后的新版本'), api), false);
assert.equal(files.get(oldState[0]), oldState[1]);
assert.equal([...files.keys()].filter(key => key.endsWith('.state.json')).length, 2);
console.log('PASS new revision retains old imported copy');
const interrupted = packet(3, '中断后可重试'); failReceipt = true;
await assert.rejects(MeetingImport.save('/host', interrupted, api), /receipt disk failure/);
failReceipt = false;
assert.equal(await MeetingImport.save('/host', interrupted, api), false);
assert.equal([...files.keys()].filter(key => key.endsWith('.state.json')).length, 3);
console.log('PASS interrupted receipt write can retry without duplicate records');
const md = [...files.keys()].find(key => key.endsWith('.md'));
files.set(md, 'user edited imported copy');
await assert.rejects(MeetingImport.save('/host', first, api), /已导入副本被修改/);
assert.equal(files.get(md), 'user edited imported copy');
assert.equal(memory.get('androidMemorySyncBusy'), false);
assert(builds > 0);
console.log('PASS edited imports are preserved and busy state is always released');
const bridge = fs.readFileSync(path.join(repo, 'shared-app/src/main/java/com/clawmate/shared/MeetingBridge.kt'), 'utf8');
assert(!bridge.includes('DataManager') || bridge.includes('ArkTS owns'));
assert(!bridge.includes('SQLite') && !bridge.includes('dailyLogRoot'));
console.log('PASS native bridge does not write a second copy of host business storage');
