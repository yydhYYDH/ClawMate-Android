import assert from 'node:assert/strict';
import fs from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';

const generated = '.arkui-x-validation/application/project/entry/src/main/ets/';
function module(file, names, ports = {}) {
  const source = fs.readFileSync(file, 'utf8').replace(/^import\s+[^;]+;\s*/gm, '')
    .replace(/^export\s*\{[^}]+\}(?:\s*from[^;]+)?;\s*/gm, '');
  return runInNewContext(stripTypeScriptTypes(source).replace(/^export /gm, '') + `\n({${names}});`,
    { setTimeout, clearTimeout, console, ...ports });
}
let passed = 0;
async function check(name, task) { await task(); passed++; console.log('PASS ' + name); }
const { MemoryQuery } = module('shared-frontend/adapters/platform/MemoryQuery.ets', 'MemoryQuery');
await check('memory queries reject malformed bounds, dates and unknown fields before touching storage', () => {
  for (const raw of ['[]', 'null', '{"limit":-1}', '{"limit":"2"}', '{"keywords":[2]}', '{"date":"2026-02-30"}',
    '{"month":"2026-13"}', '{"entities":[null]}', '{"domains":["secret"]}', '{"file":"/private"}',
    '{"date":"2026-01-01","month":"2026-01"}', '{"start_date":"2026-10-26","end_date":"2026-10-25"}']) {
    assert.throws(() => MemoryQuery.options(raw), undefined, raw);
  }
  assert.equal(MemoryQuery.options('{"keyword_operator":"or","limit":0}').limit, 1000);
  assert.equal(MemoryQuery.options('{"entities":[{"type":"person","name":"测试"}]}').entities[0], 'person:测试');
});
await check('memory tool emits bounded valid JSON without private IDs, paths or source references', () => {
  const event = { event_id: 'private-id', source_state_file: '/private/state', source_refs: ['private-ref'],
    event_date: '2026-10-26', summary: '真实事实'.repeat(500), normalized_fact: '事实'.repeat(500),
    image_paths: ['/private/photo'], todo: { type: 'todo', deadline: '', text: '待办' } };
  const output = MemoryQuery.response({ total_events: 40, events_truncated: false, events: Array(40).fill(event) });
  assert.ok(output.length < 3800); assert.ok(!output.includes('private'));
  const parsed = JSON.parse(output); assert.equal(parsed.total_events, 40); assert.equal(parsed.events_truncated, true);
  assert.equal(parsed.events[0].event_id, 0);
  assert.equal(parsed.events[0].content_truncated, true);
});
const { SharedAgent } = module('shared-frontend/adapters/platform/SharedAgent.ets', 'SharedAgent', { MemoryQuery });
const options = () => ({ baseUrl: 'https://fixture.invalid', apiKey: '', model: 'fixture-model', messages: [{ role: 'user', content: '查询历史' }] });
const tool = (name = 'query_events') => ({ id: 'call-1', type: 'function', function: { name, arguments: '{"keywords":["测试"]}' } });
await check('a streamed tool round sends actual query results back with the matching call ID', async () => {
  let requests = 0, queries = 0, shown = '';
  const client = { async chatCompletionStream(request, callbacks) {
    requests++;
    assert.equal(request.tools[0].function.name, 'query_events');
    if (requests === 1) return { content: '', toolCalls: [tool()] };
    const message = request.messages.at(-1);
    assert.equal(message.role, 'tool'); assert.equal(message.tool_call_id, 'call-1');
    assert.equal(JSON.parse(message.content).events[0].summary, '来自索引的事实');
    callbacks.onContentDelta('基于事实的回复'); return { content: '基于事实的回复', toolCalls: [] };
  } };
  await SharedAgent.run(client, options(), () => { queries++; return '{"ok":true,"events":[{"summary":"来自索引的事实"}]}'; },
    () => true, text => shown += text, () => {});
  assert.equal(requests, 2); assert.equal(queries, 1); assert.equal(shown, '基于事实的回复');
});
await check('cancellation after a model tool response prevents local reads and the next network request', async () => {
  let active = true, reads = 0, requests = 0;
  const client = { async chatCompletionStream() { requests++; active = false; return { content: '', toolCalls: [tool()] }; } };
  await assert.rejects(SharedAgent.run(client, options(), () => { reads++; return '{}'; }, () => active, () => {}, () => {}), /取消/);
  assert.equal(reads, 0); assert.equal(requests, 1);
});
await check('unregistered tools cannot execute and repeated tool calls stop at the configured bound', async () => {
  let reads = 0, requests = 0;
  const client = { async chatCompletionStream() { requests++; return { content: '', toolCalls: [tool('run_shell')] }; } };
  await assert.rejects(SharedAgent.run(client, options(), () => { reads++; return '{}'; }, () => true, () => {}, () => {}), /上限/);
  assert.equal(reads, 0); assert.equal(requests, 4);
});

let nativeCall;
const { ChatImages } = module('shared-frontend/adapters/platform/ChatImages.ets', 'ChatImages', {
  Bridge: { createBridge: () => ({ callMethod: (...args) => nativeCall(...args) }) },
  setTimeout: resolve => setTimeout(resolve, 1)
});
await check('image preparation consumes only the current turn and cleans cancelled native work', async () => {
  const calls = [];
  nativeCall = async (method, value) => {
    calls.push(method);
    if (method === 'imagePrepareStart') { assert.equal(JSON.parse(value).length, 1); return 'image-job'; }
    return JSON.stringify({ v: 1, status: 'done', images: ['data:image/jpeg;base64,/9j/2Q=='], error: '' });
  };
  assert.equal((await ChatImages.prepare(['content://fixture/one'], () => false)).length, 1);
  assert.deepEqual(calls, ['imagePrepareStart', 'imagePreparePoll']);
  calls.length = 0;
  await assert.rejects(ChatImages.prepare(['content://fixture/one'], () => true), /取消/);
  assert.deepEqual(calls, ['imagePrepareStart', 'imagePrepareCancel']);
  await assert.rejects(ChatImages.prepare(Array(5).fill('content://fixture'), () => false), /4张/);
});
await check('historical image files contain only the decoded byte range and remain local references', () => {
  const written = new Map(); let opened = '';
  const { ChatImages: store } = module('shared-frontend/adapters/platform/ChatImages.ets', 'ChatImages', {
    util: { Base64Helper: class { decodeSync() { return Uint8Array.from([7, 8, 255, 216, 255, 217, 9]).subarray(2, 6); } } },
    fs: { OpenMode: { CREATE: 1, WRITE_ONLY: 2, TRUNC: 4 }, openSync: path => { opened = path; return { fd: 1 }; },
      writeSync: (_, bytes) => written.set(opened, [...new Uint8Array(bytes)]), closeSync() {}, unlinkSync: path => written.delete(path) },
    ChatSessionStorage: { ensureSessionAssetsDir() {}, sessionAssetPath: (root, session, name) => `${root}/chat-sessions/assets/${session}/${name}` }
  });
  const result = store.save('/fixture', 'session-one', ['data:image/jpeg;base64,/9j/2Q=='], '当前回合');
  assert.deepEqual([...written.values()][0], [255, 216, 255, 217]);
  assert.ok(result.paths[0].startsWith('file:///fixture/chat-sessions/assets/session-one/'));
  assert.equal(result.parts[1].type, 'image_url'); assert.ok(!result.paths[0].includes('base64'));
  store.discard(result.paths); assert.equal(written.size, 0);
});

await check('workflow results persist actual summary metadata; cancellation never commits a late summary', async () => {
  const files = new Map(), statuses = []; let cancelled = false, nativeCalls = 0, collectionMarks = 0;
  const signal = { isCancelled: () => cancelled, setHandler() {}, throwIfCancelled() { if (cancelled) throw new Error('用户取消'); } };
  const storage = { ensureDir() {}, readText: path => files.get(path) ?? '',
    writeTextAtomic: (path, value) => files.set(path, value), writeJsonAtomic: (path, value) => files.set(path, JSON.stringify(value)) };
  const { AndroidWorkflow } = module('shared-frontend/adapters/platform/AndroidWorkflow.ets', 'AndroidWorkflow', {
    WorkflowStorage: { updateTaskRun: (_, id, status) => statuses.push(status), readTaskWorkflowJson: () => '{"steps":[]}', runsDir: () => '/fixture/runs' },
    DataManagerFacade: class { storage = storage; paths = { dailyLogRoot: '/fixture/daily-log' }; ensureReady() {} },
    CollectionReminderService: { recordSuccessfulCollection: async () => { collectionMarks++; assert.ok([...files.keys()].some(path => path.endsWith('.md'))); } },
    Bridge: { createBridge: () => ({ async callMethod(method) {
      nativeCalls++;
      if (method === 'workflowStart') return 'run-one';
      return JSON.stringify({ v: 1, status: 'done', result: JSON.stringify({ status: 'success', message: 'completed', resultSummary: '真实输入的输出', steps: [] }) });
    } }) }
  });
  await AndroidWorkflow.run('/fixture', { id: 'task-one', title: '用户任务', sceneId: 12 }, signal);
  assert.equal(statuses.at(-1), 'success'); assert.equal(nativeCalls, 2);
  assert.ok([...files.keys()].some(path => path.endsWith('/run_summary.json')));
  assert.equal(JSON.parse([...files.entries()].find(([path]) => path.endsWith('/run_summary.json'))[1]).source_kind, 'android_workflow');
  const log = [...files.entries()].find(([path]) => path.endsWith('.md'))[1];
  assert.ok(log.includes('1. 真实输入的输出')); assert.ok(log.includes('"task_scene_id":12'));
  assert.equal(collectionMarks, 1);
  cancelled = true; const count = files.size;
  await assert.rejects(AndroidWorkflow.run('/fixture', { id: 'task-two' }, signal), /取消/);
  assert.equal(files.size, count); assert.equal(nativeCalls, 2); assert.equal(statuses.at(-1), 'cancelled');
  assert.equal(collectionMarks, 1);
});

// Execute the actual generated data-pipeline method with a controlled model
// boundary. These are protocol/commit guards, not claims about model quality.
let request;
const { DataPipelineModelClient } = module(generated + 'datamanager/DataPipelineModelClient.ets', 'DataPipelineModelClient', {
  ArkJSON: JSON, CloudModelClient: class {}, dataManagerInfo() {}, dataManagerError() {}, dataManagerWarn() {},
  ChatMemoryResponseError: class extends Error {}, normalizeFreeText: value => value.trim()
});
await check('memory pipeline passes saved model, suppresses response logging and rejects cancelled output before validation', async () => {
  let validations = 0, active = true;
  const client = new DataPipelineModelClient({ async chatCompletionText(options) { request = options; return '{"facts":[]}'; } });
  const config = { baseUrl: 'https://fixture.invalid', apiKey: '', model: 'saved-model', enabled: true,
    ensureActive() { if (!active) throw new Error('cancelled'); } };
  await client.callJson(config, 'chat.memory', 'system', 'input', () => { validations++; });
  assert.equal(request.model, 'saved-model'); assert.equal(request.logResponseContent, false); assert.equal(validations, 1);
  client.cloudClient = { async chatCompletionText() { active = false; return '{"facts":["late"]}'; } };
  await assert.rejects(client.callJson(config, 'chat.memory', 'system', 'input', () => { validations++; }), /cancelled/);
  assert.equal(validations, 1);
});
const chatTypes = module(generated + 'pages/chat/ChatSessionTypes.ets', 'splitAssistantThinkingContent,CHAT_MESSAGE_KIND_CHAT');
const extraction = module(generated + 'datamanager/ChatMemoryExtraction.ets',
  'pendingChatMemoryInputs,chatMemoryBatches,CHAT_MEMORY_WINDOW_MS,ChatMemoryResponseError', {
    ...chatTypes, ArkJSON: JSON, CHAT_MODE_LOCAL_INFERENCE: 'local_inference'
  });
const { ChatMemorySync } = module(generated + 'datamanager/ChatMemorySync.ets', 'ChatMemorySync', {
  ...extraction, ArkJSON: JSON, CHAT_MODE_LOCAL_INFERENCE: 'local_inference',
  ChatSessionStorage: { indexPath: root => root + '/index.json', sessionsDir: root => root + '/sessions', sessionPath: (root, id) => root + '/sessions/' + id + '.json' },
  createDataManagerProgress: (...args) => args, createJsonMap: () => ({}), memorySceneLabel: () => '测试场景', dataManagerWarn() {}
});
await check('actual chat-memory sync checkpoints, exports numbered logs and avoids duplicate extraction on rerun', async () => {
  const now = Date.now();
  const sessions = { '/fixture/index.json': JSON.stringify({ sessions: [{ id: 's1', updatedAt: now }] }),
    '/fixture/sessions/s1.json': JSON.stringify({ id: 's1', mode: 'cloud_agent', messages: [
      { id: 'u1', role: 'user', kind: 'chat', content: '我喜欢看书', createdAt: now },
      { id: 'a1', role: 'assistant', kind: 'chat', content: '收到', createdAt: now + 1 }
    ] }) };
  const values = new Map(Object.entries(sessions));
  const storage = { join: (...parts) => parts.join('/'), dirname: path => path.slice(0, path.lastIndexOf('/')),
    exists: path => values.has(path), readText: (path, fallback = '') => values.get(path) ?? fallback,
    readJson: (path, fallback) => values.has(path) ? JSON.parse(values.get(path)) : fallback,
    listDir: () => ['s1.json'], writeJsonAtomic: (path, value) => values.set(path, JSON.stringify(value)),
    writeTextAtomic: (path, value) => values.set(path, value) };
  let calls = 0;
  const model = { async extractChatMemory(inputs) { calls++; return inputs.map(input => ({ source_ref: input.source_ref,
    facts: [{ scene_id: 12, text: '用户喜欢看书' }] })); } };
  const sync = new ChatMemorySync({ filesDir: '/fixture', dailyLogRoot: '/fixture/workflows/daily-log' }, storage, model);
  const result = await sync.sync({ enabled: true }, undefined, now + 1000);
  assert.ok(result.factCount > 0); assert.ok(calls > 0);
  const count = calls;
  assert.ok([...values.values()].some(value => value.includes('1. 用户喜欢看书')));
  const second = await sync.sync({ enabled: true }, undefined, now + 1000);
  assert.equal(second.processedParts, 0); assert.equal(calls, count);
  // Corrupt receipts fail closed rather than extracting and duplicating facts.
  values.set('/fixture/workflows/chat-memory/state.json', '{broken');
  await assert.rejects(sync.sync({ enabled: true }, undefined, now + 1000));
});
const revoked = new Map();
const { CloudModelClient } = module(generated + 'utils/CloudModelClient.ets', 'CloudModelClient', {
  PrivacyConsent: { onHttpCancel: (request, cancel) => revoked.set(request, cancel), destroyHttp: request => request.destroy() },
  AppLogger: { warnCause() {} }
});
await check('non-streaming cancellation settles even when Android destroy never resolves the transport promise', async () => {
  let destroyed = 0;
  const request = { request: () => new Promise(() => {}), destroy: () => { destroyed++; } };
  const client = new CloudModelClient(); client.registerActiveRequest(request);
  const pending = client.requestCancellable(request, 'https://fixture.invalid', {}, 10000);
  client.cancelActiveRequests();
  await assert.rejects(pending, /取消/); assert.ok(destroyed > 0);
});
await check('revocation and timeouts cannot deliver a later non-streaming response', async () => {
  let resolveResponse;
  const request = { request: () => new Promise(resolve => { resolveResponse = resolve; }), destroy() {} };
  const client = new CloudModelClient();
  const pending = client.requestCancellable(request, 'https://fixture.invalid', {}, 10000);
  revoked.get(request)(); resolveResponse({ responseCode: 200, result: 'late private output' });
  await assert.rejects(pending, /撤回/);
  await assert.rejects(client.requestCancellable({ request: () => new Promise(() => {}), destroy() {} },
    'https://fixture.invalid', {}, 5), /超时/);
});
console.log(JSON.stringify({ passed, scope: 'Shared integration contracts with controlled ports; no real provider inference claim' }));
