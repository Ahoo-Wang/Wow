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

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import { useFilterEditor } from '../src/react/index.js';
import { FilterPanel, ViewSurface } from '../src/ui/index.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';

afterEach(cleanup);

describe('popups carry the theme out of the root', () => {
  it('puts the root class and the surface theme on a popup', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore(),
      resolveSource: () => testSource(),
    });
    const runtime = engine.create('orders', {
      title: 'T',
      scope: 'personal',
      config: recordConfig(),
    });
    function Probe() {
      return <FilterPanel filter={useFilterEditor(runtime)} />;
    }
    render(
      <ViewSurface theme="dark">
        <Probe />
      </ViewSurface>,
    );

    fireEvent.click(screen.getByRole('combobox', { name: 'Add' }));
    const popup = await screen.findByRole('listbox');
    const content = popup.closest('[data-slot="combobox-content"]');

    expect(content?.classList.contains('fve-root')).toBe(true);
    expect(content?.getAttribute('data-theme')).toBe('dark');
    // Outside the surface, as a portal is; that is the whole point.
    expect(content?.closest('[data-slot="view-surface"]')).toBeNull();
  });

  /**
   * jsdom applies no stylesheet, so `color-scheme` never resolves there. The
   * surface reads it off the root to learn the mode the cascade gave it, so
   * the stub answers for the root the way the real stylesheet would under an
   * ancestor `.dark`.
   */
  function stubDarkColorScheme() {
    const computedStyle = window.getComputedStyle.bind(window);
    vi.spyOn(window, 'getComputedStyle').mockImplementation(
      (element: Element, pseudo?: string | null) => {
        const style = computedStyle(element, pseudo);
        return element.matches('[data-slot="view-surface"]')
          ? new Proxy(style, {
              get: (target, key) =>
                key === 'colorScheme'
                  ? 'dark'
                  : Reflect.get(target, key, target),
            })
          : style;
      },
    );
  }

  async function openAddPopup(theme?: 'light' | 'dark') {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore(),
      resolveSource: () => testSource(),
    });
    const runtime = engine.create('orders', {
      title: 'T',
      scope: 'personal',
      config: recordConfig(),
    });
    function Probe() {
      return <FilterPanel filter={useFilterEditor(runtime)} />;
    }
    render(
      <div className="dark">
        <ViewSurface theme={theme}>
          <Probe />
        </ViewSurface>
      </div>,
    );

    fireEvent.click(screen.getByRole('combobox', { name: 'Add' }));
    const popup = await screen.findByRole('listbox');
    return popup.closest('[data-slot="combobox-content"]');
  }

  it('carries the mode a following surface resolved from the cascade', async () => {
    stubDarkColorScheme();

    expect((await openAddPopup())?.getAttribute('data-theme')).toBe('dark');
  });

  it('lets a pinned theme win over what the cascade resolved', async () => {
    stubDarkColorScheme();

    expect((await openAddPopup('light'))?.getAttribute('data-theme')).toBe(
      'light',
    );
  });
});
