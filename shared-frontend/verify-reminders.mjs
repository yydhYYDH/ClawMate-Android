import assert from 'node:assert/strict';
import fs from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
const generated = '.arkui-x-validation/application/project/entry/src/main/ets/';
const read = file => fs.readFileSync(file, 'utf8');
function module(source, names, ports = {}) {
  const js = stripTypeScriptTypes(source.replace(/^import\s+[^;]+;\s*/gm, '')).replace(/^export /gm, '');
  return runInNewContext(js + `\n({${names}});`, { setTimeout, clearTimeout, Date, ...ports });
}
const policy = module(read(generated + 'utils/CollectionReminderPolicy.ets'),
  'CollectionReminderState,collectionReminderCanClaim,collectionReminderDayKey');
const requestKey = 'collectionReminderRequest', readyKey = 'collectionReminderReady';
function fixture() {
  const memory = new Map(), calls = [];
  const state = { v: 1, version: 1, enabled: false, hour: 21, minute: 0, planToken: 'fixture-plan',
    handledDays: [], reminderIds: [], registeredStamp: '', errorText: '', lastCollectedAt: 0 };
  let handler = method => JSON.stringify(state);
  const { CollectionReminderService: service } = module(read('shared-frontend/adapters/utils/CollectionReminderService.ets'), 'CollectionReminderService', {
    ...policy, AppStorage: { get: key => memory.get(key), setOrCreate: (key, value) => memory.set(key, value) },
    preferences: { getPreferencesSync: () => ({ getSync: key => key === 'hour' ? 18 : 42 }) },
    Bridge: { createBridge: () => ({ callMethod: async (...args) => { calls.push(args); return handler(...args); } }) }
  });
  return { service, memory, calls, state, handle: next => { handler = next; },
    request: () => JSON.stringify({ planToken: state.planToken, dueAt: Date.now() - 1000 }) };
}
let passed = 0;
async function check(name, test) { await test(); console.log('PASS ' + name); passed++; }
await check('legacy saved time is passed once to the Android owner before UI operations', async () => {
  const f = fixture(); f.service.initialize({}); f.service.initialize({}); await f.service.refresh();
  assert.deepEqual(f.calls[0], ['reminderInitialize', '18', '42']);
  assert.equal(f.calls.filter(call => call[0] === 'reminderInitialize').length, 1);
  assert.equal(f.service.load().enabled, false);
});
await check('invalid reminder time fails before crossing the native bridge', async () => {
  const f = fixture();
  for (const [hour, minute] of [[-1, 0], [24, 0], [0, 60], [1.5, 2], [NaN, 0]]) await assert.rejects(f.service.setTime(hour, minute), /无效/);
  assert.equal(f.calls.length, 0);
});
await check('time edits and switch operations use typed platform arguments and update the original panel state', async () => {
  const f = fixture();
  f.handle((method, first, second) => {
    if (method === 'reminderSetTime') { f.state.hour = Number(first); f.state.minute = Number(second); }
    if (method === 'reminderSetEnabled') { assert.equal(typeof first, 'boolean'); f.state.enabled = first; }
    return JSON.stringify(f.state);
  });
  await f.service.setTime(12, 34); assert.deepEqual(f.calls[0], ['reminderSetTime', '12', '34']);
  await f.service.setEnabled(true); assert.equal(f.service.load().enabled, true);
  await f.service.setEnabled(false); assert.equal(f.service.load().enabled, false);
  assert.equal(f.service.load().minute, 34);
});
await check('permission and registration errors remain visible without a false registered state', async () => {
  const f = fixture();
  f.handle(method => {
    if (method === 'reminderSetEnabled') { f.state.errorText = '通知未授权'; throw new Error('blocked'); }
    return JSON.stringify(f.state);
  });
  await assert.rejects(f.service.setEnabled(true), /blocked/);
  assert.equal(f.service.load().enabled, false); assert.equal(f.service.load().registeredStamp, '');
  assert.equal(f.service.load().errorText, '通知未授权');
  const resolved = fixture(); resolved.state.errorText = '通知未授权，请重试';
  await assert.rejects(resolved.service.setEnabled(true), /通知未授权/);
  assert.equal(resolved.service.load().enabled, false);
});
await check('an incompatible native protocol is rejected instead of reporting success', async () => {
  const f = fixture(); f.state.v = 2;
  await assert.rejects(f.service.setEnabled(true), /协议版本/);
  assert.equal(f.service.load().enabled, false);
});
await check('background pages and locked windows never become ready for reminder execution', () => {
  const f = fixture();
  f.service.setWindowReady(false); f.service.acceptRuntime(1, '', true); assert.equal(f.memory.get(readyKey), false);
  f.service.setWindowReady(true); f.service.acceptRuntime(1, '', false); assert.equal(f.memory.get(readyKey), false);
  f.service.acceptRuntime(1, '', true); assert.equal(f.memory.get(readyKey), true);
});
await check('malformed, future, expired and foreign reminder requests are rejected by the original policy', async () => {
  const f = fixture(); f.state.enabled = true; await f.service.refresh();
  for (const raw of ['null', '[]', '{bad', JSON.stringify({ planToken: 'forged', dueAt: Date.now() }),
    JSON.stringify({ planToken: f.state.planToken, dueAt: Date.now() + 10000 }),
    JSON.stringify({ planToken: f.state.planToken, dueAt: Date.now() - 48 * 3600000 })]) assert.equal(f.service.pendingIntent(raw), null);
});
await check('claim requires the exact current request before consulting the native durable owner', async () => {
  const f = fixture(); f.state.enabled = true; await f.service.refresh();
  const before = f.calls.length; assert.equal(await f.service.claim(f.request()), false); assert.equal(f.calls.length, before);
});
await check('native rejection cannot start a claim or clear an unconsumed request', async () => {
  const f = fixture(); f.state.enabled = true; await f.service.refresh();
  const request = f.request(); f.memory.set(requestKey, request); f.handle(() => false);
  assert.equal(await f.service.claim(request), false); assert.equal(f.memory.get(requestKey), request);
});
await check('successful native claim refreshes replay protection and clears only the owned request', async () => {
  const f = fixture(); f.state.enabled = true; await f.service.refresh();
  const request = f.request(); f.memory.set(requestKey, request);
  f.handle(method => {
    if (method === 'reminderClaim') { f.state.handledDays = [policy.collectionReminderDayKey(JSON.parse(request).dueAt)]; return true; }
    return JSON.stringify(f.state);
  });
  assert.equal(await f.service.claim(request), true); assert.equal(f.memory.get(requestKey), '');
  assert.equal(await f.service.claim(request), false);
  assert.equal(f.calls.filter(call => call[0] === 'reminderClaim').length, 1);
});
await check('a durable claim write failure propagates before any workflow can run', async () => {
  const f = fixture(); f.state.enabled = true; await f.service.refresh();
  const request = f.request(); f.memory.set(requestKey, request);
  f.handle(() => { throw new Error('disk unavailable'); });
  await assert.rejects(f.service.claim(request), /disk unavailable/); assert.equal(f.memory.get(requestKey), request);
});
const taskSource = read(generated + 'pages/task/TaskPage.ets');
await check('the actual batch method waits for claim and refuses a late foreground loss', async () => {
  const method = taskSource.match(/  private async executeEnabledTasks\([^]*?\n  \}/)[0];
  for (const losesForeground of [false, true]) {
    let harness;
    const { Task } = module('class Task {\n' + method + '\n}', 'Task', {
      CollectionReminderService: { pendingIntent: () => ({}), claim: async () => { if (losesForeground) harness.active = false; return losesForeground; } },
      WorkflowStorage: { loadTasks: () => [] }, AppStorage: { setOrCreate: () => assert.fail('No batch may start') },
      WorkflowCancellation: class { constructor() { assert.fail('No executor may start'); } }, showToastSafely() {}
    });
    harness = new Task(); Object.assign(harness, { active: true, reminderWindowReady: true, taskControlMode: 'phone',
      isTaskExecutionBusy: () => false, flushTaskContextDrafts: () => false, enabledTasks: () => [{ id: 'one', enabled: true }] });
    await harness.executeEnabledTasks('owned-request', true);
  }
});
await check('notification dispatch includes a real user confirmation before batch execution', () => {
  const method = taskSource.match(/  private async startCollectionFromReminder\([^]*?\n  \}/)[0];
  assert.ok(method.indexOf('await promptAction.showDialog') < method.indexOf('await this.runEnabledTasks'));
  assert.ok(method.includes('CollectionReminderService.clearRequest(request)'));
});
await check('a successful echo-only workflow never advances the successful collection timestamp', async () => {
  let marks = 0; const files = new Map();
  const { AndroidWorkflow } = module(read('shared-frontend/adapters/platform/AndroidWorkflow.ets'), 'AndroidWorkflow', {
    CollectionReminderService: { recordSuccessfulCollection: async () => { marks++; } },
    WorkflowStorage: { updateTaskRun() {}, readTaskWorkflowJson: () => '{}', runsDir: () => '/fixture/runs' },
    DataManagerFacade: class { paths = { dailyLogRoot: '/fixture/daily' }; storage = { ensureDir() {}, writeJsonAtomic: (path, value) => files.set(path, value) }; ensureReady() {} },
    Bridge: { createBridge: () => ({ callMethod: async method => method === 'workflowStart' ? 'id' : JSON.stringify({ v: 1, status: 'done', result: JSON.stringify({ status: 'success', message: 'echo', resultSummary: '', steps: [] }) }) }) }
  });
  await AndroidWorkflow.run('/fixture', { id: 'echo', title: 'echo' }, { throwIfCancelled() {}, setHandler() {}, isCancelled: () => false });
  assert.equal(marks, 0); assert.equal(files.size, 1);
});
console.log(JSON.stringify({ passed, scope: 'Original reminder policy, ArkTS/native state and claim contracts, real batch dispatch; host ports only' }));
