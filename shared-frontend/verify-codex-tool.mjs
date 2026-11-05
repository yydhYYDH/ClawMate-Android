// Checks for the archived Codex tool: argument validation and result bounding.
//
// The tool shapes what the cloud model may ask for and what it gets back, so the risky
// properties are all negative ones: the model must not be able to smuggle extra fields,
// a foreign run id, or an unbounded answer.
//
// Run: node shared-frontend/verify-codex-tool.mjs   (Node 24, uses stripTypeScriptTypes)
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripTypeScriptTypes } from 'node:module';

const here = path.dirname(fileURLToPath(import.meta.url));
const source = path.join(here, 'adapters/platform/CodexTool.ets');
const code = stripTypeScriptTypes(fs.readFileSync(source, 'utf8'), { mode: 'strip' });

// The module only imports the ArkTS JSON alias; map it to the host implementation.
const body = code
  .replace("import { JSON as ArkJSON } from '@kit.ArkTS';", '')
  .replace("import { CloudChatToolDefinition } from '../utils/CloudModelClient';", '')
  .replaceAll('ArkJSON', 'JSON');
const module = await import('data:text/javascript;base64,' + Buffer.from(body).toString('base64'));

let passed = 0;
const check = (name, run) => { run(); console.log('PASS ' + name); passed += 1; };
const throws = (fn, pattern) => assert.throws(fn, pattern);

check('a minimal task is accepted and trimmed', () => {
  const request = module.parseCodexTaskArguments(JSON.stringify({ task: '  做个报告  ' }));
  assert.equal(request.task, '做个报告');
  assert.equal(request.continueRunId, '');
});

check('a valid continuation run id is accepted', () => {
  const id = '123e4567-e89b-42d3-a456-426614174000';
  const request = module.parseCodexTaskArguments(JSON.stringify({ task: '改一下', continue_run_id: id }));
  assert.equal(request.continueRunId, id);
});

check('unknown fields are refused', () => {
  throws(() => module.parseCodexTaskArguments(JSON.stringify({ task: 'x', workspaceId: '/etc' })), /不接受参数/);
});

check('the model cannot choose a connection, path or permission', () => {
  for (const field of ['origin', 'token', 'workspace', 'path', 'mode', 'approvalMode', 'connectionId']) {
    throws(() => module.parseCodexTaskArguments(JSON.stringify({ task: 'x', [field]: 'y' })), /不接受参数/);
  }
});

check('an over-long task is refused', () => {
  throws(() => module.parseCodexTaskArguments(JSON.stringify({ task: 'x'.repeat(20_001) })), /task/);
});

check('a non-run-id continuation is refused', () => {
  throws(() => module.parseCodexTaskArguments(JSON.stringify({ task: 'x', continue_run_id: '../etc' })), /continue_run_id/);
  throws(() => module.parseCodexTaskArguments(JSON.stringify({ task: 'x', continue_run_id: 'short' })), /continue_run_id/);
});

check('malformed arguments do not throw an untyped error', () => {
  throws(() => module.parseCodexTaskArguments('not json'), /JSON/);
  throws(() => module.parseCodexTaskArguments('[]'), /对象/);
  throws(() => module.parseCodexTaskArguments('null'), /对象/);
});

const outcome = (over = {}) => ({
  ok: true, status: 'completed', runId: 'r1', computer: 'pc', answer: 'ok',
  files: [], receivedIds: [], error: '', ...over,
});

check('a small result fits and reports no truncation', () => {
  const payload = JSON.parse(module.codexTaskToolPayload(outcome()));
  assert.equal(payload.ok, true);
  assert.equal(payload.truncated, false);
  assert.equal(payload.summary, 'ok');
});

check('a long answer is truncated under the tool budget', () => {
  const text = module.codexTaskToolPayload(outcome({ answer: '啊'.repeat(120_000) }));
  assert.ok(text.length <= 3900, `payload was ${text.length} characters`);
  assert.equal(JSON.parse(text).truncated, true);
});

check('many files are truncated under the tool budget', () => {
  const files = Array.from({ length: 200 }, (_, i) => ({ id: 'f' + i, name: 'file-' + i + '.txt', mimeType: 'text/plain' }));
  const text = module.codexTaskToolPayload(outcome({ files, receivedIds: files.map(f => f.id) }));
  assert.ok(text.length <= 3900, `payload was ${text.length} characters`);
  const payload = JSON.parse(text);
  assert.equal(payload.file_count, 200);
  assert.equal(payload.received_count, 200);
  assert.equal(payload.truncated, true);
});

check('the payload never carries a token, path or file body', () => {
  const text = module.codexTaskToolPayload(outcome({
    answer: 'ok',
    files: [{ id: 'f1', name: 'report.pdf', mimeType: 'application/pdf' }],
    receivedIds: ['f1'],
  }));
  for (const secret of ['token', 'Bearer', '/home/', 'C:\\', 'BEGIN CERTIFICATE']) {
    assert.ok(!text.includes(secret), `payload leaked ${secret}`);
  }
});

check('saved_on_phone reflects what actually arrived', () => {
  const files = [{ id: 'f1', name: 'a.txt', mimeType: 'text/plain' }, { id: 'f2', name: 'b.txt', mimeType: 'text/plain' }];
  const payload = JSON.parse(module.codexTaskToolPayload(outcome({ files, receivedIds: ['f1'] })));
  assert.equal(payload.files.find(f => f.id === 'f1').saved_on_phone, true);
  assert.equal(payload.files.find(f => f.id === 'f2').saved_on_phone, false);
});

check('the definition exposes exactly two bounded parameters', () => {
  const definition = module.runCodexTaskDefinition();
  assert.equal(definition.type, 'function');
  assert.equal(definition.function.name, 'run_codex_task');
  assert.deepEqual(definition.function.parameters.required, ['task']);
  assert.equal(definition.function.parameters.additionalProperties, false);
  assert.deepEqual(Object.keys(definition.function.parameters.properties).sort(), ['continue_run_id', 'task']);
});

check('a disconnected run is described as unknown, never as failed', () => {
  const message = module.codexTerminalMessage(JSON.stringify({ status: 'disconnected' }));
  assert.match(message, /同步/);
  assert.match(message, /重复下发/);
});

check('a cancelled run is described as cancelled', () => {
  for (const status of ['cancelled', 'rejected', 'invalid_arguments']) {
    assert.match(module.codexTerminalMessage(JSON.stringify({ status })), /取消/);
  }
});

check('a successful run has no terminal override', () => {
  assert.equal(module.codexTerminalMessage(JSON.stringify({ status: 'completed' })), null);
});

console.log(JSON.stringify({ passed, scope: 'ArkTS tool argument and payload policy; no device or model call' }));
