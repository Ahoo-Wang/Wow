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
    expect(within(personal).getByText('Personal')).toBeDefined();
    expect(
      within(personal)
        .getAllByRole('button')
        .map(item => item.textContent),
    ).toEqual(['Mine']);
    // A system view is a shared view, so it sits in that group rather than
    // in a third one — the tag is what says where it came from.
    expect(within(shared).getByText('Shared')).toBeDefined();
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
    expect(within(groups[0]).getByText('Shared')).toBeDefined();
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
});
