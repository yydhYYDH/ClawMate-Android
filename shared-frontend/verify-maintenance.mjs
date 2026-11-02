import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { replaceChecked, applyPatches } from './source-patches.mjs';
import { syncOverlays, recordPrepared, verifyPrepared, sealRuntime, verifyRuntime } from './build-state.mjs';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let passed = 0;
function check(name, action) { action(); console.log('PASS ' + name); passed++; }
check('missing and ambiguous single replacements stop upstream adaptation', () => {
  assert.throws(() => replaceChecked('changed', 'anchor', 'new'), /drift/);
  assert.throws(() => replaceChecked('anchor anchor', 'anchor', 'new'), /found 2/);
  assert.throws(() => replaceChecked('changed', /anchor/g, 'new'), /drift/);
});
check('explicit global patches preserve JavaScript replacement semantics', () => {
  assert.equal(replaceChecked('a1 a2', /a(\d)/g, 'b$1'), 'b1 b2');
  assert.equal(replaceChecked('a a', 'a', 'b', { all: true, expected: 2 }), 'b b');
  assert.throws(() => replaceChecked('a a a', 'a', 'b', { all: true, expected: 2 }), /found 3/);
  assert.equal(replaceChecked('first', /(first)/, '$1-last'), 'first-last');
  assert.equal(applyPatches('a', [['a', 'b'], ['b', 'c']]), 'c');
  assert.throws(() => applyPatches('a', [['a', 'b'], ['missing', 'c']]), /drift/);
});
check('checked regular expressions retain native replacement cursor semantics', () => {
  const pattern = /a/g; pattern.lastIndex = 7;
  replaceChecked('a', pattern, 'b');
  assert.equal(pattern.lastIndex, 0); // Same final cursor as String.replace.
  assert.throws(() => replaceChecked('x', '', 'y'), /unsupported/);
  assert.throws(() => replaceChecked('x', /x/y, 'y'), /unsupported/);
});
const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'clawmate-maintenance-'));
try {
  const put = (file, content = '') => { const full = path.join(fixture, file); fs.mkdirSync(path.dirname(full), { recursive: true }); fs.writeFileSync(full, content); };
  const source = path.join(fixture, 'authored');
  const target = path.join(fixture, 'generated/overlays');
  put('authored/Current.ets', 'current'); put('generated/overlays/Removed.ets', 'stale');
  check('overlay regeneration removes deleted adaptations and retains authored inputs', () => {
    syncOverlays(source, target, fixture);
    assert.ok(!fs.existsSync(path.join(target, 'Removed.ets')));
    assert.equal(fs.readFileSync(path.join(target, 'Current.ets'), 'utf8'), 'current');
    assert.equal(fs.readFileSync(path.join(source, 'Current.ets'), 'utf8'), 'current');
  });
  check('overlay cleanup refuses paths outside its generated workspace', () => {
    assert.throws(() => syncOverlays(source, path.join(fixture, '../escape'), fixture), /workspace/);
  });
  // A minimal independent project exercises freshness, without modifying the
  // pinned reference or borrowing the developer's live compiled runtime.
  for (const file of ['shared-frontend/Input.ets', 'scripts/build-shared.sh', 'experiments/arkui-x/prepare-project.mjs',
    'experiments/arkui-x/package-android.py', 'experiments/arkui-x/toolchain-lock.json',
    'app/src/main/res/drawable-nodpi/onboarding_agent.png', 'app/src/main/res/drawable-nodpi/onboarding_avatar.png',
    'app/src/main/res/drawable-nodpi/onboarding_data_home.png']) put(file, 'fixture');
  for (const file of ['shared-frontend/upstream-lock.json', 'experiments/arkui-x/ui-upstream-lock.json', 'shared-frontend/resource-lock.json']) put(file, '{"files":[]}');
  for (const base of ['shared-frontend/template', '.arkui-x-validation/application/project']) {
    for (const file of ['AppScope/app.json5', 'hvigor/config.json5', 'entry/src/main/resources/strings.json',
      'entry/src/main/ets/entryability/EntryAbility.ets', 'hvigorfile.ts', 'oh-package.json5', 'entry/oh-package.json5',
      'entry/hvigorfile.ts', 'entry/src/main/module.json5']) put(base + '/' + file, '{}');
  }
  for (const file of ['build-profile.json5', 'entry/build-profile.json5', '.arkui-x/arkui-x-config.json5', 'staged-sources.json']) {
    put('.arkui-x-validation/application/project/' + file, '{}');
  }
  const runtimeFile = '.arkui-x-validation/application/runtime/src/main/assets/arkui-x/entry/ets/modules.abc';
  put(runtimeFile, 'compiled'); put('.arkui-x-validation/application/runtime/libs/arm64-v8a/libfixture.so', 'native');
  check('unchanged preparation and runtime verify with stable digests', () => {
    const first = recordPrepared(fixture); assert.equal(recordPrepared(fixture).digest, first.digest);
    sealRuntime(fixture); assert.equal(verifyRuntime(fixture).runtimeFiles, 2);
  });
  check('editing source, generated code or resources invalidates preparation', () => {
    for (const file of ['shared-frontend/Input.ets', '.arkui-x-validation/application/project/entry/src/main/module.json5',
      'shared-frontend/template/entry/src/main/resources/strings.json', '.arkui-x-validation/application/project/hvigor/config.json5',
      'scripts/build-shared.sh']) {
      const full = path.join(fixture, file), before = fs.readFileSync(full);
      put(file, 'changed'); assert.throws(() => verifyPrepared(fixture), /stale/); fs.writeFileSync(full, before);
    }
  });
  check('new preparation requires recompilation and changed runtime fails verification', () => {
    put('shared-frontend/Input.ets', 'next version'); recordPrepared(fixture);
    assert.throws(() => verifyRuntime(fixture), /runtime is stale/);
    sealRuntime(fixture); put(runtimeFile, 'changed binary');
    assert.throws(() => verifyRuntime(fixture), /runtime is stale/);
  });
} finally {
  // fixture is a direct mkdtemp result under the OS temp directory.
  assert.ok(path.resolve(fixture).startsWith(path.resolve(os.tmpdir()) + path.sep));
  fs.rmSync(fixture, { recursive: true, force: true });
}
if (process.argv.includes('--prepare-twice')) {
  const prepare = () => {
    const result = spawnSync(process.execPath, ['shared-frontend/prepare.mjs'], { cwd: repo, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr + result.stdout);
    return verifyPrepared(repo);
  };
  check('two real preparations are identical despite a stale overlay from an older adaptation', () => {
    const first = prepare();
    const stale = path.join(repo, '.arkui-x-validation/application/overlays/pages/onboarding/FirstLaunchOnboarding.ets');
    const staleSource = path.join(repo, '.arkui-x-validation/application/project/entry/src/main/ets/platform/Abandoned.ets');
    const staleWrapper = path.join(repo, '.arkui-x-validation/application/project/hvigor/orphan-wrapper.js');
    assert.ok(!fs.existsSync(stale), 'Do not overwrite a live overlay');
    fs.mkdirSync(path.dirname(stale), { recursive: true });
    fs.writeFileSync(stale, '// stale adaptation must never enter the compilation input');
    assert.ok(!fs.existsSync(staleSource) && !fs.existsSync(staleWrapper));
    fs.writeFileSync(staleSource, '// untracked old generated source');
    fs.writeFileSync(staleWrapper, '// wrapper from an older template');
    const second = prepare();
    assert.equal(second.digest, first.digest);
    assert.ok(!fs.existsSync(stale));
    assert.ok(!fs.existsSync(staleSource) && !fs.existsSync(staleWrapper));
  });
}
console.log(JSON.stringify({ passed, scope: 'Source adaptation, preparation determinism and stale runtime prevention' }));
