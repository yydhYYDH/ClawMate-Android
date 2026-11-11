// Android adaptation of pages/codex/CodexApprovalCard.ets.
//
// One behavioural fix: the row holding the accept/decline buttons carried
// `.enabled(!this.busy)`. Under ArkUI-X a container's enabled state is inherited by its
// children and takes precedence, so once the row was rendered while busy, "仅允许本次"
// stayed disabled — leaving the phone able to decline an approval but never to grant one.
// Observed on device: the bridge reported canAccept=true while the button was clickable=false.
//
// Each button now decides for itself, so a transient busy state cannot permanently disable
// the accept path.

const ACCEPT_LABEL = "Button('仅允许本次')";
const DECLINE_PREFIX = "Button('拒绝').onClick(";
const ROW_GUARD = '}.enabled(!this.busy)';

/**
 * @param {string} source the staged upstream approval card
 * @return {string} the card with per-button enablement
 */
export function adaptCodexApprovalCard(source) {
  // The upstream file uses CRLF; anchoring on whole lines avoids depending on that.
  const newline = source.includes('\r\n') ? '\r\n' : '\n';
  const lines = source.split(/\r?\n/);

  const declineAt = lines.findIndex((line) => line.trim().startsWith(DECLINE_PREFIX));
  if (declineAt < 0) throw new Error('CodexApprovalCard: decline button not found');
  const acceptAt = declineAt + 1;
  if (!lines[acceptAt].includes(ACCEPT_LABEL)) {
    throw new Error('CodexApprovalCard: accept button not where expected');
  }
  const rowCloseAt = acceptAt + 2;
  if (!lines[rowCloseAt].includes(ROW_GUARD)) {
    throw new Error('CodexApprovalCard: row enabled guard not where expected');
  }

  const indent = lines[declineAt].match(/^\s*/)[0];
  lines[declineAt] = lines[declineAt].replace(DECLINE_PREFIX, "Button('拒绝').enabled(!this.busy).onClick(");
  lines[acceptAt] = `${indent}${ACCEPT_LABEL}.enabled(this.approval.canAccept === true && !this.busy)`;

  // Drop the container-level guard; it is what shadowed the per-button state.
  lines[rowCloseAt] = lines[rowCloseAt].replace(ROW_GUARD, '}');

  return lines.join(newline);
}
