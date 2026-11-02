// Host checks of the original ArkTS source, with type erasure only.
// These checks do not establish ArkTS compiler or Android runtime compatibility.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const rootArg = process.argv.indexOf('--source-root');
if (rootArg < 0 || !process.argv[rootArg + 1]) {
  throw new Error('Usage: node verify-contracts.mjs --source-root <read-only HarmonyOS checkout>');
}
const root = path.resolve(process.argv[rootArg + 1]);
const lock = JSON.parse(readFileSync(path.join(here, 'upstream-lock.json'), 'utf8'));
const modules = {};
for (const file of lock.files) {
  const bytes = readFileSync(path.join(root, file.path));
  const blob = createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
  assert.equal(blob, file.gitBlob, `Source drift: ${file.path}; review upstream before updating the lock`);
  const source = bytes.toString('utf8');
  assert.ok(!/^\s*import\s/m.test(source), `Host leaf check cannot resolve imports: ${file.path}`);
  const js = stripTypeScriptTypes(source, { mode: 'transform', sourceUrl: file.path });
  const names = [...js.matchAll(/^export (?:const|let|var|function|class) ([A-Za-z_$][\w$]*)/gm)]
    .map(match => match[1]);
  // Export wrapping changes module linkage only, not implementation statements.
  modules[file.path.slice('entry/src/main/ets/'.length)] =
    new Function(`${js.replace(/^export /gm, '')}\nreturn {${names.join(',')}};`)();
}

let passed = 0;
function check(name, run) {
  run();
  passed++;
  console.log(`PASS ${name}`);
}

const pacer = modules['pages/chat/ChatStreamDisplayPacer.ets'];
check('stream chunks retain order and finish within eight frames', () => {
  const instance = new pacer.ChatStreamDisplayPacer();
  instance.setTarget('session', 'message', '开始', []);
  instance.advanceFrame();
  const text = '开始' + '中文流式回复🙂'.repeat(70);
  instance.setTarget('session', 'message', text, ['调用工具']);
  let before = instance.snapshot().visibleContent;
  instance.beginFinish('session', 'message', text, ['调用工具']);
  let ticks = 0;
  while (instance.shouldScheduleFrame() && ticks < 9) {
    const current = instance.advanceFrame();
    assert.ok(current.visibleContent.startsWith(before));
    before = current.visibleContent;
    ticks++;
  }
  assert.ok(ticks <= 8);
  assert.equal(instance.snapshot().visibleContent, text);
  assert.equal(instance.snapshot().complete, true);
});
check('switching chat sessions clears the old display', () => {
  const instance = new pacer.ChatStreamDisplayPacer();
  instance.setTarget('old', 'message', '旧消息', []);
  instance.advanceFrame();
  assert.equal(instance.setTarget('new', 'other', '新消息', []).visibleContent, '');
});
const policy = modules['pages/chat/ChatStreamUpdatePolicy.ets'];
check('persistence throttle respects time and size boundaries', () => {
  const state = policy.chatStreamUpdateStateAfterFlush(1000, 10);
  assert.equal(policy.shouldFlushChatStreamUpdate(2199, 20, state), false);
  assert.equal(policy.shouldFlushChatStreamUpdate(2200, 20, state), true);
  assert.equal(policy.shouldFlushChatStreamUpdate(1001, 8202, state), true);
  assert.equal(policy.chatStreamUpdateDelayMs(1500, state), 700);
});
const layout = modules['pages/home/HomeRecommendationLayout.ets'];
check('home layout handles empty, partial and capped pages', () => {
  assert.equal(layout.homeRecommendationGridHeight(-1), 0);
  assert.equal(layout.homeRecommendationGridHeight(1), 178);
  assert.equal(layout.homeRecommendationGridHeight(4), 368);
  assert.equal(layout.homeRecommendationGridHeight(100), 368);
});
const cancellation = modules['utils/WorkflowCancellation.ets'];
check('workflow cancellation persists and notifies late subscribers', () => {
  const signal = new cancellation.WorkflowCancellation();
  let calls = 0;
  signal.setHandler(() => calls++);
  signal.cancel();
  signal.cancel();
  assert.equal(calls, 1);
  signal.setHandler(() => calls++);
  assert.equal(calls, 2);
  assert.throws(() => signal.throwIfCancelled(), /用户取消/);
});
const scenes = modules['utils/WorkflowSceneConfig.ets'];
check('memory categories retain legacy mappings and personal information', () => {
  assert.equal(scenes.memorySceneConfigs().length, 12);
  assert.equal(scenes.memorySceneLabel(12), '个人信息');
  assert.deepEqual(scenes.normalizeMemorySceneIds([7, 1, 12, 12, -1, NaN]), [1, 12]);
});
const onboarding = modules['pages/onboarding/OnboardingTypes.ets'];
check('onboarding uses versioned completion and bounded navigation', () => {
  assert.equal(onboarding.shouldShowFirstLaunchOnboarding(''), true);
  assert.equal(onboarding.shouldShowFirstLaunchOnboarding(onboarding.FIRST_LAUNCH_ONBOARDING_VERSION), false);
  assert.equal(onboarding.firstLaunchOnboardingButtonText(99), '进入首页');
  assert.equal(onboarding.clampFirstLaunchOnboardingIndex(-1), 0);
});
const repair = modules['utils/WorkflowJsonRepair.ets'].WorkflowJsonRepair;
check('workflow JSON normalization keeps valid quoted Chinese text', () => {
  const payload = { title: '“我的”工作流', steps: [], enabled: true };
  const fenced = '```json\n' + JSON.stringify(payload) + '\n```';
  assert.deepEqual(JSON.parse(repair.normalizeJsonText(fenced)), payload);
});
console.log(JSON.stringify({
  upstreamCommit: lock.ref,
  exactSourceFiles: lock.files.length,
  passed,
  scope: 'Host JavaScript contracts only; no Android runtime, ArkTS compilation or cloud inference claim'
}));
