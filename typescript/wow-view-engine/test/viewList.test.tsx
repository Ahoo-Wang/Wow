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
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ViewInstanceSummary } from '../src/index.js';
import type { ViewListState } from '../src/react/index.js';
import { ViewList, ViewSurface } from '../src/ui/index.js';

afterEach(cleanup);

function listState(overrides: Partial<ViewListState> = {}): ViewListState {
  return {
    items: [],
    all: [],
    preferences: null,
    permissions: {
      createPersonal: true,
      createShared: true,
      reorder: true,
      setDefault: true,
      instance: () => ({ save: true, rename: true, delete: true }),
    },
    defaultInstanceId: null,
    loading: false,
    error: null,
    preferencesError: null,
    reload: () => {},
    ...overrides,
  };
}

describe('ViewList on its own', () => {
  /** What a list receives: an instance's identity plus its config's kind. */
  function summary(
    overrides: Partial<ViewInstanceSummary> = {},
  ): ViewInstanceSummary {
    return {
      id: 'a',
      definitionId: 'orders',
      title: 'Mine',
      scope: 'personal',
      kind: 'record',
      revision: '1',
      ...overrides,
    };
  }

  it('shows placeholders while loading', () => {
    const { container } = render(
      <ViewList
        list={listState({ loading: true })}
        currentId={null}
        onOpen={() => {}}
      />,
    );
    expect(container.querySelectorAll('[data-slot="skeleton"]').length).toBe(3);
  });

  it('explains an empty list, and says so when it failed', () => {
    render(<ViewList list={listState()} currentId={null} onOpen={() => {}} />);
    expect(screen.getByText(/Save the current conditions/)).toBeDefined();

    cleanup();
    render(
      <ViewList
        list={listState({ error: { code: 'x', severity: 'error', path: [] } })}
        currentId={null}
        onOpen={() => {}}
      />,
    );
    expect(screen.getByText(/could not be loaded/)).toBeDefined();
  });

  it('is named by the definition, and falls back when none is given', () => {
    // The heading names the nav, so the two cannot drift apart.
    render(
      <ViewList
        list={listState({ loading: true })}
        title="订单工作台"
        currentId={null}
        onOpen={() => {}}
      />,
    );
    expect(
      screen.getByRole('navigation', { name: '订单工作台' }),
    ).toBeDefined();
    expect(screen.getByRole('heading', { name: '订单工作台' })).toBeDefined();

    cleanup();
    render(
      <ViewList
        list={listState({ loading: true })}
        currentId={null}
        onOpen={() => {}}
      />,
    );
    expect(screen.getByRole('navigation', { name: 'Views' })).toBeDefined();
  });

  it('files a system view under shared, and tags that one alone', () => {
    render(
      <ViewSurface>
        <ViewList
          list={listState({
            items: [
              summary({ id: 'a', title: 'Mine' }),
              summary({ id: 'b', title: 'Ours', scope: 'shared' }),
              summary({ id: 'c', title: 'All orders', scope: 'system' }),
            ],
          })}
          currentId="a"
          onOpen={() => {}}
        />
      </ViewSurface>,
    );

    const [personal, shared] = screen.getAllByRole('group');
    // The two groups are headings and not captions: they are what divides
    // the column, so they are readable as such.
    expect(
      within(personal).getByRole('heading', { name: 'My views' }),
    ).toBeDefined();
    expect(
      within(personal)
        .getAllByRole('button')
        .map(item => item.textContent),
    ).toEqual(['Mine']);
    // A system view is a shared view, so it sits in that group rather than
    // in a third one — the tag is what says where it came from.
    expect(
      within(shared).getByRole('heading', { name: 'Shared views' }),
    ).toBeDefined();
    expect(within(shared).getByRole('button', { name: /Ours/ })).toBeDefined();
    const tags = screen.getAllByText('system');
    expect(tags).toHaveLength(1);
    expect(tags[0].closest('button')?.textContent).toContain('All orders');
  });

  it('shows only the groups it has views for', () => {
    render(
      <ViewSurface>
        <ViewList
          list={listState({ items: [summary({ scope: 'system' })] })}
          currentId={null}
          onOpen={() => {}}
        />
      </ViewSurface>,
    );

    const groups = screen.getAllByRole('group');
    expect(groups).toHaveLength(1);
    expect(
      within(groups[0]).getByRole('heading', { name: 'Shared views' }),
    ).toBeDefined();
  });

  it('tells a record view from an analysis view by its icon', () => {
    // One data definition holds both, so the two sit in the same group and
    // the icon is all that separates them.
    render(
      <ViewSurface>
        <ViewList
          list={listState({
            items: [
              summary({ id: 'a', title: 'Rows', kind: 'record' }),
              summary({ id: 'b', title: 'Totals', kind: 'analysis' }),
            ],
          })}
          currentId={null}
          onOpen={() => {}}
        />
      </ViewSurface>,
    );

    const iconOf = (name: string) =>
      screen
        .getByRole('button', { name: new RegExp(name) })
        .querySelector('svg')
        ?.getAttribute('class');
    expect(iconOf('Rows')).toContain('inbox');
    expect(iconOf('Totals')).toContain('sigma');
  });

  it('marks the open view and opens the one clicked', () => {
    const opened = vi.fn();
    render(
      <ViewSurface>
        <ViewList
          list={listState({
            items: [
              summary({ id: 'a', title: 'Mine' }),
              summary({ id: 'b', title: 'Ours', scope: 'shared' }),
            ],
          })}
          currentId="a"
          onOpen={opened}
        />
      </ViewSurface>,
    );

    expect(screen.getByRole('button', { name: /Mine/ }).ariaCurrent).toBe(
      'true',
    );

    fireEvent.click(screen.getByRole('button', { name: /Ours/ }));
    expect(opened).toHaveBeenCalledWith('b');
  });

  it('is a navigation column and not a list on the work area', () => {
    // The ground and the rule are the list's own, so a host that composes it
    // into its own frame gets the column rather than a bare list on white.
    const { container } = render(
      <ViewSurface>
        <ViewList
          list={listState({ items: [summary()] })}
          currentId={null}
          onOpen={() => {}}
        />
      </ViewSurface>,
    );

    const nav = container.querySelector('[data-slot="view-list"]')!;
    expect(nav.className).toContain('bg-sidebar');
    expect(nav.className).toContain('text-sidebar-foreground');
    expect(nav.className).toContain('border-sidebar-border');
  });

  it('marks the open view with a bar rather than with another grey', () => {
    // Four states used to share one 3% grey, so the open view and a hovered
    // one painted the same colour. A bar is a different kind of mark, and no
    // theme can collapse it into the fill beside it.
    render(
      <ViewSurface>
        <ViewList
          list={listState({
            items: [
              summary({ id: 'a', title: 'Mine' }),
              summary({ id: 'b', title: 'Ours', scope: 'shared' }),
            ],
          })}
          currentId="a"
          onOpen={() => {}}
        />
      </ViewSurface>,
    );

    const current = screen.getByRole('button', { name: /Mine/ });
    expect(current.className).toContain(
      'shadow-[inset_2px_0_0_var(--primary)]',
    );
    expect(current.className).toContain('bg-background');
    expect(current.className).toContain('font-medium');
    // And hover is the column's own step, not the ghost variant's `muted` —
    // on this ground that one *is* the ground.
    const other = screen.getByRole('button', { name: /Ours/ });
    expect(other.className).toContain('hover:bg-sidebar-accent');
    expect(other.className).not.toContain('shadow-[inset');
  });

  it('stars the view that opens first, in a word as well as a picture', () => {
    render(
      <ViewSurface>
        <ViewList
          list={listState({
            items: [
              summary({ id: 'a', title: 'Mine' }),
              summary({ id: 'b', title: 'Ours', scope: 'shared' }),
            ],
            // The manager's star reads this same preference, so the two
            // screens cannot disagree about which view opens first.
            preferences: { order: [], defaultInstanceId: 'b', revision: '1' },
          })}
          currentId="a"
          onOpen={() => {}}
        />
      </ViewSurface>,
    );

    const starred = screen.getByRole('button', { name: /Ours/ });
    expect(
      starred.querySelector('[data-slot="view-default-star"]'),
    ).not.toBeNull();
    // A picture of a fact is not the fact: a reader hears it too.
    expect(starred.textContent).toContain('Default');
    expect(
      screen
        .getByRole('button', { name: /Mine/ })
        .querySelector('[data-slot="view-default-star"]'),
    ).toBeNull();
  });
});
