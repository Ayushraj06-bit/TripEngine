/**
 * jsdom has no matchMedia. This one answers from a table the test sets, so a test can be on a
 * wide screen, or ask for reduced motion.
 */
let matching = new Set<string>();

export function setMedia(...queries: string[]): void {
  matching = new Set(queries);
}

export function resetMedia(): void {
  matching = new Set();
}

// jsdom has no pointer capture either; the sheet's drag handle asks for it.
Element.prototype.setPointerCapture = () => undefined;
Element.prototype.releasePointerCapture = () => undefined;

// Only what the app uses; the type's deprecated listener methods are left out on purpose.
window.matchMedia = (query: string) =>
  ({
    matches: matching.has(query),
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  }) as unknown as MediaQueryList;
