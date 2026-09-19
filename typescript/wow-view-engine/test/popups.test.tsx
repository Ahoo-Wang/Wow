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

import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import { useFilterEditor } from '../src/react/index.js';
import { Dialog, DialogTitle } from '../src/ui/components/dialog.js';
import { DialogContent } from '../src/ui/popups.js';
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

/**
 * A dialog portals two elements, not one. The backdrop is the popup's
 * sibling, so it needs the root class in its own right: the built stylesheet
 * is scoped to `:where(.fve-root, .fve-root *)`, and a backdrop outside every
 * root keeps none of its utilities — the dialog would open over an undimmed
 * page.
 */
describe('a dialog themes its backdrop as well as its surface', () => {
  function openDialog(theme: 'light' | 'dark') {
    render(
      <ViewSurface theme={theme}>
        <Dialog defaultOpen>
          <DialogContent>
            <DialogTitle>Confirm</DialogTitle>
          </DialogContent>
        </Dialog>
      </ViewSurface>,
    );
  }

  it('puts the root class and the surface theme on the backdrop', async () => {
    openDialog('dark');

    const surface = await screen.findByRole('dialog');
    const backdrop = document.querySelector('[data-slot="dialog-overlay"]');

    expect(backdrop?.classList.contains('fve-root')).toBe(true);
    expect(backdrop?.getAttribute('data-theme')).toBe('dark');
    // The same mode as the surface it dims, and outside the view surface —
    // both are portalled, which is why neither inherits the root.
    expect(surface.getAttribute('data-theme')).toBe('dark');
    expect(surface.classList.contains('fve-root')).toBe(true);
    expect(backdrop?.closest('[data-slot="view-surface"]')).toBeNull();
  });

  it('keeps the vendored popup classes on the surface', async () => {
    openDialog('light');

    const surface = await screen.findByRole('dialog');

    expect(surface.classList.contains('bg-popover')).toBe(true);
    expect(surface.getAttribute('data-slot')).toBe('dialog-content');
  });

  /**
   * A popup's `className` may be a function of its own state, and both
   * wrappers a dialog goes through have to keep it one: resolving it eagerly
   * would hand Base UI a string computed from no state, and dropping to the
   * string branch would lose the root class the portalled popup needs.
   */
  it('keeps a state-dependent class a function, with the root in front', async () => {
    render(
      <ViewSurface theme="light">
        <Dialog defaultOpen>
          <DialogContent
            className={state => (state.open ? 'is-open' : 'is-closed')}
          >
            <DialogTitle>Confirm</DialogTitle>
          </DialogContent>
        </Dialog>
      </ViewSurface>,
    );

    const surface = await screen.findByRole('dialog');
    const classes = surface.className;

    // The state reached the caller's function rather than being guessed at.
    expect(surface.classList.contains('is-open')).toBe(true);
    expect(surface.classList.contains('is-closed')).toBe(false);
    // And the root class still leads the caller's own, as it does for a
    // plain string, so the theme is not lost to the function form.
    expect(surface.classList.contains('fve-root')).toBe(true);
    expect(classes.indexOf('fve-root')).toBeLessThan(
      classes.indexOf('is-open'),
    );
    // The vendored popup classes survive both wrappers too.
    expect(surface.classList.contains('bg-popover')).toBe(true);
  });

  it('still closes from the close button', async () => {
    openDialog('light');
    await screen.findByRole('dialog');

    fireEvent.click(screen.getByRole('button', { name: 'Close' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(document.querySelector('[data-slot="dialog-overlay"]')).toBeNull();
  });
});
