// Minimal types for @ronanarm/walkthroughjs (ships no types).
// The UMD bundle's default export is the Walkthrough class and it also
// registers a `window.walkthrough` helper as a side effect.

declare module "@ronanarm/walkthroughjs" {
  export interface WalkthroughStep {
    element: string;
    title?: string;
    text?: string;
    position?: "top" | "bottom" | "left" | "right" | "center";
  }
  export interface WalkthroughOptions {
    progressColor?: string;
    overlayColor?: string;
    highlightPadding?: number;
    zIndex?: number;
    skipText?: string;
    [key: string]: unknown;
  }
  export default class Walkthrough {
    constructor(options?: WalkthroughOptions);
    configure(config: { steps: WalkthroughStep[]; options?: WalkthroughOptions }): this;
    start(): this;
    close(): void;
    finish(): void;
    isActive: boolean;
  }
}
