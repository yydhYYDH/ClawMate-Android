import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { adaptCodexApprovalCard } from '../../shared-frontend/adapters/pages/codex/codex-approval-patches.mjs';
import { adaptCodexPage, adaptCodexTypes } from '../../shared-frontend/adapters/pages/codex/codex-page-patches.mjs';

const reference = new URL('../../.arkui-x-validation/reference/entry/src/main/ets/', import.meta.url);
const read = path => readFileSync(new URL(path, reference), 'utf8');
const load = async text => import('data:text/javascript;base64,' +
  Buffer.from(stripTypeScriptTypes(text, { mode: 'transform' })).toString('base64'));

const page = adaptCodexPage(read('pages/codex/CodexPage.ets'));
assert.match(page, /CodexPickers\.scanPairingCode\(\)/);
assert.match(page, /CodexPickers\.pickDocument\('pairing'\)/);
assert.match(page, /CodexPickers\.pickDocument\(image \? 'image' : 'file'\)/);
assert.doesNotMatch(page, /DocumentViewPicker|@kit\.ScanKit/);
const pair = page.slice(page.indexOf('private async pairFromInput()'), page.indexOf('private chooseModel('));
assert.match(pair, /await candidate\.pair\(bundle\)[\s\S]*?if \(this\.remember\) \{\s*try \{ await CodexCredentialStore\.save\(connection, bundle\.name\); \}[\s\S]*?await this\.connect\('', bundle\.name\)/);

const approval = adaptCodexApprovalCard(read('pages/codex/CodexApprovalCard.ets'));
assert.match(approval, /Button\('拒绝'\)\.enabled\(!this\.busy\)\.onClick\(.*'decline'/);
assert.match(approval, /'accept', \[\]/);

const { mergeCodexTask } = await load(adaptCodexTypes(read('pages/codex/CodexTypes.ets')));
const current = { id: 'task', revision: 1, items: [{ id: 'answer', kind: 'assistant', text: '正在' }] };
const taskDelta = {
  baseRevision: 1,
  task: { id: 'task', revision: 2, status: 'completed', artifacts: [{ id: 'file', name: 'report.md' }] },
  items: [{ id: 'answer', kind: 'assistant', append: true, text: '完成' }],
  itemOrder: ['answer'],
};
const merged = mergeCodexTask(current, { task: null, taskDelta });
assert.equal(merged.status, 'completed');
assert.equal(merged.items[0].text, '正在完成');
assert.equal(merged.artifacts[0].name, 'report.md');
assert.equal(mergeCodexTask(current, { task: null, taskDelta: null, unchanged: true }), undefined);

const { codexLinkedArtifact, codexWebLink } = await load(read('pages/codex/CodexMarkdownLinks.ets')
  .replace("import { CodexArtifact } from './CodexTypes';", ''));
const file = { id: 'file', name: 'report.md' };
assert.equal(codexLinkedArtifact('sandbox:/outputs/report.md', [file]), file);
assert.equal(codexLinkedArtifact('outputs/report.md', [file, { id: 'another', name: file.name }]), undefined);
assert.equal(codexLinkedArtifact('content://provider/report.md', [file]), undefined);
assert.equal(codexWebLink('https://example.com/report.md'), true);

const { checkCodexJsonBudget } = await load(read('utils/CodexRequestBudget.ets'));
assert.throws(() => checkCodexJsonBudget('/v1/tasks/test/turns', '中'.repeat(22_000)), /过多/);
assert.doesNotThrow(() => checkCodexJsonBudget('/v1/uploads', 'a'.repeat(11 * 1024 * 1024)));
console.log('PASS Android Codex workbench picker, approval, delta, delivery link and request budget contracts');
