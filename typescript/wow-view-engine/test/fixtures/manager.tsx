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
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { useState } from 'react';
import { expect } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type ViewInstance,
  type ViewPermissions,
} from '../../src/index.js';
import {
  useViewList,
  useViewManager,
  type ViewListState,
  type ViewManagerController,
} from '../../src/react/index.js';
import { ViewList } from '../../src/ui/ViewList.js';
import { ViewManager } from '../../src/ui/ViewManager.js';
import { ViewSurface } from '../../src/ui/ViewSurface.js';
import { ordersDefinition, recordConfig, testSource } from '../fixtures.js';
import { tracked } from './writes.js';

/** Two personal views, alongside the system view the definition declares. */
export function instances(): ViewInstance[] {
  return [
    {
      id: 'orders-1',
      definitionId: 'orders',
      title: 'Mine',
      scope: 'personal',
      revision: '1',
      config: recordConfig(),
    },
    {
      id: 'orders-2',
      definitionId: 'orders',
      title: 'Yours',
      scope: 'personal',
      revision: '1',
      config: recordConfig(),
    },
    {
      id: 'orders-3',
      definitionId: 'orders',
      title: 'Ours',
      scope: 'shared',
      revision: '1',
      config: recordConfig(),
    },
  ];
}

export function permitting(
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

export function setup(permissions = permitting()) {
  const store = tracked(
    new MemoryViewStore({ instances: instances(), permissions }),
  );
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store,
    resolveSource: () => testSource(),
  });
  return { engine, store };
}

/**
 * The sidebar with its manager, which is how a user reaches the dialog.
 *
 * The list no longer renders the dialog: one dialog is opened from two
 * places — the sidebar's gear and the collapsed header's switcher — so
 * whoever draws both holds the open state. Here that is this harness, as it
 * is `WorkbenchShell` in the real thing.
 */
export function Sidebar({
  engine,
  withManager = true,
}: {
  engine: ViewEngine;
  withManager?: boolean;
}) {
  const list = useViewList(engine, 'orders');
  const manager = useViewManager(engine, 'orders', list);
  const [open, setOpen] = useState(false);
  return (
    <ViewSurface>
      <ViewList
        list={list}
        currentId={null}
        onOpen={() => undefined}
        onManage={withManager ? () => setOpen(true) : undefined}
      />
      {withManager && (
        <ViewManager
          manager={manager}
          list={list}
          open={open}
          onOpenChange={setOpen}
        />
      )}
    </ViewSurface>
  );
}

/** The dialog on its own, so the open view's state can be handed to it. */
export function Standalone({
  engine,
  openDirtyId,
  hold,
}: {
  engine: ViewEngine;
  openDirtyId?: string | null;
  /** Lets a test reach the controller the dialog is driving. */
  hold?(parts: { list: ViewListState; manager: ViewManagerController }): void;
}) {
  const list = useViewList(engine, 'orders');
  const manager = useViewManager(engine, 'orders', list);
  hold?.({ list, manager });
  return (
    <ViewSurface>
      <ViewManager
        manager={manager}
        list={list}
        open
        onOpenChange={() => undefined}
        openDirtyId={openDirtyId}
      />
    </ViewSurface>
  );
}

/** Row titles in the order the dialog draws them. */
export function rows(): string[] {
  return Array.from(
    document.querySelectorAll('[data-slot="view-manager-row"]'),
  ).map(row => row.textContent ?? '');
}

/**
 * One row by the title it shows. A row being renamed shows it in an input
 * rather than as text, and it is the same row throughout.
 */
export function row(title: string): HTMLElement {
  const found = Array.from(
    document.querySelectorAll('[data-slot="view-manager-row"]'),
  ).find(
    candidate =>
      candidate.textContent?.includes(title) ||
      Array.from(candidate.querySelectorAll('input')).some(field =>
        field.value.includes(title),
      ),
  );
  if (!found) throw new Error(`no row for ${title}`);
  return found as HTMLElement;
}

/** The handle one row is dragged by, and takes its arrow keys on. */
export function handle(title: string): HTMLElement {
  return within(row(title)).getByRole('button', { name: `Reorder ${title}` });
}

/** What the dialog has said out loud about a move, for a reader who cannot see it. */
export function announcement(): string {
  return (
    document.querySelector('[data-slot="view-manager-announcement"]')
      ?.textContent ?? ''
  );
}

/**
 * Each row with the audience group it is drawn in. One sortable group per
 * audience is how a drag is kept inside one: a row of the other group is not
 * a drop target at all.
 */
export function groupsOfRows(): [string, string][] {
  return Array.from(
    document.querySelectorAll('[data-slot="view-manager-group"]'),
  ).flatMap(group =>
    Array.from(group.querySelectorAll('[data-slot="view-manager-row"]')).map(
      (managed): [string, string] => [
        (managed.textContent ?? '').replace(/\s+/g, ' ').trim(),
        group.getAttribute('data-audience') ?? '',
      ],
    ),
  );
}

/** The manager opened from the sidebar, settled. */
export async function manage(engine: ViewEngine) {
  render(<Sidebar engine={engine} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Manage views' }));
  await screen.findByRole('dialog');
  await waitFor(() => expect(rows()).toHaveLength(4));
}

/** The manager on its own, settled. */
export async function standalone(
  engine: ViewEngine,
  openDirtyId?: string | null,
) {
  render(<Standalone engine={engine} openDirtyId={openDirtyId} />);
  await waitFor(() => expect(rows()).toHaveLength(4));
}
