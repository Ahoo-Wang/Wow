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
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import type { ViewInstance, ViewPermissions } from '../src/index.js';
import { defaultMessages, RecordWorkbench } from '../src/ui/index.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';

afterEach(cleanup);

const COLLAPSE = defaultMessages['label.workbench.collapse-sidebar'];
const EXPAND = defaultMessages['label.workbench.expand-sidebar'];
const SWITCH = defaultMessages['label.workbench.switch-view'];
/** The same control with no view behind it, where the label is the name. */
const CHOOSE = defaultMessages['label.workbench.choose-view'];
const MANAGE = defaultMessages['label.manage.open'];

/**
 * Two personal views and one shared, so the switcher has both groups to
 * order, and the definition's own `all` view gives it a system row to tag.
 */
function instances(): ViewInstance[] {
  return [
    {
      id: 'mine',
      definitionId: 'orders',
      title: 'Mine',
      scope: 'personal',
      revision: '1',
      config: recordConfig(),
    },
    {
      id: 'ours',
      definitionId: 'orders',
      title: 'Ours',
      scope: 'shared',
      revision: '1',
      config: recordConfig(),
    },
  ];
}

function permitting(
  overrides: Partial<ViewPermissions> = {},
): () => ViewPermissions {
  return () => ({
    createPersonal: true,
    createShared: true,
    reorder: true,
    setDefault: true,
    instance: () => ({ save: true, rename: true, delete: true }),
    ...overrides,
  });
}

function engineWith(
  saved = instances(),
  permissions = permitting(),
  source = testSource(),
): ViewEngine {
  return new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances: saved, permissions }),
    resolveSource: () => source,
  });
}

/** The workbench, opened on a view and settled. */
async function open(engine: ViewEngine, instanceId: string | null = 'mine') {
  const user = userEvent.setup();
  render(
    <RecordWorkbench
      engine={engine}
      definitionId="orders"
      instanceId={instanceId}
    />,
  );
  await screen.findByRole('table');
  return user;
}

const sidebar = () => document.querySelector('[data-slot="view-sidebar"]');
const switcher = () => screen.queryByRole('button', { name: SWITCH });
const chooser = () => screen.queryByRole('button', { name: CHOOSE });

describe('collapsing the sidebar', () => {
  it('opens with the list beside the result, and a way to fold it away', async () => {
    await open(engineWith());

    expect(sidebar()).not.toBeNull();
    expect(
      screen
        .getByRole('button', { name: COLLAPSE })
        .getAttribute('aria-expanded'),
    ).toBe('true');
    // With the list on screen the list *is* the switcher; a second one would
    // be two controls for one choice.
    expect(switcher()).toBeNull();
  });

  it('takes the list, and the rule beside it, out of the page', async () => {
    const user = await open(engineWith());
    await user.click(screen.getByRole('button', { name: COLLAPSE }));

    expect(sidebar()).toBeNull();
    // The separator was the sidebar's edge; with nothing to the left of the
    // result it is a line drawn down the middle of nothing.
    expect(
      document.querySelectorAll('[data-orientation="vertical"]'),
    ).toHaveLength(0);
  });

  it('puts the definition, the switcher and the way back in the title bar', async () => {
    const user = await open(engineWith());
    await user.click(screen.getByRole('button', { name: COLLAPSE }));

    const identity = document.querySelector<HTMLElement>(
      '[data-slot="view-identity"]',
    )!;
    const order = [...identity.querySelectorAll('[data-slot]')].map(node =>
      node.getAttribute('data-slot'),
    );
    expect(order[0]).toBe('view-collapsed');
    expect(identity.textContent).toContain('Orders');
    // Two levels, one page: the definition is the `h1`, the view the `h2`.
    expect(
      screen.getByRole('heading', { level: 1, name: 'Orders' }),
    ).toBeDefined();
    expect(screen.getByRole('heading', { level: 2 })).toBeDefined();
    expect(switcher()).not.toBeNull();
  });

  /**
   * The switcher is the collapsed group's spring, and it stops at its own
   * contents. `grow` alone stretched it across the group whatever it had to
   * say, and the vendored button centres, so a short name floated in the
   * middle of a 470px pill: `max-w-fit` caps it at the name it shows and
   * `justify-start` puts the label where the icon leaves off.
   */
  it('sizes the switcher to what it says, not to the room it is given', async () => {
    const user = await open(engineWith());
    await user.click(screen.getByRole('button', { name: COLLAPSE }));

    const trigger = switcher()!;
    // **Surviving class assertions**: layout at a call site, which is what
    // `className` is for — there is no state behind a length or a
    // direction, and jsdom lays nothing out. The pixels are the browser
    // stories’.
    expect(trigger.className).toContain('max-w-fit');
    expect(trigger.className).toContain('justify-start');

    // The floor is on the trigger, because `w-0` is also what stops the
    // label's `nowrap` asking the group for the whole string — so it has to
    // be the label's 6em plus the furniture around it. The label keeps its
    // own 6em to stay clear of the icons.
    expect(trigger.className).toContain('min-w-[calc(6em+3.25rem)]');
    const label = within(trigger).getByText('Mine');
    expect(label.className).toContain('min-w-[6em]');

    // And the group around it may not be squeezed below that floor, or it
    // reports a width it cannot keep and the bar never wraps.
    const collapsed = document.querySelector<HTMLElement>(
      '[data-slot="view-collapsed"]',
    )!;
    expect(collapsed.className).not.toContain('min-w-0');
    // And it has no box of its own: the switcher is the identity group's
    // spring directly, so with a short name the audience word and Save
    // stand against it rather than at the far end of a box that grew on.
    expect(collapsed.className).toContain('contents');
    expect(collapsed.className).not.toContain('grow');
  });

  /**
   * The definition's title is the first thing to go when the row runs out of
   * room, and "the row" is this bar rather than the page: a viewport
   * breakpoint kept it on screen in a 360px panel on a wide page while the
   * view's own name was down to two characters.
   */
  it('measures the definition title against the bar rather than the viewport', async () => {
    const user = await open(engineWith());
    await user.click(screen.getByRole('button', { name: COLLAPSE }));

    const definition = document.querySelector<HTMLElement>(
      '[data-slot="definition-title"]',
    )!;
    expect(definition.className).toContain('hidden');
    expect(definition.className).toContain('@md/header:block');
    expect(definition.className).not.toContain('sm:inline');
    // The page's name, at the level it has in the sidebar, and joined to
    // the view it is the parent of.
    expect(definition.tagName).toBe('H1');
    expect(definition.nextElementSibling?.getAttribute('data-slot')).toBe(
      'definition-separator',
    );
  });

  it('leaves the title a heading the region still points at', async () => {
    const user = await open(engineWith());
    await user.click(screen.getByRole('button', { name: COLLAPSE }));

    // The switcher shows the name, so the heading goes out of the layout —
    // but not out of the document: `main` is labelled by its id, and an id
    // that addresses nothing is a broken label rather than a missing one.
    const title = document.querySelector<HTMLElement>(
      '[data-slot="view-title"]',
    )!;
    expect(title.tagName).toBe('H2');
    expect(title.className).toContain('sr-only');
    expect(
      document.querySelector('main')!.getAttribute('aria-labelledby'),
    ).toBe(title.id);
  });

  it('hands focus to the control that undoes what was just done', async () => {
    const user = await open(engineWith());

    await user.click(screen.getByRole('button', { name: COLLAPSE }));
    // The button just pressed is gone, so focus would fall to the body and a
    // keyboard user would start the page again from the top.
    expect(document.activeElement).toBe(
      screen.getByRole('button', { name: EXPAND }),
    );

    await user.click(screen.getByRole('button', { name: EXPAND }));
    expect(document.activeElement).toBe(
      screen.getByRole('button', { name: COLLAPSE }),
    );
  });

  it('takes no focus on arrival', async () => {
    await open(engineWith());

    // The first layout is the state the workbench opened in, which nobody
    // asked for; moving focus for it would steal the page from its host.
    expect(document.activeElement).toBe(document.body);
  });

  it('starts folded when the host says so', async () => {
    const engine = engineWith();
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="mine"
        defaultSidebarOpen={false}
      />,
    );
    await screen.findByRole('table');

    expect(sidebar()).toBeNull();
    expect(switcher()).not.toBeNull();
  });

  it('tells a host every time it changes, and nothing on arrival', async () => {
    const changed = vi.fn();
    const engine = engineWith();
    const user = userEvent.setup();
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="mine"
        onSidebarOpenChange={changed}
      />,
    );
    await screen.findByRole('table');
    expect(changed).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: COLLAPSE }));
    expect(changed).toHaveBeenCalledTimes(1);
    expect(changed).toHaveBeenCalledWith(false);
    await user.click(screen.getByRole('button', { name: EXPAND }));
    expect(changed).toHaveBeenLastCalledWith(true);
  });

  it('gives the result the width the list was taking', async () => {
    const user = await open(engineWith());
    const main = document.querySelector<HTMLElement>('main')!;
    // What the surface actually lays out beside the main column. The exit
    // control the surface always carries is `hidden` unless the view is
    // filling the screen with its toggle underneath it, so it takes no room
    // and is not one of them.
    const beside = () =>
      [...main.parentElement!.children].filter(
        node => node !== main && !node.hasAttribute('hidden'),
      );
    expect(beside()).not.toHaveLength(0);

    await user.click(screen.getByRole('button', { name: COLLAPSE }));
    // Nothing fixed-width is left beside it, which is the whole point on a
    // narrow screen: `w-56` of list is `w-56` the rows do not get.
    expect(beside()).toHaveLength(0);
  });
});

describe('the view switcher', () => {
  /** The switcher, opened. */
  async function opened(engine: ViewEngine, instanceId = 'mine') {
    const user = await open(engine, instanceId);
    await user.click(screen.getByRole('button', { name: COLLAPSE }));
    await user.click(screen.getByRole('button', { name: SWITCH }));
    await screen.findByRole('menu');
    return user;
  }

  it('groups the views as the sidebar groups them, personal first', async () => {
    await opened(engineWith());

    const groups = [
      ...screen.getByRole('menu').querySelectorAll('[role="group"]'),
    ];
    expect(groups[0].textContent).toContain(
      defaultMessages['label.scope.group.personal'],
    );
    expect(groups[0].textContent).toContain('Mine');
    expect(groups[1].textContent).toContain(
      defaultMessages['label.scope.group.shared'],
    );
    // A system view is a shared view — that is `audienceOf`'s answer, not a
    // third group — and only the tag says where it came from.
    expect(groups[1].textContent).toContain('Ours');
    expect(groups[1].textContent).toContain('All orders');
  });

  it('tags the view that ships with the definition, and only that one', async () => {
    await opened(engineWith());

    const tags = screen.getAllByText(defaultMessages['label.scope.tag.system']);
    expect(tags).toHaveLength(1);
    expect(tags[0].closest('[role="menuitemradio"]')!.textContent).toContain(
      'All orders',
    );
  });

  it('marks the view that is open', async () => {
    await opened(engineWith());

    const checked = screen
      .getAllByRole('menuitemradio')
      .filter(item => item.getAttribute('aria-checked') === 'true');
    expect(checked.map(item => item.textContent)).toEqual(['Mine']);
  });

  it('opens the view that was chosen', async () => {
    const user = await opened(engineWith());

    await user.click(screen.getByRole('menuitemradio', { name: /Ours/ }));
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="view-title"]')!.textContent,
      ).toBe('Ours'),
    );
  });

  it('is operable from the keyboard alone', async () => {
    const user = await open(engineWith());
    await user.click(screen.getByRole('button', { name: COLLAPSE }));

    screen.getByRole('button', { name: SWITCH }).focus();
    await user.keyboard('{Enter}');
    await screen.findByRole('menu');
    await user.keyboard('{ArrowDown}');
    // Which row the menu lands on is Base UI's business; that the keyboard
    // reaches the rows at all is this package's.
    expect(screen.getByRole('menu').contains(document.activeElement)).toBe(
      true,
    );
  });

  it('still asks before a draft is released', async () => {
    const user = await opened(engineWith());
    await user.keyboard('{Escape}');

    // Edit the draft, so switching is a delete rather than a navigation.
    await user.click(
      screen.getByRole('button', {
        name: new RegExp(defaultMessages['label.filter.panel']),
      }),
    );
    await user.click(
      screen.getByRole('button', {
        name: defaultMessages['label.filter.add'],
      }),
    );
    const picker = await screen.findByRole('dialog');
    await user.click(
      within(picker).getByRole('checkbox', { name: 'Warehouse' }),
    );
    await user.click(
      screen.getByRole('button', {
        name: defaultMessages['label.filter.pick-done'],
      }),
    );

    await user.click(screen.getByRole('button', { name: SWITCH }));
    await user.click(
      await screen.findByRole('menuitemradio', { name: /Ours/ }),
    );

    // The switcher is a shorter way to the same door, not a way around it.
    expect(
      await screen.findByText(defaultMessages['label.leave.heading']),
    ).toBeDefined();
  });
});

describe('the view manager behind two entries', () => {
  it('opens the one dialog from the sidebar and from the switcher', async () => {
    const user = await open(engineWith());

    await user.click(screen.getByRole('button', { name: MANAGE }));
    expect(await screen.findByRole('dialog')).toBeDefined();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    await user.click(screen.getByRole('button', { name: COLLAPSE }));
    await user.click(screen.getByRole('button', { name: SWITCH }));
    await user.click(await screen.findByRole('menuitem', { name: MANAGE }));

    // One dialog, whichever way in was used: two entries each holding their
    // own open state would be two dialogs.
    expect(await screen.findByRole('dialog')).toBeDefined();
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
  });

  it('offers no way in at all when nothing can be managed', async () => {
    const readOnly = permitting({
      reorder: false,
      setDefault: false,
      instance: () => ({ save: false, rename: false, delete: false }),
    });
    const user = await open(engineWith(instances(), readOnly));

    expect(screen.queryByRole('button', { name: MANAGE })).toBeNull();

    await user.click(screen.getByRole('button', { name: COLLAPSE }));
    await user.click(screen.getByRole('button', { name: SWITCH }));
    await screen.findByRole('menu');
    // A button whose only lesson is that it leads to a dialog of read-only
    // rows is a button that teaches nothing, in either place.
    expect(screen.queryByRole('menuitem', { name: MANAGE })).toBeNull();
  });
});

describe('collapsing with nothing to switch to', () => {
  it('keeps the way back when no view would open', async () => {
    const user = userEvent.setup();
    render(
      <RecordWorkbench
        engine={engineWith()}
        definitionId="orders"
        instanceId="no-such-view"
        defaultSidebarOpen={false}
      />,
    );
    await screen.findByRole('alert');

    // Without a title bar to carry it, a workbench that could not be
    // un-collapsed would be a trap: the list is the only way to another view.
    await user.click(screen.getByRole('button', { name: EXPAND }));
    expect(sidebar()).not.toBeNull();
  });

  it('switches on a list of one', async () => {
    const only: ViewInstance[] = [instances()[0]];
    const user = await open(engineWith(only));
    await user.click(screen.getByRole('button', { name: COLLAPSE }));
    await user.click(screen.getByRole('button', { name: SWITCH }));

    await screen.findByRole('menu');
    // The definition's own view is always there, store or no store, so one
    // saved view still makes two rows — one personal group, one shared.
    expect(screen.getAllByRole('menuitemradio')).toHaveLength(2);
  });

  it('shows the switcher under its own name while the list loads', async () => {
    const engine = engineWith();
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="mine"
        defaultSidebarOpen={false}
      />,
    );

    // The trigger is on screen before the list answers: a control that
    // appeared late would move the title bar under the user's pointer. It
    // has no view to name yet, so it names itself rather than standing there
    // blank.
    expect(chooser()).not.toBeNull();
    expect(switcher()).toBeNull();
    await screen.findByRole('table');
  });

  /**
   * The screen that reports a view which will not open is the one screen
   * where the switcher is the only way anywhere — and it used to be an icon,
   * a chevron and a gap where a name would have been.
   */
  it('names the switcher for what it does when no view opened', async () => {
    render(
      <RecordWorkbench
        engine={engineWith()}
        definitionId="orders"
        instanceId="no-such-view"
        defaultSidebarOpen={false}
      />,
    );
    await screen.findByRole('alert');

    const trigger = chooser()!;
    // Read the same to the eye and to a screen reader: a control whose
    // visible word is not in its accessible name cannot be asked for out
    // loud (WCAG 2.5.3).
    expect(within(trigger).getByText(CHOOSE).getAttribute('data-slot')).toBe(
      'view-switcher-label',
    );
    expect(switcher()).toBeNull();
  });

  /**
   * And the definition's name is measured by the same rule it is measured by
   * over an open view. The bar is drawn outside `ViewHeader` here, so without
   * a container of its own the `@md/header` question had nothing to ask and
   * the name stayed hidden at every width — on the one screen where nothing
   * else says which definition this is.
   */
  it('gives that bar the header container the name is measured against', async () => {
    render(
      <RecordWorkbench
        engine={engineWith()}
        definitionId="orders"
        instanceId="no-such-view"
        defaultSidebarOpen={false}
      />,
    );
    await screen.findByRole('alert');

    const definition = document.querySelector<HTMLElement>(
      '[data-slot="definition-title"]',
    )!;
    expect(definition.textContent).toBe('Orders');
    expect(definition.closest('[class~="@container/header"]')).not.toBeNull();
  });
});
