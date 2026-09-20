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
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import { useFilterEditor } from '../src/react/index.js';
import {
  Combobox as VendoredCombobox,
  ComboboxContent as VendoredComboboxContent,
} from '../src/ui/components/combobox.js';
import {
  Dialog,
  DialogTitle,
  DialogContent as VendoredDialogContent,
} from '../src/ui/components/dialog.js';
import {
  DropdownMenu,
  DropdownMenuItem,
  DropdownMenuContent as VendoredDropdownMenuContent,
} from '../src/ui/components/dropdown-menu.js';
import {
  Popover,
  PopoverContent as VendoredPopoverContent,
} from '../src/ui/components/popover.js';
import {
  Select,
  SelectItem,
  SelectContent as VendoredSelectContent,
} from '../src/ui/components/select.js';
import {
  Tooltip,
  TooltipContent as VendoredTooltipContent,
} from '../src/ui/components/tooltip.js';
import { EditorBandToggle } from '../src/ui/EditorBand.js';
import {
  ComboboxContent,
  DialogContent,
  DropdownMenuContent,
  PopoverContent,
  SelectContent,
  TooltipContent,
} from '../src/ui/popups.js';
import { FilterPanel, ViewSurface } from '../src/ui/index.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';

afterEach(cleanup);

/**
 * The field picker's popup, once it is up. It is named by its title rather
 * than found by role, because the picker is a popover and the page may hold
 * more than one portalled thing at a time.
 */
async function findFieldPicker(): Promise<Element | null> {
  const title = await screen.findByText('Choose filter fields');
  return title.closest('[data-slot="popover-content"]');
}

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

    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    const content = await findFieldPicker();

    expect(content?.classList.contains('fve-root')).toBe(true);
    expect(content?.getAttribute('data-theme')).toBe('dark');
    // Outside the surface, as a portal is; that is the whole point.
    expect(content?.closest('[data-slot="view-surface"]')).toBeNull();
  });

  /**
   * A menu is a different wrapper from a popover, and the editor's modes are
   * the one menu that stays on screen while the editor itself is folded
   * away — so nothing else of the editor is around to carry the theme in.
   */
  it('puts the root class and the surface theme on a menu', async () => {
    const user = userEvent.setup();
    render(
      <ViewSurface theme="dark">
        <EditorBandToggle
          open={false}
          onOpenChange={() => undefined}
          controls="band"
          label="Filter"
          modes={<DropdownMenuItem>Advanced</DropdownMenuItem>}
          pending={0}
        />
      </ViewSurface>,
    );

    await user.click(screen.getByRole('button', { name: 'Editor options' }));
    const item = await screen.findByText('Advanced');
    const content = item.closest('[data-slot="dropdown-menu-content"]');

    expect(content?.classList.contains('fve-root')).toBe(true);
    expect(content?.getAttribute('data-theme')).toBe('dark');
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

    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    return findFieldPicker();
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

/**
 * Every popup kind, as this file composes it and as the registry ships it.
 *
 * `open` skips the trigger: what is under test is the markup a popup mounts,
 * not how it is opened, and a dialog, a menu and a tooltip are each opened by
 * something different.
 */
const KINDS = [
  {
    name: 'combobox',
    slot: 'combobox-content',
    vendored: (
      <VendoredCombobox open>
        <VendoredComboboxContent>list</VendoredComboboxContent>
      </VendoredCombobox>
    ),
    composed: (
      <VendoredCombobox open>
        <ComboboxContent>list</ComboboxContent>
      </VendoredCombobox>
    ),
  },
  {
    name: 'dialog',
    slot: 'dialog-content',
    vendored: (
      <Dialog open>
        <VendoredDialogContent>
          <DialogTitle>Confirm</DialogTitle>
        </VendoredDialogContent>
      </Dialog>
    ),
    composed: (
      <Dialog open>
        <DialogContent>
          <DialogTitle>Confirm</DialogTitle>
        </DialogContent>
      </Dialog>
    ),
  },
  {
    name: 'dropdown menu',
    slot: 'dropdown-menu-content',
    vendored: (
      <DropdownMenu open>
        <VendoredDropdownMenuContent>
          <DropdownMenuItem>Advanced</DropdownMenuItem>
        </VendoredDropdownMenuContent>
      </DropdownMenu>
    ),
    composed: (
      <DropdownMenu open>
        <DropdownMenuContent>
          <DropdownMenuItem>Advanced</DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    ),
  },
  {
    name: 'popover',
    slot: 'popover-content',
    vendored: (
      <Popover open>
        <VendoredPopoverContent>Fields</VendoredPopoverContent>
      </Popover>
    ),
    composed: (
      <Popover open>
        <PopoverContent>Fields</PopoverContent>
      </Popover>
    ),
  },
  {
    name: 'select',
    slot: 'select-content',
    vendored: (
      <Select open>
        <VendoredSelectContent>
          <SelectItem value="20">20</SelectItem>
        </VendoredSelectContent>
      </Select>
    ),
    composed: (
      <Select open>
        <SelectContent>
          <SelectItem value="20">20</SelectItem>
        </SelectContent>
      </Select>
    ),
  },
  {
    name: 'tooltip',
    slot: 'tooltip-content',
    vendored: (
      <Tooltip open>
        <VendoredTooltipContent>Rename</VendoredTooltipContent>
      </Tooltip>
    ),
    composed: (
      <Tooltip open>
        <TooltipContent>Rename</TooltipContent>
      </Tooltip>
    ),
  },
] as const;

/** The popup with that slot, wherever in the document its portal put it. */
function popupOf(slot: string): HTMLElement {
  const found = document.body.querySelector<HTMLElement>(
    `[data-slot="${slot}"]`,
  );
  if (!found) throw new Error(`no ${slot} on the page`);
  return found;
}

/**
 * The stacking level a popup paints on, and the element that carries it.
 *
 * A popup is portalled to the body inside a *positioner* the layout engine
 * gives a `transform`, which makes the positioner a stacking context — so the
 * level has to sit on the positioner, and a `z-index` on the content inside
 * it cannot escape. The positioner is no `.fve-root`, and the build pins
 * every rule of the stylesheet to `:where(.fve-root, .fve-root *)`, so the
 * registry's `isolate z-50` on it matches nothing: left alone it computes to
 * `z-index: auto` and the popup paints at level 0, in front of the page only
 * because its portal is last in the body. That is what buries a popup under
 * anything a host raises. `FillTheScreenOverRaisedHostChrome` in
 * `stories/view-engine/RecordWorkbench.test.stories.tsx` is the same claim
 * measured in a browser, where the layers actually exist.
 */
describe('every popup opens on the popup layer', () => {
  const LAYER = 'var(--fve-popup-z-index, 50)';

  it.each(KINDS)('carries the layer on a $name', async ({ slot, composed }) => {
    render(<ViewSurface theme="light">{composed}</ViewSurface>);

    const popup = await waitFor(() => popupOf(slot));
    // A dialog has no positioner: it is fixed to the viewport itself, so the
    // level belongs on the popup, and on the backdrop beside it.
    const layered =
      slot === 'dialog-content' ? popup : (popup.parentElement as HTMLElement);

    expect(layered.style.zIndex).toBe(LAYER);
  });

  /**
   * A dialog is the one popup a caller's own `style` reaches, and the two
   * elements it portals are ranked against each other: a popup that lost the
   * layer to an unrelated property would end up behind the backdrop that
   * still has it — dimmed by its own dimming, and not clickable.
   */
  it.each([
    ['an object', { maxWidth: 600 }],
    ['a function of the popup state', () => ({ maxWidth: 600 })],
  ] as const)(
    "keeps the layer under a caller's style, given %s",
    async (_form, style) => {
      render(
        <ViewSurface theme="light">
          <Dialog open>
            <DialogContent style={style}>
              <DialogTitle>Confirm</DialogTitle>
            </DialogContent>
          </Dialog>
        </ViewSurface>,
      );

      const surface = await screen.findByRole('dialog');

      expect(surface.style.zIndex).toBe(LAYER);
      // And the caller's own property is still there, which is the half a
      // plain override would have kept.
      expect(surface.style.maxWidth).toBe('600px');
    },
  );

  it('carries the layer on a dialog backdrop too', async () => {
    render(
      <ViewSurface theme="light">
        <Dialog open>
          <DialogContent>
            <DialogTitle>Confirm</DialogTitle>
          </DialogContent>
        </Dialog>
      </ViewSurface>,
    );

    await screen.findByRole('dialog');
    const backdrop = document.querySelector<HTMLElement>(
      '[data-slot="dialog-overlay"]',
    );

    expect(backdrop?.style.zIndex).toBe(LAYER);
  });
});

/**
 * The cost of composing instead of wrapping: the markup here is a copy, and a
 * copy drifts. `components/*.tsx` are vendored from the shadcn registry and
 * updated with `shadcn add --diff`, so this renders both and compares what
 * the browser is handed — every class, the slot, and the parts inside.
 * Everything this file adds deliberately (the root class, the surface mode,
 * the stacking level) is named below; anything else that differs is drift.
 */
describe('the composed popups keep step with the registry', () => {
  /** Classes, without the one this file puts in front of the copy. */
  const classesOf = (element: Element) =>
    [...element.classList].filter(name => name !== 'fve-root').sort();

  /** The parts inside, with Base UI's per-render ids made comparable. */
  const partsOf = (element: Element) =>
    element.innerHTML.replace(/base-ui-[\w-]+/g, 'base-ui-id');

  it.each(KINDS)(
    'renders a $name the way the registry does',
    async ({ slot, vendored, composed }) => {
      render(vendored);
      const registry = await waitFor(() => popupOf(slot));
      const expected = {
        classes: classesOf(registry),
        html: partsOf(registry),
        positioner: registry.parentElement?.className,
      };
      cleanup();

      render(<ViewSurface theme="light">{composed}</ViewSurface>);
      const ours = await waitFor(() => popupOf(slot));

      expect({
        classes: classesOf(ours),
        html: partsOf(ours),
        positioner: ours.parentElement?.className,
      }).toEqual(expected);
      // And the additions are all there, on the elements that need them.
      expect(ours.classList.contains('fve-root')).toBe(true);
      expect(ours.getAttribute('data-theme')).toBe('light');
    },
  );
});
