import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { checkedPath, lockedInputs, verifySources, stageSources, checkToolchain } from './bootstrap.mjs';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'clawmate-bootstrap-'));
let passed = 0;
const check = (name, run) => { run(); console.log('PASS ' + name); passed++; };
const put = (file, bytes) => { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, bytes); };
const saveJson = (file, value) => put(file, JSON.stringify(value));
const blob = bytes => createHash('sha1').update(`blob ${Buffer.byteLength(bytes)}\0`).update(bytes).digest('hex');
const paths = ['experiments/arkui-x/ui-upstream-lock.json', 'shared-frontend/upstream-lock.json', 'shared-frontend/resource-lock.json'];
const sourceFile = 'entry/src/main/ets/pages/A.ets';
const resourceFile = 'entry/src/main/resources/base/element/color.json';
const rawFile = 'entry/src/main/resources/rawfile/workflows/configs/default_fixture.json';
const locks = [sourceFile, resourceFile, rawFile].map(name => ({ repository: 'doulujiyao12/mobiinfra-oh', ref: 'a'.repeat(40), files: [{ path: name, gitBlob: blob(name) }] }));
let next = 0;
function fresh() {
  const root = path.join(fixture, 'case-' + next++), source = path.join(root, 'source'), target = path.join(root, 'repo');
  fs.mkdirSync(target, { recursive: true });
  paths.forEach((name, i) => saveJson(path.join(target, name), locks[i]));
  for (const file of [sourceFile, resourceFile, rawFile]) put(path.join(source, file), file);
  put(path.join(source, 'private.db'), 'private state');
  put(path.join(source, 'entry/src/main/ets/config/DefaultEndpointConfig.ets'), 'private defaults');
  return { root, source, target };
}
try {
  check('only allowlisted byte-verified inputs are staged, with source/private files unchanged', () => {
    const f = fresh(), before = fs.readFileSync(path.join(f.source, sourceFile));
    assert.equal(stageSources(f.target, f.source).files, 3);
    assert.equal(fs.readFileSync(path.join(f.target, '.arkui-x-validation/reference', rawFile), 'utf8'), rawFile);
    assert.ok(!fs.existsSync(path.join(f.target, '.arkui-x-validation/reference/private.db')));
    assert.ok(!fs.existsSync(path.join(f.target, '.arkui-x-validation/reference/entry/src/main/ets/config')));
    assert.deepEqual(fs.readFileSync(path.join(f.source, sourceFile)), before);
  });
  check('repeat bootstrap verifies existing inputs without rewriting them', () => {
    const f = fresh(); stageSources(f.target, f.source);
    const file = path.join(f.target, '.arkui-x-validation/reference', resourceFile), before = fs.statSync(file).mtimeMs;
    stageSources(f.target, f.source); assert.equal(fs.statSync(file).mtimeMs, before);
  });
  check('modified source fails before any generated directory is created', () => {
    const f = fresh(); put(path.join(f.source, sourceFile), 'changed');
    assert.throws(() => stageSources(f.target, f.source), /drift/);
    assert.ok(!fs.existsSync(path.join(f.target, '.arkui-x-validation')));
  });
  check('missing resource fails before any staging writes', () => {
    const f = fresh(); fs.unlinkSync(path.join(f.source, rawFile));
    assert.throws(() => stageSources(f.target, f.source), /ENOENT/);
    assert.ok(!fs.existsSync(path.join(f.target, '.arkui-x-validation')));
  });
  check('an existing different reference is preserved and no remaining files are copied', () => {
    const f = fresh(), existing = path.join(f.target, '.arkui-x-validation/reference', resourceFile);
    put(existing, 'old input'); assert.throws(() => stageSources(f.target, f.source), /Refuse to overwrite/);
    assert.equal(fs.readFileSync(existing, 'utf8'), 'old input');
    assert.ok(!fs.existsSync(path.join(f.target, '.arkui-x-validation/reference', sourceFile)));
  });
  check('parent traversal, absolute paths and Windows alternate streams are rejected', () => {
    for (const p of ['../outside', '/outside', 'entry/../../outside', 'C:/outside', 'entry\\file', 'entry/file:stream', 'entry//file']) assert.throws(() => checkedPath(fixture, p), /Unsafe/);
  });
  check('linked source directory is rejected before import', () => {
    const f = fresh(), other = path.join(f.root, 'linked-source'); fs.mkdirSync(other);
    fs.symlinkSync(path.join(f.source, 'entry'), path.join(other, 'entry'), 'junction');
    assert.throws(() => verifySources(f.target, other), /Linked/);
  });
  check('linked destination cannot write outside the generated workspace', () => {
    const f = fresh(), outside = path.join(f.root, 'outside'); fs.mkdirSync(outside);
    fs.symlinkSync(outside, path.join(f.target, '.arkui-x-validation'), 'junction');
    assert.throws(() => stageSources(f.target, f.source), /Linked/);
    assert.deepEqual(fs.readdirSync(outside), []);
  });
  check('different pinned revisions fail before import', () => {
    const f = fresh(); saveJson(path.join(f.target, paths[1]), { ...locks[1], ref: 'b'.repeat(40) });
    assert.throws(() => lockedInputs(f.target), /revision/);
  });
  check('conflicting blobs across locks fail', () => {
    const f = fresh(); saveJson(path.join(f.target, paths[1]), { ...locks[0], files: [{ path: sourceFile, gitBlob: 'c'.repeat(40) }] });
    assert.throws(() => lockedInputs(f.target), /blob/);
  });
  check('private defaults and unexpected resource destinations cannot be added to import lists', () => {
    const f = fresh(); saveJson(path.join(f.target, paths[0]), { ...locks[0], files: [{ path: 'entry/src/main/ets/config/DefaultEndpointConfig.ets', gitBlob: 'a'.repeat(40) }] });
    assert.throws(() => lockedInputs(f.target), /Private defaults/);
    saveJson(path.join(f.target, paths[0]), locks[0]);
    saveJson(path.join(f.target, paths[1]), { ...locks[1], files: [{ ...locks[1].files[0], target: '../../outside' }] });
    assert.throws(() => lockedInputs(f.target), /destination/);
  });
  const toolsRoot = path.join(fixture, 'tools'), toolRepo = path.join(fixture, 'tool-repo');
  const lock = JSON.parse(fs.readFileSync(path.join(repo, 'experiments/arkui-x/toolchain-lock.json'), 'utf8'));
  // Unit fixture uses the actual host Node version. Real build uses the unmodified
  // production lock and separately invokes the installed Node20 executable.
  lock.nodeForBuild = process.version.slice(1);
  saveJson(path.join(toolRepo, 'experiments/arkui-x/toolchain-lock.json'), lock);
  const toolPaths = { arkuiXSdk: path.join(toolsRoot, 'ax'), openHarmonySdk: path.join(toolsRoot, 'oh'), buildTools: path.join(toolsRoot, 'bt'), buildNode: process.execPath };
  const ax = path.join(toolPaths.arkuiXSdk, '20/arkui-x/arkui-x.json');
  const oh = path.join(toolPaths.openHarmonySdk, '20/ets/oh-uni-package.json');
  saveJson(ax, { apiVersion: '20', version: lock.arkuiXRuntime, releaseType: 'Release' });
  for (const name of ['ets', 'toolchains']) saveJson(path.join(toolPaths.openHarmonySdk, '20', name, 'oh-uni-package.json'), { apiVersion: '20', version: lock.openHarmonySdk, releaseType: 'Release' });
  for (const [name, version] of [['hvigor', lock.hvigor], ['hvigor-ohos-plugin', lock.hvigor], ['hvigor-ohos-arkui-x-plugin', lock.arkuiXBuildPlugin]]) saveJson(path.join(toolPaths.buildTools, 'node_modules/@ohos', name, 'package.json'), { version });
  check('matching toolchain metadata and executable version pass validation', () => assert.equal(checkToolchain(toolRepo, toolPaths).node, process.version.slice(1)));
  check('wrong SDK version, API and Beta metadata fail validation', () => {
    const original = JSON.parse(fs.readFileSync(oh, 'utf8'));
    for (const changed of [{ ...original, version: '0.0.0' }, { ...original, apiVersion: '22' }, { ...original, releaseType: 'Beta' }]) {
      saveJson(oh, changed); assert.throws(() => checkToolchain(toolRepo, toolPaths), /SDK/);
    }
    saveJson(oh, original);
  });
  check('plugin version and Node version drift cannot start a build', () => {
    const file = path.join(toolPaths.buildTools, 'node_modules/@ohos/hvigor/package.json');
    saveJson(file, { version: '0' }); assert.throws(() => checkToolchain(toolRepo, toolPaths), /plugin drift/);
    saveJson(file, { version: lock.hvigor }); lock.nodeForBuild = '0.0.0';
    saveJson(path.join(toolRepo, 'experiments/arkui-x/toolchain-lock.json'), lock);
    assert.throws(() => checkToolchain(toolRepo, toolPaths), /Build Node/);
  });
} finally {
  assert.ok(path.resolve(fixture).startsWith(path.resolve(os.tmpdir()) + path.sep));
  fs.rmSync(fixture, { recursive: true, force: true });
}
console.log(JSON.stringify({ passed, scope: 'Allowlisted source/resource import and pinned toolchain validation; no SDK download or clean-machine certification' }));
