// Conservative import graph. Ordinary imports may be used only as types.
// No direct platform import does not imply that a file is portable.
import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
const option = name => args[args.indexOf(name) + 1];
let inventory;
if (args.includes('--inventory')) {
  inventory = JSON.parse(fs.readFileSync(option('--inventory'), 'utf8'));
} else if (args.includes('--source-root')) {
  const root = path.resolve(option('--source-root'));
  inventory = [];
  function walk(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.isFile() && entry.name.endsWith('.ets')) {
        const source = fs.readFileSync(full, 'utf8');
        const imports = [...source.matchAll(/^[ \t]*import\s+([^;]*?)\s+from\s*(['"])([^'"]+)\2\s*;?/gm)]
          .map(match => match[3]);
        inventory.push({
          path: path.relative(root, full).split(path.sep).join('/'),
          imports,
          platform: imports.filter(name => /^@(?:kit\.|ohos\.|arkui-x\.)|\.so$/.test(name)),
          ui: /@(?:Component|Entry|Builder|CustomDialog)\b/.test(source)
        });
      }
    }
  }
  walk(path.join(root, 'entry/src/main/ets'));
} else {
  throw new Error('Pass --source-root <checkout> or --inventory <saved import metadata>');
}

const files = new Map(inventory.map(file => [file.path, file]));
function resolve(from, name) {
  if (!name.startsWith('.')) return undefined;
  const base = path.posix.normalize(path.posix.join(path.posix.dirname(from), name));
  return [base, base + '.ets', base + '.ts', base + '/index.ets'].find(candidate => files.has(candidate));
}
function closure(root) {
  const seen = new Set();
  function visit(name) {
    if (seen.has(name)) return;
    seen.add(name);
    for (const dependency of files.get(name)?.imports || []) {
      const resolved = resolve(name, dependency);
      if (resolved) visit(resolved);
    }
  }
  visit(root);
  return [...seen];
}
const roots = [
  'pages/home/HomePage.ets', 'pages/chat/ChatPage.ets', 'utils/CloudModelClient.ets',
  'utils/AgentLoopRunner.ets', 'utils/WorkflowRunner.ets', 'pages/onboarding/FirstLaunchOnboarding.ets'
];
const summary = {
  scope: 'Conservative import graph, not a compiler result or code reuse percentage',
  mainEtsFiles: inventory.length,
  filesWithDirectPlatformImports: inventory.filter(file => file.platform.length).length,
  filesWithoutDirectPlatformImports: inventory.filter(file => !file.platform.length).length,
  filesWithUiDecorators: inventory.filter(file => file.ui).length,
  platformModules: [...new Set(inventory.flatMap(file => file.platform))].sort(),
  roots: Object.fromEntries(roots.map(root => {
    const dependencies = closure('entry/src/main/ets/' + root);
    return [root, {
      conservativeFileCount: dependencies.length,
      platformModules: [...new Set(dependencies.flatMap(name => files.get(name)?.platform || []))].sort()
    }];
  }))
};
const json = JSON.stringify(summary, null, 2) + '\n';
if (args.includes('--output')) fs.writeFileSync(option('--output'), json);
process.stdout.write(json);
