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
 * observing — a chart's responsive container — would otherwise render into
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

globals.ResizeObserver ??= ResizeObserverStub;
globals.IntersectionObserver ??= IntersectionObserverStub;

/**
 * jsdom has no `matchMedia`. The suites run as a reader who asked for
 * less motion: a chart's marks then land where they belong at once
 * (`useChartMotion`) instead of growing over frames that a busy machine
 * stretches past any timeout — which is what made the value-label test fail
 * one run in several. A suite about the preference itself stubs its own.
 */
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
