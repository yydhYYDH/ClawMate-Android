/** The offline reader's host-facing contract. No native capabilities are bound. */
type ManualRoute =
  | { page: 'home' }
  | { page: 'chapter'; id: string }
  | { page: 'search'; query: string }
  | { page: 'not-found' };

interface ManualState {
  version: 1;
  route: ManualRoute;
  hash: string;
  canGoBack: boolean;
}

interface ClawMateManualAPI {
  readonly version: 1;
  getState(): ManualState;
  /** Returns false for any chapter ID absent from app-manifest.json. */
  openChapter(id: string): boolean;
  openHome(): void;
  /** True means the viewer closed or an asynchronous history back was requested.
   * False means the native host should close its own help page.
   */
  back(): boolean;
}

interface Window {
  ClawMateManual: Readonly<ClawMateManualAPI>;
}

interface WindowEventMap {
  'clawmate-manual:routechange': CustomEvent<ManualState>;
}
