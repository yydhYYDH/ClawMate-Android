// An extended app-server fixture for the Android client's end-to-end checks.
//
// The upstream test fixture (fake-app-server.mjs) only produces a canned answer, so the
// approval and artifact-delivery paths cannot be exercised through it. This one adds:
//
//   - "approval" turns: the server asks for a command approval before finishing;
//   - "deliver" turns: the final message links a file under outputs/, which the bridge
//     publishes as an artifact the phone must download and verify.
//
// It is a test double only: no inference, no network, no file outside its own workspace.
import { createInterface } from 'node:readline';
import { randomUUID, createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const workspace = process.env.CLAWMATE_FIXTURE_WORKSPACE || process.cwd();
const send = (message) => process.stdout.write(`${JSON.stringify(message)}\n`);

/** Writes a delivery file and returns its relative name plus digest. */
function publishDelivery(threadId) {
  const directory = join(workspace, 'outputs');
  mkdirSync(directory, { recursive: true });
  const name = `report-${threadId.slice(0, 8)}.md`;
  const body = `# Fixture report\n\nthread: ${threadId}\ngenerated: ${new Date().toISOString()}\n`;
  writeFileSync(join(directory, name), body, 'utf8');
  return { name, sha256: createHash('sha256').update(body).digest('hex'), size: Buffer.byteLength(body) };
}

const pending = new Map();

createInterface({ input: process.stdin }).on('line', (line) => {
  const m = JSON.parse(line);
  if (m.method === 'initialized') return;
  if (m.method === 'initialize') return send({ id: m.id, result: { userAgent: 'android-fixture' } });
  if (m.method === 'model/list') {
    return send({ id: m.id, result: { data: [{
      id: 'fixture', model: 'fixture', displayName: 'Fixture',
      defaultReasoningEffort: 'medium',
      supportedReasoningEfforts: [{ reasoningEffort: 'medium' }],
      inputModalities: ['text', 'image'],
    }], nextCursor: null } });
  }
  if (m.method === 'thread/start') return send({ id: m.id, result: { thread: { id: randomUUID() } } });
  if (m.method === 'thread/resume') return send({ id: m.id, result: { thread: { id: m.params.threadId } } });

  // A response to an approval request this fixture raised earlier.
  if (m.id !== undefined && pending.has(m.id)) {
    const turnId = pending.get(m.id);
    pending.delete(m.id);
    const accepted = m.result?.decision === 'accept' || m.result?.decision === 'approved';
    send({ method: 'item/agentMessage/delta', params: { threadId: m.params?.threadId, turnId, itemId: 'answer', delta: accepted ? '已批准' : '已拒绝' } });
    send({ method: 'item/completed', params: { threadId: m.params?.threadId, turnId, item: { id: 'answer', type: 'agentMessage', text: accepted ? '已批准执行' : '已拒绝执行' } } });
    return send({ method: 'turn/completed', params: { threadId: m.params?.threadId, turn: { id: turnId, status: 'completed', error: null } } });
  }

  if (m.method === 'turn/start') {
    const turnId = randomUUID();
    const threadId = m.params.threadId;
    const text = (m.params.input || []).filter((i) => i.type === 'text').map((i) => i.text).join('\n');
    send({ method: 'turn/started', params: { threadId, turn: { id: turnId, status: 'inProgress' } } });
    send({ id: m.id, result: { turn: { id: turnId, status: 'inProgress' } } });

    if (/\bapprove\b|\b审批\b/i.test(text)) {
      // Ask the phone for permission before finishing.
      const requestId = randomUUID();
      pending.set(requestId, { threadId, turnId });
      return send({
        id: requestId,
        method: 'item/commandExecution/requestApproval',
        params: {
          threadId, turnId, command: 'rm -rf build', cwd: workspace,
          reason: 'fixture requests permission', availableDecisions: ['accept', 'decline'],
        },
      });
    }

    if (/\bdeliver\b|\b交付\b/i.test(text)) {
      const file = publishDelivery(threadId);
      const link = `outputs/${file.name}`;
      send({ method: 'item/agentMessage/delta', params: { threadId, turnId, itemId: 'answer', delta: '已生成 ' } });
      send({ method: 'item/agentMessage/delta', params: { threadId, turnId, itemId: 'answer', delta: `[${file.name}](${link})` } });
      send({ method: 'item/completed', params: { threadId, turnId, item: { id: 'answer', type: 'agentMessage', text: `已生成 [${file.name}](${link})` } } });
      return send({ method: 'turn/completed', params: { threadId, turn: { id: turnId, status: 'completed', error: null } } });
    }

    send({ method: 'item/agentMessage/delta', params: { threadId, turnId, itemId: 'answer', delta: '测试' } });
    send({ method: 'item/agentMessage/delta', params: { threadId, turnId, itemId: 'answer', delta: '成功' } });
    send({ method: 'item/completed', params: { threadId, turnId, item: { id: 'answer', type: 'agentMessage', text: '测试成功' } } });
    return send({ method: 'turn/completed', params: { threadId, turn: { id: turnId, status: 'completed', error: null } } });
  }

  if (m.method === 'turn/interrupt') {
    return send({ id: m.id, result: {} });
  }
  if (m.method === 'fixture/exit') return process.exit(0);
  send({ id: m.id, error: { code: -32601, message: 'Unknown fixture method' } });
});
