import assert from 'node:assert/strict';
import fs from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';

const source = fs.readFileSync(new URL('./adapters/platform/NativeCapabilities.ets', import.meta.url), 'utf8');
const js = stripTypeScriptTypes(source.replace(/^import .*;\r?\n/gm, '')).replace(/^export /gm, '');
let handler;
const Native = runInNewContext(js + '\nNativeCapabilities;', {
  Bridge: { createBridge: () => ({ callMethod: (...args) => handler(...args) }) },
  setTimeout: resolve => setTimeout(resolve, 1)
});
let passed = 0;
async function check(name, task) { await task(); console.log('PASS ' + name); passed++; }
const snapshot = (status, text = '', error = '', v = 1) => JSON.stringify({ v, status, text, error });
await check('running OCR delivers real text after completion', async () => {
  let polls = 0;
  handler = async method => method === 'ocrStart' ? 'operation-1' : method === 'ocrPoll' ?
    snapshot(++polls === 1 ? 'running' : 'done', polls === 1 ? '' : '真实识别文本') : assert.fail('unexpected cleanup');
  assert.equal(await Native.recognize('file:///fixture.png', () => {}, () => false), '真实识别文本');
  assert.equal(polls, 2);
});
await check('successful empty image remains empty', async () => {
  handler = async method => method === 'ocrStart' ? 'empty' : snapshot('done');
  assert.equal(await Native.recognize('file:///empty.png', () => {}, () => false), '');
});
await check('cancellation before polling cancels native operation', async () => {
  const calls = [];
  handler = async (method, id) => { calls.push([method, id]); return method === 'ocrStart' ? 'cancel-1' : true; };
  await assert.rejects(Native.recognize('file:///fixture.png', () => {}, () => true), /已取消/);
  assert.deepEqual(calls.map(call => call[0]), ['ocrStart', 'ocrCancel']);
  assert.equal(calls[1][1], 'cancel-1');
});
await check('a late completion after cancellation never becomes text', async () => {
  let cancelled = false, cleaned = false;
  handler = async method => {
    if (method === 'ocrStart') return 'late';
    if (method === 'ocrPoll') { cancelled = true; return snapshot('done', 'must not deliver'); }
    if (method === 'ocrCancel') cleaned = true;
  };
  await assert.rejects(Native.recognize('file:///fixture.png', () => {}, () => cancelled), /已取消/);
  assert.ok(cleaned);
});
await check('engine failures and incompatible protocol versions reject with cleanup', async () => {
  for (const result of [snapshot('failed', '', '引擎不可用'), snapshot('done', 'wrong version', '', 2)]) {
    let cleaned = false;
    handler = async method => {
      if (method === 'ocrStart') return 'failed';
      if (method === 'ocrPoll') return result;
      if (method === 'ocrCancel') cleaned = true;
    };
    await assert.rejects(Native.recognize('file:///fixture.png', () => {}, () => false), /不可用|不兼容/);
    assert.ok(cleaned);
  }
});
await check('runtime rejects an incompatible bridge version', async () => {
  handler = async () => JSON.stringify({ v: 2 });
  await assert.rejects(Native.runtime(), /不兼容/);
});
await check('navigation style and capsule dimensions follow the pinned source', () => {
  const sourceIndex = fs.readFileSync('.arkui-x-validation/reference/entry/src/main/ets/pages/Index.ets', 'utf8');
  const tabs = fs.readFileSync('.arkui-x-validation/reference/entry/src/main/ets/pages/main/FloatingMainTabs.ets', 'utf8');
  const generated = fs.readFileSync('.arkui-x-validation/application/project/entry/src/main/ets/platform/NavigationStyle.ets', 'utf8');
  for (const name of ['tabIconResource', 'tabBarStyle']) {
    const method = sourceIndex.match(new RegExp(`  private ${name}\\([^]*?\\n  \\}`))[0]
      .replace('private ', 'static ').replaceAll('this.tabIconResource', 'NavigationStyle.tabIconResource');
    assert.ok(generated.includes(method));
  }
  for (const name of ['TAB_BAR_HEIGHT', 'TAB_BAR_BOTTOM_MARGIN', 'TAB_BAR_SIDE_MARGIN']) {
    assert.ok(generated.includes(tabs.match(new RegExp(`const ${name}: number = \\d+;`))[0]));
  }
});
console.log(JSON.stringify({ passed, scope: 'Host bridge contracts and source-derived presentation only; not Android recognition or playback evidence' }));
