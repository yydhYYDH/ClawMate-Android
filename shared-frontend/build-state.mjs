import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const digest = value => hash(JSON.stringify(value));
const readJson = file => JSON.parse(fs.readFileSync(file, 'utf8'));
function filesUnder(root) {
  if (fs.lstatSync(root).isSymbolicLink()) throw new Error(`Generated/input directory must not be a link: ${root}`);
  return fs.readdirSync(root, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name, 'en')).flatMap(item => {
    const file = path.join(root, item.name);
    if (item.isSymbolicLink()) throw new Error(`Generated/input file must not be a link: ${file}`);
    return item.isDirectory() ? filesUnder(file) : [file];
  });
}
function snapshots(repo, files) {
  return [...new Set(files)].map(file => ({ path: path.relative(repo, file).split(path.sep).join('/'),
    sha256: hash(fs.readFileSync(file)) })).sort((a, b) => a.path.localeCompare(b.path, 'en'));
}
function roots(repo) {
  const project = path.join(repo, '.arkui-x-validation/application/project');
  const runtime = path.join(repo, '.arkui-x-validation/application/runtime');
  return { project, runtime, prepared: path.join(project, 'prepared-state.json'), seal: path.join(runtime, 'compiled-state.json') };
}
function inputs(repo) {
  const files = filesUnder(path.join(repo, 'shared-frontend')).filter(file => /\.(ets|mjs|ps1)$/.test(file));
  for (const name of ['shared-frontend/upstream-lock.json', 'experiments/arkui-x/ui-upstream-lock.json',
    'experiments/arkui-x/toolchain-lock.json', 'experiments/arkui-x/prepare-project.mjs', 'experiments/arkui-x/package-android.py', 'shared-frontend/resource-lock.json', 'scripts/build-shared.sh']) {
    files.push(path.join(repo, name));
  }
  for (const name of ['shared-frontend/upstream-lock.json', 'experiments/arkui-x/ui-upstream-lock.json', 'shared-frontend/resource-lock.json']) {
    for (const file of readJson(path.join(repo, name)).files) files.push(path.join(repo, '.arkui-x-validation/reference', file.path));
  }
  files.push(...filesUnder(path.join(repo, 'shared-frontend/template')));
  for (const name of ['onboarding_agent', 'onboarding_avatar', 'onboarding_data_home']) {
    files.push(path.join(repo, 'app/src/main/res/drawable-nodpi', name + '.png'));
  }
  return snapshots(repo, files);
}
function outputs(repo) {
  const { project } = roots(repo);
  return snapshots(repo, [...filesUnder(path.join(project, 'entry/src/main')), ...filesUnder(path.join(project, 'AppScope')), ...filesUnder(path.join(project, 'hvigor')),
    ...['build-profile.json5', 'entry/build-profile.json5', '.arkui-x/arkui-x-config.json5', 'staged-sources.json',
      'hvigorfile.ts', 'oh-package.json5', 'entry/hvigorfile.ts', 'entry/oh-package.json5'].map(name => path.join(project, name))]);
}
export function syncOverlays(source, target, allowedRoot) {
  const resolved = path.resolve(target);
  const root = fs.realpathSync(allowedRoot);
  if (!resolved.startsWith(root + path.sep) || fs.realpathSync(path.dirname(resolved)) !== path.dirname(resolved)) {
    throw new Error('Overlay target must remain inside the generated workspace without links');
  }
  // Check the complete tree before deleting any file. Only generated .ets files
  // are removed; authored adapters and the read-only reference are never edited.
  const previous = fs.existsSync(resolved) ? filesUnder(resolved) : [];
  for (const file of previous) if (file.endsWith('.ets')) fs.unlinkSync(file);
  fs.cpSync(source, resolved, { recursive: true });
}
export function recordPrepared(repo) {
  const state = { v: 1, inputs: inputs(repo), outputs: outputs(repo) };
  state.digest = digest(state);
  fs.writeFileSync(roots(repo).prepared, JSON.stringify(state, null, 2) + '\n');
  return state;
}
export function verifyPrepared(repo) {
  const file = roots(repo).prepared;
  if (!fs.existsSync(file)) throw new Error('No prepared state; run shared-frontend/build.ps1');
  const saved = readJson(file);
  const actual = { v: 1, inputs: inputs(repo), outputs: outputs(repo) };
  if (saved.digest !== digest(actual)) {
    const before = new Map([...saved.inputs, ...saved.outputs].map(file => [file.path, file.sha256]));
    const after = new Map([...actual.inputs, ...actual.outputs].map(file => [file.path, file.sha256]));
    const changed = [...new Set([...before.keys(), ...after.keys()])].filter(name => before.get(name) !== after.get(name));
    throw new Error(`Prepared sources are stale (${changed.slice(0, 8).join(', ')}); run shared-frontend/build.ps1`);
  }
  return saved;
}
function runtimeFiles(repo) {
  const { runtime } = roots(repo);
  return snapshots(repo, [...filesUnder(path.join(runtime, 'src/main/assets')), ...filesUnder(path.join(runtime, 'libs'))]);
}
export function sealRuntime(repo) {
  const prepared = verifyPrepared(repo);
  const state = { v: 1, prepared: prepared.digest, files: runtimeFiles(repo) };
  fs.writeFileSync(roots(repo).seal, JSON.stringify(state, null, 2) + '\n');
  return state;
}
export function verifyRuntime(repo) {
  const prepared = verifyPrepared(repo);
  const { seal } = roots(repo);
  if (!fs.existsSync(seal)) throw new Error('No compiled state; run shared-frontend/build.ps1');
  const saved = readJson(seal);
  if (saved.v !== 1 || saved.prepared !== prepared.digest || digest(saved.files) !== digest(runtimeFiles(repo))) {
    throw new Error('Compiled ArkUI-X runtime is stale or changed; run shared-frontend/build.ps1');
  }
  return { prepared: saved.prepared, runtimeFiles: saved.files.length };
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const command = process.argv[2];
  if (!['seal', 'verify', 'verify-prepared'].includes(command)) throw new Error('Usage: node shared-frontend/build-state.mjs seal|verify|verify-prepared');
  const result = command === 'seal' ? sealRuntime(repo) : command === 'verify' ? verifyRuntime(repo) : verifyPrepared(repo);
  console.log(JSON.stringify({ command, digest: result.digest ?? result.prepared, runtimeFiles: result.runtimeFiles ?? result.files?.length }));
}
