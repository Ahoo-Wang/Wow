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

import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useChartMotion } from '../src/ui/charts/motion.js';
import { usePrinting } from '../src/ui/charts/print.js';

/** A `matchMedia` whose reduced-motion answer the test can change. */
function preference(reduce: boolean) {
  const listeners = new Set<() => void>();
  const queries: string[] = [];
  const list = {
    matches: reduce,
    addEventListener: (_: string, listener: () => void) =>
      listeners.add(listener),
    removeEventListener: (_: string, listener: () => void) =>
      listeners.delete(listener),
  };
  vi.stubGlobal('matchMedia', (query: string) => {
    queries.push(query);
    return list;
  });
  return {
    queries,
    set(next: boolean) {
      list.matches = next;
      listeners.forEach(listener => listener());
    },
    listeners,
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('useChartMotion', () => {
  it('animates unless the reader asked for less motion', () => {
    preference(false);
    expect(renderHook(() => useChartMotion()).result.current).toBe(true);
    preference(true);
    expect(renderHook(() => useChartMotion()).result.current).toBe(false);
  });

  it('follows the preference when it changes while a chart is open', () => {
    const media = preference(false);
    const { result, unmount } = renderHook(() => useChartMotion());
    expect(result.current).toBe(true);
    act(() => media.set(true));
    expect(result.current).toBe(false);
    unmount();
    expect(media.listeners.size).toBe(0);
  });

  it('animates where the platform cannot say', () => {
    vi.stubGlobal('matchMedia', undefined);
    expect(renderHook(() => useChartMotion()).result.current).toBe(true);
  });

  it('draws paper without motion', () => {
    const media = preference(false);
    renderHook(() => useChartMotion());
    // One query, either half of which stops the marks moving.
    expect(media.queries[media.queries.length - 1]).toBe(
      '(prefers-reduced-motion: reduce), print',
    );
  });
});

describe('usePrinting', () => {
  it('says when printing starts and ends, and lets go of the query', () => {
    const media = preference(false);
    const { result, unmount } = renderHook(() => usePrinting());
    expect(media.queries[media.queries.length - 1]).toBe('print');
    expect(result.current).toBe(false);
    act(() => media.set(true));
    expect(result.current).toBe(true);
    act(() => media.set(false));
    expect(result.current).toBe(false);
    unmount();
    expect(media.listeners.size).toBe(0);
  });

  it('prints nothing where the platform cannot say', () => {
    vi.stubGlobal('matchMedia', undefined);
    expect(renderHook(() => usePrinting()).result.current).toBe(false);
  });
});
