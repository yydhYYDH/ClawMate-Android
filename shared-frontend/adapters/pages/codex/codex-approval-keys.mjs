// Android adaptation of pages/codex/CodexPage.ets.
//
// Two robustness fixes, both found on a real device:
//
// 1. `result.task !== undefined` guards a task-shaped reply, but a long poll that reports no
//    change answers `{"unchanged":true}` with `task: null`. The guard let null through, so
//    applyTask(task) then threw "Cannot read property id of null" and the user saw an error
//    banner right after a successful approval. Every such guard now also rejects null.
//
// 2. The approvals key function could be called for an entry that had just been removed,
//    which threw the same error.
//
// Every other adaptation on this page (ScanKit, CameraKit, MediaLibraryKit, FrostedPage)
// lives in codex-page-patches.mjs.
import { replaceChecked } from '../../../source-patches.mjs';

/**
 * @param {string} source the staged, already-adapted page
 * @return {string} the page with null-safe task handling
 */
export function adaptCodexApprovalKeys(source) {
  let out = source;

  // A poll may answer with no task at all; treat null exactly like undefined.
  const guard = 'if (epoch === this.epoch && result.task !== undefined)';
  const fixed = 'if (epoch === this.epoch && result.task !== null && result.task !== undefined)';
  let count = 0;
  while (out.includes(guard)) {
    out = out.replace(guard, fixed);
    count += 1;
    if (count > 8) throw new Error('CodexPage: unexpected number of task guards');
  }
  if (count === 0) throw new Error('CodexPage: task guard not found');

  // mergeCodexTask returns null for a reply that carries no task, and the poll loop passed
  // that straight to applyTask. Anchored without line breaks so CRLF does not matter.
  out = replaceChecked(out,
    'if (next !== undefined) {',
    'if (next !== null && next !== undefined) {');

  out = replaceChecked(out,
    '  }, (approval: CodexApproval) => approval.id)',
    '  }, (approval: CodexApproval) => approval === null || approval === undefined ? \'\' : approval.id)');

  return out;
}
