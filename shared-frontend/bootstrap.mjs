import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { spawnSync as win32Spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const json = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const blob = bytes => createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
// Windows junctions surface as ordinary directories to every Node stat (identical mode,
// and lstat().isSymbolicLink() is false), so they are detected with the Win32 attribute
// query instead. Without this a redirected input or output root would be accepted.
function isLinked(stat, full, windowsAttributes) {
  if (stat.isSymbolicLink()) return true;
  if (process.platform !== 'win32') return false;
  return ((windowsAttributes?.get(full) ?? win32Attributes(full)) & 0x400) !== 0;
}
function win32Attributes(full) {
  const out = win32Spawn('powershell.exe',
    ['-NoProfile', '-NonInteractive', '-Command', `(Get-Item -LiteralPath '${full.replace(/'/g, "''")}' -Force).Attributes.value__`],
    { encoding: 'utf8', windowsHide: true });
  if (out.status !== 0) throw new Error(`Unable to inspect input attributes: ${full}`);
  const value = Number.parseInt(String(out.stdout).trim(), 10);
  if (!Number.isFinite(value)) throw new Error(`Unable to inspect input attributes: ${full}`);
  return value;
}
function pathParts(relative) {
  if (typeof relative !== 'string' || !relative || relative.includes('\\') ||
      relative.split('/').some(p => !p || p === '.' || p === '..' || p.includes(':'))) throw new Error('Unsafe input path');
  return relative.split('/');
}
export function checkedPath(root, relative) {
  return checkPath(root, relative);
}
function checkPath(root, relative, windowsAttributes) {
  const parts = pathParts(relative);
  const base = fs.realpathSync(root);
  let current = base;
  for (const part of parts) {
    current = path.join(current, part);
    const stat = fs.lstatSync(current, { throwIfNoEntry: false });
    if (stat) {
      if (isLinked(stat, current, windowsAttributes)) throw new Error(`Linked input/output rejected: ${relative}`);
    } else {
      // Descendants cannot exist once a directory is missing.
      break;
    }
  }
  return path.join(base, ...parts);
}

// Query every locked path component in one PowerShell process. A reparse point
// is still rejected by checkPath before any descendant can be read.
function verifyWindowsAttributes(root, files) {
  if (process.platform !== 'win32') return undefined;
  const prefixes = new Set();
  for (const file of files) {
    let current = root;
    for (const part of pathParts(file.path)) {
      current = path.join(current, part);
      prefixes.add(current);
    }
  }
  const paths = [...prefixes];
  const command = "foreach ($path in ([Console]::In.ReadToEnd() | ConvertFrom-Json)) { " +
    "try { [int](Get-Item -LiteralPath $path -Force -ErrorAction Stop).Attributes } catch { -1 } }";
  const out = win32Spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command],
    { input: JSON.stringify(paths).replace(/[^\x00-\x7f]/g, char =>
      '\\u' + char.charCodeAt(0).toString(16).padStart(4, '0')), encoding: 'utf8', windowsHide: true });
  const values = String(out.stdout).trim().split(/\r?\n/).map(Number);
  if (out.status !== 0 || values.length !== paths.length || values.some(value => !Number.isInteger(value))) {
    throw new Error('Unable to inspect locked input attributes');
  }
  return new Map(paths.map((file, index) => [file, values[index]]));
}
// Every entry may pin its own `ref`; without one it inherits the lock-level `ref`.
// A single shared revision cannot describe inputs that upstream changed between the
// integrated revision and a newer revision whose new directories are being adopted.
export function lockedInputs(repo) {
  const files = new Map();
  const revisions = new Set();
  for (const name of ['experiments/arkui-x/ui-upstream-lock.json', 'shared-frontend/upstream-lock.json', 'shared-frontend/resource-lock.json']) {
    const lock = json(path.join(repo, name));
    if (lock.repository !== 'doulujiyao12/mobiinfra-oh' || !/^[a-f0-9]{40}$/.test(lock.ref)) throw new Error('Invalid source identity');
    revisions.add(lock.ref);
    for (const file of lock.files) {
      const ref = file.ref === undefined ? lock.ref : file.ref;
      if (!/^[a-f0-9]{40}$/.test(ref)) throw new Error('Invalid source revision');
      if (!/^[a-f0-9]{40}$/.test(file.gitBlob)) throw new Error('Invalid source blob');
      if (!/^(entry\/src\/main\/(ets\/.+\.ets|resources\/(base|dark)\/(element|media)\/.+|resources\/rawfile\/workflows\/configs\/default_[a-z_]+\.json)|AppScope\/resources\/base\/media\/Icon\.png)$/.test(file.path)) throw new Error('Input outside reviewed source/resource scope');
      if (/Default(?:Endpoint|Account)Config\.ets$/.test(file.path)) throw new Error('Private defaults must never be staged');
      const previous = files.get(file.path);
      if (previous && (previous.gitBlob !== file.gitBlob || previous.ref !== ref)) throw new Error(`Source locks disagree on blob or revision: ${file.path}`);
      if (file.target && file.target !== 'AppScope/resources/base/media/app_icon.png') throw new Error('Unexpected resource destination');
      revisions.add(ref);
      files.set(file.path, { ...file, ref });
    }
  }
  const list = [...files.values()];
  // Keep the historical `revision` field meaning "the integrated revision" so callers
  // that only understand one revision continue to see the base pin, and expose the
  // full set for diagnostics and for per-entry staging.
  const count = ref => list.filter(file => file.ref === ref).length;
  const revision = [...revisions].sort((a, b) => count(b) - count(a) || a.localeCompare(b, 'en'))[0];
  return { revision, revisions: [...revisions].sort(), files: list.sort((a, b) => a.path.localeCompare(b.path, 'en')) };
}
export function verifySources(repo, sourceRoot) {
  const lock = lockedInputs(repo);
  const root = fs.realpathSync(sourceRoot);
  const attributes = verifyWindowsAttributes(root, lock.files);
  let total = 0;
  const files = lock.files.map(file => {
    const full = checkPath(root, file.path, attributes);
    const stat = fs.statSync(full);
    if (!stat.isFile() || stat.size > 8 * 1024 * 1024 || (total += stat.size) > 128 * 1024 * 1024) throw new Error('Source input exceeds bounds');
    const bytes = fs.readFileSync(full);
    if (blob(bytes) !== file.gitBlob) throw new Error(`Pinned source/resource drift: ${file.path}`);
    return { ...file, bytes };
  });
  return { revision: lock.revision, revisions: lock.revisions, files, bytes: total };
}
export function stageSources(repo, sourceRoot) {
  // Check every source and existing destination before creating or changing files.
  const verified = verifySources(repo, sourceRoot);
  const destination = checkedPath(repo, '.arkui-x-validation/reference');
  for (const file of verified.files) {
    const target = checkedPath(repo, '.arkui-x-validation/reference/' + file.path);
    if (fs.existsSync(target) && (!fs.statSync(target).isFile() || fs.statSync(target).size !== file.bytes.length || blob(fs.readFileSync(target)) !== file.gitBlob)) throw new Error(`Refuse to overwrite existing reference: ${file.path}`);
  }
  for (const file of verified.files) {
    const target = checkedPath(repo, '.arkui-x-validation/reference/' + file.path);
    if (fs.existsSync(target)) continue;
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, file.bytes, { flag: 'wx' });
  }
  return { revision: verified.revision, revisions: verified.revisions, files: verified.files.length, bytes: verified.bytes, destination };
}
export function toolchainPaths(repo, overrides = {}) {
  const scratch = path.join(repo, '.arkui-x-validation');
  const file = path.join(scratch, 'toolchain.local.json');
  const saved = fs.existsSync(file) ? json(file) : {};
  return {
    arkuiXSdk: overrides.arkuiXSdk || saved.arkuiXSdk || path.join(scratch, 'sdk'),
    openHarmonySdk: overrides.openHarmonySdk || saved.openHarmonySdk || path.join(scratch, 'openharmony-sdk'),
    buildTools: overrides.buildTools || saved.buildTools || path.join(scratch, 'build-tools'),
    buildNode: overrides.buildNode || saved.buildNode || (process.platform === 'win32' ? path.join(scratch, 'node/node-v20.19.5-win-x64/node.exe') : process.execPath)
  };
}
export function checkToolchain(repo, paths) {
  const lock = json(path.join(repo, 'experiments/arkui-x/toolchain-lock.json'));
  for (const [file, version] of [
    [path.join(paths.arkuiXSdk, '20/arkui-x/arkui-x.json'), lock.arkuiXRuntime],
    ...['ets', 'toolchains'].map(name => [path.join(paths.openHarmonySdk, '20', name, 'oh-uni-package.json'), lock.openHarmonySdk])
  ]) {
    const metadata = json(file);
    if (metadata.version !== version || Number(metadata.apiVersion) !== lock.apiVersion || metadata.releaseType !== 'Release') throw new Error(`SDK must match locked Release API ${lock.apiVersion} / ${version}: ${file}`);
  }
  for (const [name, version] of [['hvigor', lock.hvigor], ['hvigor-ohos-plugin', lock.hvigor], ['hvigor-ohos-arkui-x-plugin', lock.arkuiXBuildPlugin]]) {
    if (json(path.join(paths.buildTools, 'node_modules/@ohos', name, 'package.json')).version !== version) throw new Error(`Build plugin drift: ${name}`);
  }
  const result = spawnSync(paths.buildNode, ['--version'], { encoding: 'utf8', windowsHide: true });
  if (result.status !== 0 || result.stdout.trim() !== 'v' + lock.nodeForBuild) throw new Error(`Build Node must be ${lock.nodeForBuild}`);
  return { api: lock.apiVersion, arkuiX: lock.arkuiXRuntime, openHarmony: lock.openHarmonySdk, hvigor: lock.hvigor, node: lock.nodeForBuild };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const options = new Map();
  for (let i = 2; i < process.argv.length; i++) {
    const key = process.argv[i];
    if (['--check-toolchain', '--help'].includes(key)) { options.set(key, true); continue; }
    if (!['--source-root', '--arkuix-sdk', '--openharmony-sdk', '--build-tools', '--build-node'].includes(key) || !process.argv[i + 1] || process.argv[i + 1].startsWith('--') || options.has(key)) throw new Error(`Invalid bootstrap option: ${key}`);
    options.set(key, process.argv[++i]);
  }
  if (options.has('--help')) {
    console.log('Stage allowlisted pinned inputs: --source-root DIR [--arkuix-sdk DIR --openharmony-sdk DIR --build-tools DIR --build-node FILE]. --check-toolchain checks preinstalled tools only; no downloads or private source/config imports.');
  } else {
    const overrides = Object.fromEntries([['--arkuix-sdk', 'arkuiXSdk'], ['--openharmony-sdk', 'openHarmonySdk'], ['--build-tools', 'buildTools'], ['--build-node', 'buildNode']].filter(([key]) => options.has(key)).map(([key, name]) => [name, path.resolve(options.get(key))]));
    const paths = toolchainPaths(repo, overrides);
    const tools = checkToolchain(repo, paths);
    if (!options.has('--source-root') && !options.has('--check-toolchain')) throw new Error('Specify --source-root or --check-toolchain');
    const staged = options.has('--source-root') ? stageSources(repo, options.get('--source-root')) : undefined;
    if (Object.keys(overrides).length) {
      const file = checkedPath(repo, '.arkui-x-validation/toolchain.local.json');
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(file, JSON.stringify(paths, null, 2) + '\n');
    }
    console.log(JSON.stringify({ tools, staged }));
  }
}
