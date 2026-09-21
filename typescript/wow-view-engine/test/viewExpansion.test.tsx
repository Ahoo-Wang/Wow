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

import { StrictMode, useRef, useState } from 'react';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import type { ViewInstance, ViewSource } from '../src/index.js';
import { useWorkbench } from '../src/react/index.js';
import {
  AnalysisWorkbench,
  defaultMessages,
  EmbeddedView,
  RecordWorkbench,
  useViewExpansion,
  WorkbenchShell,
  zhCN,
} from '../src/ui/index.js';
import {
  analysisConfig,
  deferred,
  ordersDefinition,
  recordConfig,
  testSource,
  ROWS,
} from './fixtures.js';

/**
 * What actually scrolls a document, which is what the lock borrows.
 *
 * `<html>` in standards mode, `<body>` in quirks: body's own overflow reaches
 * the viewport only while html's is `visible`, so a host that owns page
 * scrolling on `html` would go on scrolling behind a surface that locked the
 * body alone.
 */
const scroller = (doc: Document = document) =>
  (doc.scrollingElement as HTMLElement | null) ?? doc.documentElement;

/**
 * The page's inline overflow, one axis at a time and priority included.
 *
 * Both parts matter and the shorthand can express neither on its own: a host
 * that set only `overflow-y`, or gave the axes different values or different
 * priorities, is a page the shorthand cannot read back or hand back.
 */
function held(doc: Document = document): string[] {
  const style = scroller(doc).style;
  return ['overflow-x', 'overflow-y'].map(name => {
    const priority = style.getPropertyPriority(name);
    return style.getPropertyValue(name) + (priority ? ` !${priority}` : '');
  });
}

/** What a host was holding before a surface borrowed it. */
function give(value: string, priority = '', doc: Document = document): void {
  const style = scroller(doc).style;
  for (const name of ['overflow-x', 'overflow-y'])
    style.setProperty(name, value, priority);
}

/** Locked, on both axes, in the way a host stylesheet cannot outrank. */
const LOCKED = ['hidden !important', 'hidden !important'];
/** And a page holding nothing of its own. */
const FREE = ['', ''];

afterEach(() => {
  cleanup();
  // The lock is the one thing this suite can leave behind for the next one,
  // which is exactly the failure it exists to catch — so it is cleared here
  // rather than trusted, and asserted on inside every test that takes it.
  for (const name of ['overflow', 'overflow-x', 'overflow-y']) {
    scroller().style.removeProperty(name);
    document.body.style.removeProperty(name);
  }
});

const FILL = defaultMessages['label.workbench.expand-view'];
const LEAVE = defaultMessages['label.workbench.collapse-view'];
const COLLAPSE_SIDEBAR = defaultMessages['label.workbench.collapse-sidebar'];
const EXPAND_SIDEBAR = defaultMessages['label.workbench.expand-sidebar'];
const SWITCH = defaultMessages['label.workbench.switch-view'];
const FILTER = new RegExp(defaultMessages['label.filter.panel']);

const mine: ViewInstance = {
  id: 'mine',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

/** A second record view, so there is somewhere to switch to. */
const ours: ViewInstance = {
  id: 'ours',
  definitionId: 'orders',
  title: 'Ours',
  scope: 'shared',
  revision: '1',
  config: recordConfig(),
};

const byWarehouse: ViewInstance = {
  id: 'by-warehouse',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'shared',
  revision: '1',
  config: analysisConfig(),
};

function engineWith(source: ViewSource = testSource()): ViewEngine {
  return new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances: [mine, ours, byWarehouse] }),
    resolveSource: () => source,
  });
}

/** The record workbench, opened on a saved view with rows on screen. */
async function open(engine = engineWith()) {
  const user = userEvent.setup();
  render(
    <RecordWorkbench engine={engine} definitionId="orders" instanceId="mine" />,
  );
  await screen.findByRole('table');
  return user;
}

/** The workbench, opened and already filling the screen. */
async function expanded(engine = engineWith()) {
  const user = await open(engine);
  await user.click(screen.getByRole('button', { name: FILL }));
  return user;
}

const surface = () =>
  document.querySelector<HTMLElement>('[data-slot="view-surface"]')!;
const isExpanded = () =>
  surface().getAttribute('data-view-expanded') === 'true';

describe('the control that fills the screen', () => {
  it('sits in the title bar with the other controls for how a view is read', async () => {
    await open();

    const controls = document.querySelector<HTMLElement>(
      '[data-slot="view-controls"]',
    )!;
    const toggle = screen.getByRole('button', { name: FILL });
    expect(controls.contains(toggle)).toBe(true);
    // The editor's fold governs what the view asks; this governs the room
    // the answer gets. They read outwards, in that order.
    const order = [
      ...controls.querySelectorAll(
        '[data-slot="editor-toggle"], [data-slot="view-expand"]',
      ),
    ].map(node => node.getAttribute('data-slot'));
    expect(order).toEqual(['editor-toggle', 'view-expand']);
  });

  it('says which state it is in, and never says it twice', async () => {
    const user = await open();
    const toggle = screen.getByRole('button', { name: FILL });

    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    // Nothing to press Escape for yet, so nothing is announced about it.
    expect(toggle.hasAttribute('aria-keyshortcuts')).toBe(false);

    await user.click(toggle);
    const back = screen.getByRole('button', { name: LEAVE });
    expect(back).toBe(toggle);
    expect(back.getAttribute('aria-expanded')).toBe('true');
    expect(back.getAttribute('aria-keyshortcuts')).toBe('Escape');
    // One control, two names: a second button for the way back would be a
    // second thing to find for one choice.
    expect(screen.queryByRole('button', { name: FILL })).toBeNull();
  });

  it('marks the surface in place rather than moving it anywhere', async () => {
    const user = await open();
    const before = screen.getByRole('table');
    const parent = surface().parentElement;

    await user.click(screen.getByRole('button', { name: FILL }));

    expect(isExpanded()).toBe(true);
    // The same table node, under the same parent: a portal would have
    // remounted everything under it and taken the draft with it.
    expect(screen.getByRole('table')).toBe(before);
    expect(surface().parentElement).toBe(parent);
  });

  it('speaks the host language, like every other word on the bar', async () => {
    const engine = engineWith();
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="mine"
        messages={zhCN}
      />,
    );
    await screen.findByRole('table');

    expect(
      screen.getByRole('button', { name: zhCN['label.workbench.expand-view'] }),
    ).toBeDefined();
  });

  it('is not offered where the host says there is no room for it', async () => {
    render(<Shell engine={engineWith()} expandable={false} />);
    await screen.findByText('rows');

    expect(screen.queryByRole('button', { name: FILL })).toBeNull();
    // And the group it would have joined is still the group it was: the
    // editor's fold does not move because its neighbour is absent.
    expect(
      document.querySelector('[data-slot="view-controls"]')!.textContent,
    ).toMatch(FILTER);
  });

  it.each([
    ['record', RecordWorkbench, 'mine'],
    ['analysis', AnalysisWorkbench, 'by-warehouse'],
  ] as const)(
    'can be turned off through the %s workbench itself',
    async (_kind, Workbench, instanceId) => {
      // A contract the default entries do not forward is a contract a host
      // using them cannot reach: it would have to give up the workbench and
      // reassemble `WorkbenchShell` by hand to lose one button.
      const { rerender } = render(
        <Workbench
          engine={engineWith()}
          definitionId="orders"
          instanceId={instanceId}
        />,
      );
      expect(await screen.findByRole('button', { name: FILL })).toBeDefined();

      rerender(
        <Workbench
          engine={engineWith()}
          definitionId="orders"
          instanceId={instanceId}
          expandable={false}
        />,
      );
      await waitFor(() =>
        expect(screen.queryByRole('button', { name: FILL })).toBeNull(),
      );
    },
  );
});

describe('the background while a view fills the screen', () => {
  it('stops scrolling, and is handed back exactly as it was', async () => {
    give('auto', 'important');
    const user = await open();

    await user.click(screen.getByRole('button', { name: FILL }));
    // Important, because the page may be holding its own overflow that way
    // from a stylesheet — `html { overflow: auto !important }`, or Tailwind's
    // forced utility — and a stylesheet's `!important` outranks a plain
    // inline declaration. The background would go on scrolling under a
    // surface that covers it.
    expect(held()).toEqual(LOCKED);

    await user.click(screen.getByRole('button', { name: LEAVE }));
    // The priority too: a host that wrote `!important` meant it, and a plain
    // `auto` handed back is a different page from the one we borrowed.
    expect(held()).toEqual(['auto !important', 'auto !important']);
  });

  it('leaves nothing behind when the view is unmounted while expanded', async () => {
    give('scroll');
    const user = userEvent.setup();
    const view = render(
      <RecordWorkbench
        engine={engineWith()}
        definitionId="orders"
        instanceId="mine"
      />,
    );
    await screen.findByRole('table');
    await user.click(screen.getByRole('button', { name: FILL }));
    expect(held()).toEqual(LOCKED);

    // Navigating away is the one close that never runs a click handler, so
    // a lock released only on the button would outlive the page that took it.
    view.unmount();
    expect(held()).toEqual(['scroll', 'scroll']);
  });

  it('survives the setup React runs twice', async () => {
    give('auto');
    const user = userEvent.setup();
    const view = render(
      <StrictMode>
        <RecordWorkbench
          engine={engineWith()}
          definitionId="orders"
          instanceId="mine"
        />
      </StrictMode>,
    );
    await screen.findByRole('table');

    await user.click(screen.getByRole('button', { name: FILL }));
    // Setup, cleanup, setup: a count that did not come back to one would
    // either unlock under an open surface or never unlock at all.
    expect(held()).toEqual(LOCKED);
    view.unmount();
    expect(held()).toEqual(['auto', 'auto']);
  });

  it('reads the page as it is each time rather than as it once was', async () => {
    give('auto');
    const first = await expanded();
    await first.click(screen.getByRole('button', { name: LEAVE }));

    give('clip', 'important');
    await first.click(screen.getByRole('button', { name: FILL }));
    await first.click(screen.getByRole('button', { name: LEAVE }));

    // The remembered value is dropped with the last lock, so the second
    // expansion restores what the host had by then, not what it had first.
    expect(held()).toEqual(['clip !important', 'clip !important']);
  });

  it('borrows the element that actually scrolls the document', async () => {
    // Body's overflow reaches the viewport only while html's is `visible`, so
    // a host that owns page scrolling on `html` — an ordinary thing to do —
    // goes on scrolling by wheel, touch and key behind a surface that locked
    // the body alone.
    document.documentElement.style.setProperty('overflow-y', 'auto');
    const user = await open();

    await user.click(screen.getByRole('button', { name: FILL }));
    expect(scroller()).toBe(document.documentElement);
    expect(held()).toEqual(LOCKED);
  });

  it('hands back each axis on its own, value and priority', async () => {
    // A host with one axis set, the other free, and the two at different
    // priorities: the `overflow` shorthand can read back none of that, and
    // writing it would replace both longhands — so restoring from it would
    // lose whatever the host had for good.
    const style = scroller().style;
    style.setProperty('overflow-x', 'clip');
    style.setProperty('overflow-y', 'scroll', 'important');
    const user = await open();

    await user.click(screen.getByRole('button', { name: FILL }));
    expect(held()).toEqual(LOCKED);

    await user.click(screen.getByRole('button', { name: LEAVE }));
    expect(held()).toEqual(['clip', 'scroll !important']);
  });

  it('leaves an axis the host never set unset', async () => {
    scroller().style.setProperty('overflow-y', 'auto');
    const user = await open();

    await user.click(screen.getByRole('button', { name: FILL }));
    await user.click(screen.getByRole('button', { name: LEAVE }));

    // Not `visible`, not `hidden`: an inline declaration the host never wrote
    // is one the cascade never had to outrank, and inventing one changes
    // which rule wins on that axis.
    expect(held()).toEqual(['', 'auto']);
  });
});

/**
 * A dashboard panel's workbench and an embedded view can sit on one page, and
 * both of them can fill the screen. Two owners of one document's scrolling is
 * where this kind of feature goes wrong: the first one closing puts the page
 * back under the second, or the second one closing never puts it back at all.
 */
describe('two surfaces on one document', () => {
  /** One workbench in a container of its own, expanded unless asked not to. */
  async function page(expand = true) {
    const user = userEvent.setup();
    const container = document.body.appendChild(document.createElement('div'));
    const view = render(
      <RecordWorkbench
        engine={engineWith()}
        definitionId="orders"
        instanceId="mine"
      />,
      { container, baseElement: container },
    );
    await view.findByRole('table');
    const fill = async () =>
      user.click(view.getByRole('button', { name: FILL }));
    if (expand) await fill();
    const open = () =>
      view.container.querySelector('[data-view-expanded]') !== null;
    return { user, view, fill, open };
  }

  it.each([{ first: 0 }, { first: 1 }])(
    'keeps the page still until the last of them leaves (first out: $first)',
    async ({ first }) => {
      give('auto', 'important');
      const pages = [await page(), await page()];
      expect(held()).toEqual(LOCKED);

      const closing = pages[first];
      await closing.user.click(
        closing.view.getByRole('button', { name: LEAVE }),
      );
      expect(
        closing.view.container.querySelector('[data-view-expanded]'),
      ).toBeNull();
      // The other one is still on screen and still wants the page still.
      expect(
        pages[1 - first].view.getByRole('button', { name: LEAVE }),
      ).toBeDefined();
      expect(held()).toEqual(LOCKED);

      const last = pages[1 - first];
      await last.user.click(last.view.getByRole('button', { name: LEAVE }));
      expect(held()).toEqual(['auto !important', 'auto !important']);
    },
  );

  it('counts each document on its own', async () => {
    const frame = document.body.appendChild(document.createElement('iframe'));
    const frameDocument = frame.contentDocument!;
    give('auto');
    give('scroll', 'important', frameDocument);
    try {
      const here = await page();
      const inFrame = render(
        <RecordWorkbench
          engine={engineWith()}
          definitionId="orders"
          instanceId="mine"
        />,
        {
          container: frameDocument.body.appendChild(
            frameDocument.createElement('div'),
          ),
          baseElement: frameDocument.body,
        },
      );
      await inFrame.findByRole('table');
      fireEvent.click(inFrame.getByRole('button', { name: FILL }));

      expect(held()).toEqual(LOCKED);
      expect(held(frameDocument)).toEqual(LOCKED);

      here.view.unmount();
      // One page going back does not reach into the other's.
      expect(held()).toEqual(['auto', 'auto']);
      expect(held(frameDocument)).toEqual(LOCKED);

      inFrame.unmount();
      expect(held(frameDocument)).toEqual([
        'scroll !important',
        'scroll !important',
      ]);
    } finally {
      frame.remove();
    }
  });

  it('closes the one in front and leaves the one behind it alone', async () => {
    const pages = [await page(), await page()];

    fireEvent.keyDown(document, { key: 'Escape' });

    // Every expansion listens on the same document, so without a rule for
    // which one answers, one Escape would collapse all of them at once — and
    // leave two of them fighting over where focus lands.
    expect(pages[1].open()).toBe(false);
    expect(pages[0].open()).toBe(true);
    // Which is also why the page is still still: something is still on it.
    expect(held()).toEqual(LOCKED);

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(pages[0].open()).toBe(false);
    expect(held()).toEqual(FREE);
  });

  it('answers from the one actually in front, not the one opened last', async () => {
    // Both surfaces paint in the same layer — see the `z-index` note in
    // `styles.css` — so what covers what is document order and nothing else.
    // Expand the later one first and the earlier one second: remembering the
    // order they were opened in would collapse the surface *underneath*
    // while the one the user is looking at stays exactly where it was.
    const [first, second] = [await page(false), await page(false)];
    await second.fill();
    await first.fill();

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(second.open()).toBe(false);
    expect(first.open()).toBe(true);
  });

  it('leaves focus on the surface still in front of the one it closed', async () => {
    const [first, second] = [await page(), await page()];
    // The control of the surface that just collapsed is back in page layout
    // and behind the one still expanded — a Tab from there would walk content
    // nobody can see. So focus goes to what is still in front.
    fireEvent.keyDown(document, { key: 'Escape' });

    expect(second.open()).toBe(false);
    expect(document.activeElement).toBe(
      first.view.getByRole('button', { name: LEAVE }),
    );

    // And with nothing left in front, back to this surface's own control.
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(document.activeElement).toBe(
      first.view.getByRole('button', { name: FILL }),
    );
  });
});

describe('the keyboard', () => {
  it('reaches the toggle and works it without a pointer', async () => {
    const user = await open();

    screen.getByRole('button', { name: FILL }).focus();
    await user.keyboard('{Enter}');
    expect(isExpanded()).toBe(true);

    await user.keyboard('{Enter}');
    expect(isExpanded()).toBe(false);
  });

  it('puts the view back on Escape, with focus where it started', async () => {
    const user = await expanded();
    // Focus somewhere in the result, the way a reader gets there.
    screen.getAllByRole('columnheader')[1].querySelector('button')?.focus();

    await user.keyboard('{Escape}');

    expect(isExpanded()).toBe(false);
    // Back to the control that opened it: a key pressed inside a surface
    // covering the screen would otherwise drop focus on the body.
    expect(document.activeElement).toBe(
      screen.getByRole('button', { name: FILL }),
    );
  });

  it('leaves the key to a menu that is in front of it', async () => {
    const user = await expanded();
    // Filling the screen already folded the list away, so the switcher is
    // the menu that is in front.
    await user.click(screen.getByRole('button', { name: SWITCH }));
    await screen.findByRole('menu');

    await user.keyboard('{Escape}');

    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull());
    // The menu took it, and only the menu: "close this" means the thing in
    // front, never the whole view behind it.
    expect(isExpanded()).toBe(true);
    expect(held()).toEqual(LOCKED);
  });

  it("leaves the key to a host's native dialog, which wears no role", async () => {
    await expanded();
    // A native `<dialog>` carries its role implicitly, and an implicit ARIA
    // role is not an attribute — so `[role="dialog"]` never matches one. A
    // document listener runs before the browser's own default close, so
    // without naming the element the first Escape would shut the host's
    // dialog *and* put the whole view back in the page.
    const dialog = document.body.appendChild(document.createElement('dialog'));
    dialog.setAttribute('open', '');
    const inside = dialog.appendChild(document.createElement('button'));

    fireEvent.keyDown(inside, { key: 'Escape', bubbles: true });

    expect(isExpanded()).toBe(true);
    dialog.remove();
  });

  it('leaves the key to the input method while a word is being composed', async () => {
    await expanded();
    // Escape belongs to the IME while a candidate list is open — it cancels
    // the composition — and the native event still reads `Escape`, on an
    // ordinary input, un-prevented. Collapsing there would take the
    // half-typed word and the focus with it.
    const field = surface().appendChild(document.createElement('input'));

    fireEvent.keyDown(field, { key: 'Escape', isComposing: true });
    expect(isExpanded()).toBe(true);

    // The same thing said the other way, on browsers that leave
    // `isComposing` unset.
    fireEvent.keyDown(field, { key: 'Escape', keyCode: 229 });
    expect(isExpanded()).toBe(true);

    // And once the composition is over, the key is the view's again.
    fireEvent.keyDown(field, { key: 'Escape' });
    expect(isExpanded()).toBe(false);
  });

  it('leaves the key to a dialog that is in front of it', async () => {
    const user = await expanded();
    await user.click(screen.getByRole('button', { name: FILTER }));
    await user.click(
      screen.getByRole('combobox', {
        name: defaultMessages['label.filter.add'],
      }),
    );
    const picker = await screen.findByRole('dialog');
    await user.click(within(picker).getByRole('option', { name: 'Warehouse' }));

    await user.keyboard('{Escape}');

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(isExpanded()).toBe(true);
    // And the draft the picker was writing is still there, because nothing
    // was ever re-parented.
    expect(screen.getByRole('button', { name: FILTER }).textContent).toContain(
      '1',
    );
  });

  it('ignores a key something else already answered', async () => {
    await expanded();

    const handled = new KeyboardEvent('keydown', {
      key: 'Escape',
      bubbles: true,
      cancelable: true,
    });
    handled.preventDefault();
    document.dispatchEvent(handled);

    expect(isExpanded()).toBe(true);
  });

  it('leaves the key to a popup in another realm', async () => {
    const frame = document.body.appendChild(document.createElement('iframe'));
    const frameDocument = frame.contentDocument!;
    try {
      const inFrame = render(
        <RecordWorkbench
          engine={engineWith()}
          definitionId="orders"
          instanceId="mine"
        />,
        {
          container: frameDocument.body.appendChild(
            frameDocument.createElement('div'),
          ),
          baseElement: frameDocument.body,
        },
      );
      await inFrame.findByRole('table');
      fireEvent.click(inFrame.getByRole('button', { name: FILL }));

      // A popup of the frame's own, with focus inside it. `instanceof
      // Element` against *this* window's realm answers false for a node from
      // the frame, so the whole dialog/menu/listbox check would be skipped —
      // and the view would fold up under a picker the user only meant to
      // close. The constructor comes from the event's own document instead.
      const dialog = frameDocument.body.appendChild(
        frameDocument.createElement('div'),
      );
      dialog.setAttribute('role', 'dialog');
      const inside = dialog.appendChild(frameDocument.createElement('button'));
      fireEvent.keyDown(inside, { key: 'Escape' });

      expect(
        inFrame.container.querySelector('[data-view-expanded]'),
      ).not.toBeNull();
      expect(held(frameDocument)).toEqual(LOCKED);

      // And a key with nothing in front of it still puts the view back.
      fireEvent.keyDown(frameDocument.body, { key: 'Escape' });
      expect(
        inFrame.container.querySelector('[data-view-expanded]'),
      ).toBeNull();
    } finally {
      frame.remove();
    }
  });

  it('is not moved by any other key', async () => {
    await expanded();

    fireEvent.keyDown(document, { key: 'Enter' });
    fireEvent.keyDown(document, { key: 'Backspace' });
    expect(isExpanded()).toBe(true);
  });
});

describe('the states a view can be expanded in', () => {
  it('expands while the first rows are still on their way', async () => {
    const pending = deferred<{ total: number; list: typeof ROWS }>();
    const user = userEvent.setup();
    render(
      <RecordWorkbench
        engine={engineWith(testSource({ paged: () => pending.promise }))}
        definitionId="orders"
        instanceId="mine"
      />,
    );
    const toggle = await screen.findByRole('button', { name: FILL });

    await user.click(toggle);
    expect(isExpanded()).toBe(true);
    expect(held()).toEqual(LOCKED);

    pending.resolve({ total: 2, list: [...ROWS] });
    await screen.findByRole('table');
    // The result arriving underneath changes nothing about the surface.
    expect(isExpanded()).toBe(true);
  });

  it('expands over a query that failed, strip and all', async () => {
    const user = userEvent.setup();
    render(
      <RecordWorkbench
        engine={engineWith(
          testSource({ paged: () => Promise.reject(new Error('offline')) }),
        )}
        definitionId="orders"
        instanceId="mine"
      />,
    );
    await screen.findByRole('alert');

    await user.click(screen.getByRole('button', { name: FILL }));
    expect(isExpanded()).toBe(true);
    expect(screen.getByRole('alert')).toBeDefined();
  });

  it('expands an analysis that has no result to show yet', async () => {
    const pending = deferred<Record<string, unknown>[]>();
    const user = userEvent.setup();
    render(
      <AnalysisWorkbench
        engine={engineWith(testSource({ aggregate: () => pending.promise }))}
        definitionId="orders"
        instanceId="by-warehouse"
      />,
    );
    const toggle = await screen.findByRole('button', { name: FILL });

    await user.click(toggle);
    // Nothing to show yet — no table, no chart — and the surface fills the
    // screen over what there is.
    expect(screen.queryByRole('table')).toBeNull();
    expect(isExpanded()).toBe(true);
    expect(held()).toEqual(LOCKED);
    pending.resolve([{ warehouse: 'CN', orders: 2, amount_sum: 30 }]);
  });

  it('expands with the view list folded away', async () => {
    const user = await open();
    await user.click(screen.getByRole('button', { name: COLLAPSE_SIDEBAR }));

    await user.click(screen.getByRole('button', { name: FILL }));

    expect(isExpanded()).toBe(true);
    // Both are this screen at this moment, and neither undoes the other.
    expect(document.querySelector('[data-slot="view-sidebar"]')).toBeNull();
    expect(screen.getByRole('button', { name: SWITCH })).toBeDefined();
  });

  it('offers nothing over a view that would not open', async () => {
    render(
      <RecordWorkbench
        engine={engineWith()}
        definitionId="orders"
        instanceId="no-such-view"
      />,
    );
    await screen.findByRole('alert');

    // The toggle lives in the title bar, and there is no title bar over a
    // view nobody could draw.
    expect(screen.queryByRole('button', { name: FILL })).toBeNull();
    expect(held()).toEqual(FREE);
  });

  it('gives the page back the moment the control is taken away', async () => {
    give('auto');
    const user = userEvent.setup();
    const engine = engineWith();
    const view = render(<Shell engine={engine} />);
    await screen.findByText('rows');
    await user.click(screen.getByRole('button', { name: FILL }));
    expect(held()).toEqual(LOCKED);

    view.rerender(<Shell engine={engine} expandable={false} />);

    // An expansion with no way out would be a trap, so it ends with the
    // control: withdrawing the button releases the surface rather than
    // leaving it pinned over a page whose scrolling is still locked.
    expect(isExpanded()).toBe(false);
    expect(screen.queryByRole('button', { name: LEAVE })).toBeNull();
    expect(held()).toEqual(['auto', 'auto']);

    view.rerender(<Shell engine={engine} />);
    // And it is *ended*, not parked: a surface that came back filling the
    // screen the moment its control returned would be the view taking over
    // the page with nobody having asked.
    expect(isExpanded()).toBe(false);
    expect(
      screen.getByRole('button', { name: FILL }).getAttribute('aria-expanded'),
    ).toBe('false');
    expect(held()).toEqual(['auto', 'auto']);
  });

  it('stashes nothing when a control asks while there is nothing to expand', async () => {
    function Switchable({ enabled }: { enabled: boolean }) {
      const root = useRef<HTMLDivElement>(null);
      const toggle = useRef<HTMLButtonElement>(null);
      const expansion = useViewExpansion(root, toggle, enabled);
      const [engine] = useState(engineWith);
      return (
        <>
          {/* A host's own control, which outlives `enabled` going false —
              a shortcut still bound, a button not yet unmounted. */}
          <button ref={toggle} type="button" onClick={expansion.toggle}>
            host control
          </button>
          <span data-testid="reported">{String(expansion.expanded)}</span>
          <EmbeddedView ref={root} engine={engine} instanceId="mine" />
        </>
      );
    }
    const user = userEvent.setup();
    const view = render(<Switchable enabled={false} />);
    await screen.findByRole('table');

    await user.click(screen.getByRole('button', { name: 'host control' }));
    expect(isExpanded()).toBe(false);
    expect(screen.getByTestId('reported').textContent).toBe('false');
    expect(held()).toEqual(FREE);

    view.rerender(<Switchable enabled />);

    // Nothing was remembered: a press that did nothing at the time must not
    // fill the screen later, which is the same trap as parking an expansion
    // through the control being taken away — reached by the other door.
    expect(isExpanded()).toBe(false);
    expect(screen.getByTestId('reported').textContent).toBe('false');
    expect(held()).toEqual(FREE);

    // And the control works again now that there is something to expand.
    await user.click(screen.getByRole('button', { name: 'host control' }));
    expect(isExpanded()).toBe(true);
  });

  it("survives a switch to another view (Q7): the fill is the workspace's posture", async () => {
    const user = await expanded();

    // Through the switcher: the list is folded while the screen is filled,
    // and the switcher is what stands in for it.
    await user.click(screen.getByRole('button', { name: SWITCH }));
    await user.click(
      await screen.findByRole('menuitemradio', { name: /Ours/ }),
    );
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="view-title"]')!.textContent,
      ).toBe('Ours'),
    );

    // Switching releases one runtime and opens another — a render with
    // nothing open and the next view loading — and the fill rides through
    // it: the user asked for room for the rows, and these are rows too.
    expect(isExpanded()).toBe(true);
    expect(held()).toEqual(LOCKED);
    expect(
      screen.getByRole('button', { name: LEAVE }).getAttribute('aria-expanded'),
    ).toBe('true');
    // And the list stays folded, as it is for every fill.
    expect(document.querySelector('[data-slot="view-sidebar"]')).toBeNull();

    // Leaving from the view switched to puts the page back as usual.
    await user.click(screen.getByRole('button', { name: LEAVE }));
    expect(isExpanded()).toBe(false);
    expect(held()).toEqual(FREE);
  });

  it('ends when the view switched to cannot be opened', async () => {
    // A store that lists «Ours» and then cannot hand it over: the switcher
    // offers it, the open fails, and no title bar is drawn for it.
    class Refusing extends MemoryViewStore {
      override get(id: string): Promise<ViewInstance> {
        return id === 'ours'
          ? Promise.reject(new Error('gone'))
          : super.get(id);
      }
    }
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new Refusing({ instances: [mine, ours, byWarehouse] }),
      resolveSource: () => testSource(),
    });
    const user = await expanded(engine);

    await user.click(screen.getByRole('button', { name: SWITCH }));
    await user.click(
      await screen.findByRole('menuitemradio', { name: /Ours/ }),
    );
    await screen.findByRole('alert');

    // No control on screen means no fill: a filled screen with no way out
    // is the trap the rule exists for, and the page is given back.
    expect(isExpanded()).toBe(false);
    expect(held()).toEqual(FREE);
  });
});

/**
 * Filling the screen is a gesture about the result, so the first thing that
 * is not the result gets out of the way.
 *
 * A 224px column of navigation is what the rows were meant to get, and on a
 * phone the list is not even beside them — it stacks above and the table
 * starts 204px down, which is the fewest rows a filled screen has ever
 * bought. Leaving puts back what was there, because the fold was this
 * gesture's and not the user's.
 */
describe('the list while the screen is filled', () => {
  const sidebar = () => document.querySelector('[data-slot="view-sidebar"]');

  it('folds the list away, and puts it back on the way out', async () => {
    const user = await open();
    expect(sidebar()).not.toBeNull();

    await user.click(screen.getByRole('button', { name: FILL }));
    expect(sidebar()).toBeNull();
    // The switcher is what stands in for the list, so nothing is out of
    // reach while it is away.
    expect(screen.getByRole('button', { name: SWITCH })).toBeDefined();

    await user.click(screen.getByRole('button', { name: LEAVE }));
    expect(sidebar()).not.toBeNull();
  });

  it('leaves a list that was already folded folded', async () => {
    const user = await open();
    await user.click(screen.getByRole('button', { name: COLLAPSE_SIDEBAR }));
    expect(sidebar()).toBeNull();

    await user.click(screen.getByRole('button', { name: FILL }));
    await user.click(screen.getByRole('button', { name: LEAVE }));

    // Nothing to restore: leaving puts back the fold this gesture made, not
    // one the user made before it.
    expect(sidebar()).toBeNull();
  });

  it('lets the list back inside a fill, for that fill alone', async () => {
    const user = await open();
    await user.click(screen.getByRole('button', { name: FILL }));
    expect(sidebar()).toBeNull();

    // The button is not dead while the screen is filled: a user who wants
    // the list back gets it.
    await user.click(screen.getByRole('button', { name: EXPAND_SIDEBAR }));
    expect(sidebar()).not.toBeNull();

    // Leaving reads the page's own answer again — the fill's answer was the
    // fill's — and the next fill starts without the list, as the first did.
    await user.click(screen.getByRole('button', { name: LEAVE }));
    expect(sidebar()).not.toBeNull();
    await user.click(screen.getByRole('button', { name: FILL }));
    expect(sidebar()).toBeNull();
  });

  it('takes no focus on the way in or out', async () => {
    const user = await open();
    const fill = screen.getByRole('button', { name: FILL });

    await user.click(fill);
    // The fold moved, and the keyboard did not: nothing was pressed but the
    // control that fills the screen, and it is still the one under the
    // cursor. A shell that ran the sidebar's own focus rule here would send
    // the keyboard to a button the user never asked for.
    expect(document.activeElement).toBe(
      screen.getByRole('button', { name: LEAVE }),
    );

    await user.click(screen.getByRole('button', { name: LEAVE }));
    expect(document.activeElement).toBe(
      screen.getByRole('button', { name: FILL }),
    );
  });
});

/**
 * The embed grows no control of its own — it is the result and nothing else,
 * and a button floating over somebody's order page is chrome that page did
 * not ask for and cannot place. What it owes a host that wants one is the
 * means: the surface, and the hook the workbench uses on it.
 */
describe('an embedded view, expanded by its host', () => {
  function HostedEmbed() {
    const root = useRef<HTMLDivElement>(null);
    const toggle = useRef<HTMLButtonElement>(null);
    const expansion = useViewExpansion(root, toggle);
    // Built once and held: an engine rebuilt on every render would reopen
    // the view on every click, which is the remount this whole feature
    // exists not to do.
    const [engine] = useState(engineWith);
    return (
      <>
        <button ref={toggle} type="button" onClick={expansion.toggle}>
          host control
        </button>
        <EmbeddedView ref={root} engine={engine} instanceId="mine" />
      </>
    );
  }

  it('offers nothing of its own', async () => {
    render(<EmbeddedView engine={engineWith()} instanceId="mine" />);
    await screen.findByRole('table');

    expect(screen.queryByRole('button', { name: FILL })).toBeNull();
    expect(screen.queryByRole('button', { name: LEAVE })).toBeNull();
  });

  it('expands under a control the host owns, and locks the page once', async () => {
    give('auto');
    const user = userEvent.setup();
    render(<HostedEmbed />);
    await screen.findByRole('table');

    await user.click(screen.getByRole('button', { name: 'host control' }));
    expect(isExpanded()).toBe(true);
    expect(held()).toEqual(LOCKED);

    await user.keyboard('{Escape}');
    expect(isExpanded()).toBe(false);
    expect(document.activeElement).toBe(
      screen.getByRole('button', { name: 'host control' }),
    );
    expect(held()).toEqual(['auto', 'auto']);
  });

  it('grows its own way out while the host control is underneath it', async () => {
    const user = userEvent.setup();
    render(<HostedEmbed />);
    await screen.findByRole('table');
    // Nothing in the page until there is something to leave.
    expect(screen.queryByRole('button', { name: LEAVE })).toBeNull();

    await user.click(screen.getByRole('button', { name: 'host control' }));

    // The host's control is a sibling of the surface, so a surface covering
    // the viewport covers it too. A desktop user might guess Escape; a touch
    // device has no Escape at all, and would be left with no way out.
    const exit = screen.getByRole('button', { name: LEAVE });
    expect(surface().contains(exit)).toBe(true);

    await user.click(exit);
    expect(isExpanded()).toBe(false);
    // Gone again the moment the page can be worked normally.
    expect(screen.queryByRole('button', { name: LEAVE })).toBeNull();
    expect(document.activeElement).toBe(
      screen.getByRole('button', { name: 'host control' }),
    );
  });

  it('grows no way out when the control is already inside it', async () => {
    const user = await expanded();

    // A workbench carries its own toggle in the title bar, which the surface
    // expanded around rather than over: a second exit would be a second
    // thing to find for one choice.
    expect(screen.getAllByRole('button', { name: LEAVE })).toHaveLength(1);
    expect(
      surface().querySelector('[data-slot="view-exit"]:not([hidden])'),
    ).toBeNull();
    await user.click(screen.getByRole('button', { name: LEAVE }));
    expect(isExpanded()).toBe(false);
  });

  it('waits for a target that had not mounted when the host asked', async () => {
    function LateEmbed() {
      const root = useRef<HTMLDivElement>(null);
      const toggle = useRef<HTMLButtonElement>(null);
      const expansion = useViewExpansion(root, toggle);
      const [engine] = useState(engineWith);
      const [showing, setShowing] = useState(false);
      return (
        <>
          <button ref={toggle} type="button" onClick={expansion.toggle}>
            host control
          </button>
          <button type="button" onClick={() => setShowing(true)}>
            show
          </button>
          {showing && (
            <EmbeddedView ref={root} engine={engine} instanceId="mine" />
          )}
        </>
      );
    }
    const user = userEvent.setup();
    render(<LateEmbed />);

    // Expanded before there is anything to expand: the ref is empty, and a
    // `RefObject` reports nothing when React finally fills it. Reporting
    // `expanded: true` with no element carrying the attributes or the lock
    // would be a view the page believes is filling a screen it is not on.
    await user.click(screen.getByRole('button', { name: 'host control' }));
    expect(document.querySelector('[data-view-expanded]')).toBeNull();

    await user.click(screen.getByRole('button', { name: 'show' }));
    await waitFor(() => expect(isExpanded()).toBe(true));
    expect(held()).toEqual(LOCKED);
  });

  it('is uncovered with focus on its exit, not on the control under it', async () => {
    const user = userEvent.setup();
    render(<HostedEmbed />);
    await screen.findByRole('table');
    await user.click(screen.getByRole('button', { name: 'host control' }));

    // A second surface, later in the document, expanded over the embed.
    const container = document.body.appendChild(document.createElement('div'));
    const over = render(
      <RecordWorkbench
        engine={engineWith()}
        definitionId="orders"
        instanceId="mine"
      />,
      { container, baseElement: container },
    );
    await over.findByRole('table');
    await user.click(over.getByRole('button', { name: FILL }));

    fireEvent.keyDown(document, { key: 'Escape' });

    // The embed is still expanded, so the host's own button is still
    // underneath it — handing focus there would drop a keyboard user into
    // content nobody can see, which is the whole reason focus moves at all.
    // The exit this surface grew is the one control actually on screen.
    const exit = screen.getByRole('button', { name: LEAVE });
    expect(surface().contains(exit)).toBe(true);
    expect(document.activeElement).toBe(exit);
    expect(document.activeElement).not.toBe(
      screen.getByRole('button', { name: 'host control' }),
    );
  });

  it('gives the page back when the surface it was pointed at goes away', async () => {
    function ClosableEmbed() {
      const root = useRef<HTMLDivElement>(null);
      const toggle = useRef<HTMLButtonElement>(null);
      const expansion = useViewExpansion(root, toggle);
      const [engine] = useState(engineWith);
      const [showing, setShowing] = useState(true);
      return (
        <>
          <button ref={toggle} type="button" onClick={expansion.toggle}>
            host control
          </button>
          <button type="button" onClick={() => setShowing(false)}>
            hide
          </button>
          {showing && (
            <EmbeddedView ref={root} engine={engine} instanceId="mine" />
          )}
        </>
      );
    }
    const user = userEvent.setup();
    render(<ClosableEmbed />);
    await screen.findByRole('table');

    await user.click(screen.getByRole('button', { name: 'host control' }));
    expect(held()).toEqual(LOCKED);

    // The host takes the embed away while it is filling the screen. The hook
    // stays mounted, its `enabled` never changed and React clears the ref
    // without saying so — so nothing else would ever put the page back, and
    // it would sit unscrollable with nothing on it.
    await user.click(screen.getByRole('button', { name: 'hide' }));

    await waitFor(() => expect(held()).toEqual(FREE));
  });

  it('hands back the root either way a ref is written', async () => {
    const seen: (HTMLElement | null)[] = [];
    render(
      <EmbeddedView
        ref={node => {
          seen.push(node);
        }}
        engine={engineWith()}
        instanceId="mine"
      />,
    );
    await screen.findByRole('table');

    // A callback ref as readily as an object one — and the element it gets is
    // the `.fve-root` itself, which is the only element the stylesheet will
    // expand. The surface keeps its own handle on the same node: it reads the
    // resolved light/dark mode off it, and a caller's ref arriving through
    // `...props` would have replaced that one and broken the cascade.
    expect(seen[0]).toBe(surface());
    expect(surface().classList.contains('fve-root')).toBe(true);
  });

  it("runs the host's own ref cleanup rather than swallowing it", async () => {
    const released = vi.fn();
    const attached = vi.fn(() => released);
    const view = render(
      <EmbeddedView ref={attached} engine={engineWith()} instanceId="mine" />,
    );
    await screen.findByRole('table');
    expect(attached).toHaveBeenCalledTimes(1);
    expect(released).not.toHaveBeenCalled();

    view.unmount();
    // React 19 lets a callback ref return a cleanup, and a host that installs
    // a `ResizeObserver` on the root returns its disconnect. A merge that
    // dropped the return value would leave that observer running for the life
    // of the page — and would call the ref with null instead, which is not
    // what the host asked for.
    expect(released).toHaveBeenCalledTimes(1);
    expect(attached).toHaveBeenCalledTimes(1);
  });

  it('expands the surface a host points it inside of', async () => {
    function PointedInside() {
      const inner = useRef<HTMLElement>(null);
      const toggle = useRef<HTMLButtonElement>(null);
      const expansion = useViewExpansion(inner, toggle);
      const [engine] = useState(engineWith);
      return (
        <>
          <button
            ref={toggle}
            type="button"
            onClick={() => {
              // Pointed at something *inside* the view, which is what the
              // hook's contract allows.
              inner.current = document.querySelector('table');
              expansion.toggle();
            }}
          >
            host control
          </button>
          <EmbeddedView engine={engine} instanceId="mine" />
        </>
      );
    }
    const user = userEvent.setup();
    render(<PointedInside />);
    await screen.findByRole('table');

    await user.click(screen.getByRole('button', { name: 'host control' }));

    // The stylesheet expands `.fve-root` and nothing else, so pointing this
    // at something inside a view has to mean "expand the view it is in" —
    // otherwise the page sits locked behind a surface that never grew.
    expect(isExpanded()).toBe(true);
    expect(screen.getByRole('table').hasAttribute('data-view-expanded')).toBe(
      false,
    );
  });
});

/**
 * `position: fixed` is the viewport only while no ancestor has made itself
 * the containing block — `transform`, `filter`, `contain` and the rest all
 * do, which is to say every animated wrapper and most grid shells. The box
 * the browser actually gave us is measured rather than assumed, and the
 * difference is written back as the correction.
 */
describe('the box a host actually gives it', () => {
  /**
   * A host that owns the containing block, standing in for a layout engine.
   *
   * jsdom computes none, so the element's box is whatever this says — and it
   * has to *answer* what the hook writes rather than repeat one frozen
   * rectangle, because the correction is measured in two passes: the naive
   * difference first, then what the browser made of it. A fake that returned
   * the same box both times would report a scale that is not there.
   *
   * @param origin where the host's own box starts on the screen
   * @param scale what the host multiplies its children by — `1` for a pure
   *   translate, which changes where a box is and not how big it is
   * @param size the element's box in its own coordinates before anything is
   *   written, which is `width: 100%` of the host
   */
  function hostedIn(
    el: HTMLElement,
    origin: { x: number; y: number },
    scale = 1,
    size = { width: 200, height: 100 },
  ) {
    const local = (name: string, fallback: number) => {
      const written = el.style.getPropertyValue(`--fve-expanded-${name}`);
      return written ? Number.parseFloat(written) : fallback;
    };
    vi.spyOn(el, 'getBoundingClientRect').mockImplementation(() => {
      const width = local('w', size.width) * scale;
      const height = local('h', size.height) * scale;
      const left = origin.x + local('x', 0) * scale;
      const top = origin.y + local('y', 0) * scale;
      return {
        top,
        left,
        width,
        height,
        right: left + width,
        bottom: top + height,
        x: left,
        y: top,
        toJSON: () => ({}),
      } as DOMRect;
    });
  }

  /** The surface, expanded inside such a host. */
  async function expandWith(
    ...host: [
      { x: number; y: number },
      number?,
      { width: number; height: number }?,
    ]
  ) {
    const user = await open();
    const el = surface();
    hostedIn(el, ...host);
    await user.click(screen.getByRole('button', { name: FILL }));
    return { user, el };
  }

  const fitted = (el: HTMLElement) =>
    ['x', 'y', 'w', 'h'].map(name =>
      el.style.getPropertyValue(`--fve-expanded-${name}`),
    );

  it('writes nothing when the box is already the viewport', async () => {
    const { el } = await expandWith({ x: 0, y: 0 }, 1, {
      width: window.innerWidth,
      height: window.innerHeight,
    });

    // The stylesheet's own fallbacks are `inset: 0` written the long way, so
    // the common case stays pure CSS with no measurement written back.
    expect(fitted(el)).toEqual(['', '', '', '']);
  });

  it('writes nothing where nothing has been laid out', async () => {
    const user = await open();
    await user.click(screen.getByRole('button', { name: FILL }));

    // jsdom computes no layout, so every box is zero — a measurement with
    // nothing behind it is not a correction worth writing.
    expect(fitted(surface())).toEqual(['', '', '', '']);
  });

  it('corrects the offset an ancestor containing block introduced', async () => {
    const { user, el } = await expandWith({ x: 60, y: 40 });

    // The ancestor's padding box starts at (60, 40), so `top: 0; left: 0`
    // landed there; moving back by exactly that puts the surface on the
    // viewport, and the size comes from the viewport rather than from the
    // box that was never the right one.
    expect(fitted(el)).toEqual([
      '-60px',
      '-40px',
      `${window.innerWidth}px`,
      `${window.innerHeight}px`,
    ]);

    await user.click(screen.getByRole('button', { name: LEAVE }));
    // And the host's element is handed back without our arithmetic on it.
    expect(fitted(el)).toEqual(['', '', '', '']);
  });

  it('corrects through an ancestor that scales as well as moves', async () => {
    const { el } = await expandWith({ x: 60, y: 40 }, 0.5);

    // A `transform: scale(.5)` ancestor already reports *screen* pixels from
    // `getBoundingClientRect`, but these four properties are read in the
    // element's own coordinates, where one pixel is half a screen pixel. The
    // naive difference would land the surface at half the width and still
    // 30px from the left edge, which is the half of `transform` the old
    // `translateZ(0)` evidence never exercised.
    expect(fitted(el)).toEqual([
      '-120px',
      '-80px',
      `${window.innerWidth * 2}px`,
      `${window.innerHeight * 2}px`,
    ]);

    // And in screen pixels — the only ones a reader has — that is the
    // viewport, exactly.
    const box = el.getBoundingClientRect();
    expect([box.left, box.top, box.width, box.height]).toEqual([
      0,
      0,
      window.innerWidth,
      window.innerHeight,
    ]);
  });
});

/**
 * The shell over a real workbench, for the props no default workbench hands
 * over. Bending one of them into the shape would be testing the bending.
 */
function Shell({
  engine,
  ...props
}: {
  engine: ViewEngine;
} & Partial<Parameters<typeof WorkbenchShell>[0]>) {
  const workbench = useWorkbench(engine, 'orders', {
    kind: 'record',
    instanceId: 'mine',
  });
  return (
    <WorkbenchShell
      workbench={workbench}
      kind="record"
      title="Orders"
      editorLabel={defaultMessages['label.filter.panel']}
      editor={<div data-slot="stub-editor">conditions</div>}
      result={<div data-slot="stub-result">rows</div>}
      {...props}
    />
  );
}
