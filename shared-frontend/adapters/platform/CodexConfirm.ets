// A confirmation dialog that cannot wedge the page.
//
// The upstream Codex page awaited a Promise resolved only by a dialog button:
//
//   const confirmed = await new Promise<boolean>((resolve) => { ...showAlertDialog(...) });
//
// On Android the dialog can disappear without either callback running — observed by
// dismissing it from outside — which leaves `busy` set and the page permanently inert.
// Every dialog here therefore settles once: on a button, or on a timeout that is treated
// as a refusal. A dialog is never allowed to leave a caller waiting forever.

const CONFIRM_TIMEOUT_MS: number = 120000;

export class CodexConfirm {
  /**
   * Asks the user to confirm an action.
   *
   * @return `true` only when the confirm button was pressed. A cancel, a dismissal or a
   *   timeout all mean "do not proceed".
   */
  static async ask(ui: UIContext, title: string, message: string,
    cancelLabel: string, confirmLabel: string): Promise<boolean> {
    let settled: boolean = false;
    let finish: (value: boolean) => void = (): void => {};
    const decision = new Promise<boolean>((resolve) => {
      finish = (value: boolean): void => {
        if (settled) { return; }
        settled = true;
        resolve(value);
      };
      // Whatever happens to the dialog, the caller resumes.
      setTimeout((): void => { finish(false); }, CONFIRM_TIMEOUT_MS);
    });
    try {
      ui.showAlertDialog({
        title: title,
        message: message,
        autoCancel: false,
        primaryButton: { value: cancelLabel, action: (): void => { finish(false); } },
        secondaryButton: { value: confirmLabel, action: (): void => { finish(true); } },
      });
    } catch (e) {
      // A platform without modal dialogs must not block the action path.
      finish(false);
    }
    return decision;
  }
}
