/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { afterEach, beforeEach } from 'vitest';
import { loadCharts } from '../src/ui/charts/load.js';
import { KNOWN_MISSES, utilityCheck } from './fixtures/utilities.js';

/**
 * The browser APIs jsdom does not implement, stubbed just enough to import.
 *
 * `@dnd-kit/dom`'s `utilities` module picks its observer as it is imported —
 * `canUseDOM ? ResizeObserver : MockResizeObserver` — so a jsdom suite that
 * so much as imports `ColumnSettings` throws before a single test starts.
 * Node takes the other branch and never touches the global, which is why
 * the built `/ui` entry imports there and `verify-package` passes: this is
 * a jsdom problem exactly, and not a sign that the package needs a DOM.
 *
 * The size it reports is made up, and has to be: jsdom computes no layout, so
 * every element measures zero. A component that sizes itself from what it is
 * observing — a chart, sized by what `EChart` observes — would otherwise render into
 * nothing at all, which is worse than a fiction, because it is a fiction that
 * looks like a passing test. A suite that drives sizes by hand replaces the
 * whole class with `vi.stubGlobal`, as `dashboardUi.test.tsx` does.
 */
const VIEWPORT = { width: 1024, height: 768 };

class ResizeObserverStub {
  constructor(private readonly callback: ResizeObserverCallback) {}

  observe(target: Element): void {
    this.callback(
      [{ target, contentRect: VIEWPORT } as unknown as ResizeObserverEntry],
      this as unknown as ResizeObserver,
    );
  }

  unobserve(): void {}
  disconnect(): void {}
}

/** Nothing here scrolls anything into view, so it simply never intersects. */
class IntersectionObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
  takeRecords(): [] {
    return [];
  }
}

const globals = globalThis as Record<string, unknown>;

/**
 * Whether this file runs in jsdom. A suite that runs as a server does
 * (`@vitest-environment node`, `serverRender.test.tsx`) gets none of the
 * browser stand-ins below and no class check, since it has no document: what
 * it proves is that the engine renders without them.
 */
const inBrowser = typeof document !== 'undefined';

if (inBrowser) {
  globals.ResizeObserver ??= ResizeObserverStub;
  globals.IntersectionObserver ??= IntersectionObserverStub;
}

/**
 * jsdom has no `matchMedia`. The suites run as a reader who asked for
 * less motion: a chart's marks then land where they belong at once
 * (`useChartMotion`) instead of growing over frames that a busy machine
 * stretches past any timeout — which is what made the value-label test fail
 * one run in several. A suite about the preference itself stubs its own.
 */
if (inBrowser)
  globals.matchMedia ??= (query: string): MediaQueryList =>
    ({
      matches: query.includes('prefers-reduced-motion: reduce'),
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList;

/**
 * jsdom has a `<canvas>` but no drawing context, and says so on the console
 * each time one is asked for. The chart library asks to measure its text and
 * estimates the width when told there is no context, so it is told that
 * quietly — the estimate is what every jsdom chart is laid out with.
 */
if (inBrowser)
  HTMLCanvasElement.prototype.getContext = (() =>
    null) as typeof HTMLCanvasElement.prototype.getContext;

/**
 * The chart chunk is loaded on a chart's first use (`charts/load.ts`), a
 * tick after the render that asks for it. Loaded here, before any suite
 * renders, every chart draws inside the render a test asserts on, as it
 * does in a browser from the second chart on.
 */
await loadCharts();
// And every family's own chunk (`ChartChunk`), for the same reason.
await loadCharts('statistics');
await loadCharts('hierarchy');
await loadCharts('time');
await loadCharts('geo');

/**
 * Every class a suite puts on the page carries the engine's prefix (D66) —
 * the runtime half of `prefixedClasses.test.ts`, which reads the strings in
 * `src`; this reads what they became. The build is `prefix(fve)`, so a class
 * without it, however it was spelled, joined or looked up, generates nothing
 * and the element silently loses its styling. Each class that reaches an
 * element during a test is recorded as it lands — by a `MutationObserver`,
 * because most suites unmount in an `afterEach` of their own, which runs
 * before this one — and after the test every word without the prefix that
 * `fve:` would turn into a utility fails it.
 *
 * The hooks below are passed without asking — each is a class that is no
 * utility and is not meant to be one: the engine's own boundaries, the dark
 * switch, and the classes third-party components mark or style their own
 * elements by. Any other word is asked about: the design system loads on the
 * first one a file meets (`fixtures/utilities.ts`, ~60 ms) and each verdict
 * is cached, so a suite that renders nothing unusual never loads it.
 */
const HOOKS = new RegExp(
  [
    // The engine's surface and the popups' token scope (styles.css).
    '^fve-(root|tokens)$',
    // The dark mode switch on the root (colorMode.tsx, `@custom-variant dark`).
    '^dark$',
    // lucide-react stamps every icon with `lucide` and `lucide-<name>`.
    '^lucide(-|$)',
    // react-grid-layout and its react-resizable / react-draggable parts,
    // styled by react-grid-layout/css/styles.css (the dashboard grid).
    '^react-(grid|resizable|draggable)',
    '^(cssTransforms|placeholder-resizing)$',
    // react-day-picker's parts (the calendar), themed through its own names.
    '^rdp-',
    // Base UI's scroll lock marks the page it locks.
    '^base-ui-',
  ].join('|'),
);

const seen = new Map<string, Element>();

function record(element: Element): void {
  for (const token of element.classList)
    if (!token.startsWith('fve:') && !HOOKS.test(token) && !seen.has(token))
      seen.set(token, element);
}

function recordAll(records: readonly MutationRecord[]): void {
  for (const change of records) {
    if (change.type === 'attributes') record(change.target as Element);
    else
      for (const node of change.addedNodes)
        if (node instanceof Element) {
          record(node);
          for (const inner of node.querySelectorAll('[class]')) record(inner);
        }
  }
}

const classes = inBrowser ? new MutationObserver(recordAll) : null;

beforeEach(() => {
  seen.clear();
  if (!classes) return;
  classes.observe(document, {
    subtree: true,
    childList: true,
    attributes: true,
    attributeFilter: ['class'],
  });
});

afterEach(async () => {
  if (!classes) return;
  recordAll(classes.takeRecords());
  classes.disconnect();
  for (const { token } of KNOWN_MISSES) seen.delete(token);
  if (seen.size === 0) return;
  const isUtility = await utilityCheck();
  const bare = [...seen]
    .filter(([token]) => isUtility(`fve:${token}`))
    .map(
      ([token, element]) =>
        `${token} on <${element.localName} class="${element.getAttribute('class')}">`,
    );
  seen.clear();
  if (bare.length > 0)
    throw new Error(
      `Classes without the prefix fve: generate no CSS (D66): ${bare.join('; ')}`,
    );
});
