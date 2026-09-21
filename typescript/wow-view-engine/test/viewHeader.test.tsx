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

import { act, cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  systemInstanceId,
  type AnyViewRuntime,
  type ViewInstance,
  type ViewKind,
} from '../src/index.js';
import {
  useSaveCommands,
  useViewRuntime,
  type SaveCommands,
} from '../src/react/index.js';
import { ViewHeader } from '../src/ui/ViewHeader.js';
import { ViewSurface } from '../src/ui/ViewSurface.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';

afterEach(cleanup);

const mine: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

const ours: ViewInstance = {
  ...mine,
  id: 'orders-2',
  title: 'Ours',
  scope: 'shared',
};

function setup() {
  const store = new MemoryViewStore({ instances: [mine, ours] });
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store,
    resolveSource: () => testSource(),
  });
  return { engine, store };
}

function Harness({
  engine,
  runtime,
  kind = 'record',
  actions,
  leading,
  trailing,
}: {
  engine: ViewEngine;
  runtime: AnyViewRuntime;
  kind?: ViewKind;
  actions?: ReactNode;
  leading?: ReactNode;
  trailing?: ReactNode;
}) {
  const state = useViewRuntime(runtime);
  const commands = useSaveCommands(engine, runtime);
  return (
    <ViewSurface>
      <ViewHeader
        state={state}
        kind={kind}
        commands={commands}
        actions={actions}
        leading={leading}
        trailing={trailing}
      />
    </ViewSurface>
  );
}

/** Commands for a header with no view behind it: nothing is allowed. */
const NOTHING: SaveCommands = {
  save: () => Promise.resolve(null),
  saveAs: () => Promise.resolve(null),
  rename: () => Promise.resolve(null),
  delete: () => Promise.resolve(false),
  revert: () => undefined,
  retry: () =>
    Promise.resolve({ landed: false, written: false, instance: null }),
  abandon: () => undefined,
  resolveConflict: () =>
    Promise.resolve({ landed: false, written: false, instance: null }),
  can: {
    save: false,
    saveAs: false,
    rename: false,
    delete: false,
    revert: false,
    createPersonal: false,
    createShared: false,
  },
  state: {
    pending: false,
    error: null,
    write: null,
    dirty: false,
    blocked: false,
    hasErrors: false,
    lastSavedAt: null,
  },
};

function header(): HTMLElement {
  const found = document.querySelector('[data-slot="view-header"]');
  if (!found) throw new Error('no header');
  return found as HTMLElement;
}

function title(): HTMLElement {
  const found = document.querySelector('[data-slot="view-title"]');
  if (!found) throw new Error('no title');
  return found as HTMLElement;
}

/** One of the title bar's two groups, by the slot it is drawn under. */
function group(slot: 'view-identity' | 'view-controls'): HTMLElement {
  const found = header().querySelector(`[data-slot="${slot}"]`);
  if (!found) throw new Error(`no ${slot}`);
  return found as HTMLElement;
}

describe('ViewHeader', () => {
  it('renders nothing while no view is open', () => {
    render(
      <ViewSurface>
        <ViewHeader state={null} kind="record" commands={NOTHING} />
      </ViewSurface>,
    );
    expect(document.querySelector('[data-slot="view-header"]')).toBeNull();
  });

  it('says what the view is called and who it is for', async () => {
    const { engine } = setup();
    const runtime = await engine.open('orders-1');
    render(<Harness engine={engine} runtime={runtime} />);

    expect(title().textContent).toBe('Mine');
    expect(header().textContent).toContain('personal');
    // Nothing has been edited, so neither mark is on the title.
    expect(title().dataset.dirty).toBeUndefined();
    expect(header().textContent).not.toContain('Edited');
    expect(header().textContent).not.toContain('Not saved yet');
  });

  /**
   * The name is the one thing on this row that truncates, and `truncate` is
   * visual only: a screen reader reads the whole of it, a pointer user got
   * an ellipsis and no way past it. The attribute is what jsdom can hold —
   * the geometry belongs to the browser story.
   */
  it('carries the whole name it may have to truncate', async () => {
    const long = 'Orders awaiting warehouse confirmation in the EU region';
    const store = new MemoryViewStore({
      instances: [{ ...mine, title: long }],
    });
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    const runtime = await engine.open('orders-1');
    render(<Harness engine={engine} runtime={runtime} />);

    expect(title().className).toContain('truncate');
    expect(title().getAttribute('title')).toBe(long);
  });

  it('says a shared view is shared', async () => {
    const { engine } = setup();
    const runtime = await engine.open('orders-2');
    render(<Harness engine={engine} runtime={runtime} />);

    expect(header().textContent).toContain('shared');
  });

  /**
   * A system view is a shared view — that is `audienceOf`'s answer — so the
   * tag is what says it came with the definition rather than from a user.
   */
  it('says where a system view came from', async () => {
    const { engine } = setup();
    const runtime = await engine.open(systemInstanceId('orders', 'all'));
    render(<Harness engine={engine} runtime={runtime} />);

    expect(header().textContent).toContain('system');
  });

  it('marks a view that has never been saved', () => {
    const { engine } = setup();
    const runtime = engine.create('orders', {
      title: 'Untitled',
      scope: 'personal',
      config: recordConfig(),
    });
    render(<Harness engine={engine} runtime={runtime} />);

    expect(header().textContent).toContain('Not saved yet');
    expect(header().textContent).not.toContain('Edited');
  });

  it('marks a saved view that has been edited since', async () => {
    const { engine } = setup();
    const runtime = await engine.open('orders-1');
    render(<Harness engine={engine} runtime={runtime} />);

    act(() => runtime.edit({ pageSize: 50 }));

    expect(header().textContent).toContain('Edited');
    expect(title().dataset.dirty).toBe('true');
  });

  it('names the kind it was told it is showing', async () => {
    const user = userEvent.setup();
    const { engine } = setup();
    const runtime = await engine.open('orders-1');
    render(<Harness engine={engine} runtime={runtime} kind="analysis" />);

    // One data definition holds record and analysis views together, so the
    // icon has to be nameable rather than merely recognisable.
    const trigger = header().querySelector('[data-slot="tooltip-trigger"]');
    await user.hover(trigger as Element);
    expect(await screen.findByText('Analysis view')).toBeDefined();
  });

  /**
   * The bar reads as two groups and nothing crosses between them: saving a
   * view is part of saying which view this is, and the host's own buttons
   * end the line — so a page that adds one never has to know what this
   * package happens to be drawing beside it.
   */
  it("ends the line with the host's own actions", async () => {
    const { engine } = setup();
    const runtime = await engine.open('orders-1');
    render(
      <Harness
        engine={engine}
        runtime={runtime}
        actions={<button type="button">Export</button>}
      />,
    );

    const controls = group('view-controls');
    expect(
      within(controls).getByRole('button', { name: 'Export' }),
    ).toBeDefined();
    expect(controls.lastElementChild?.textContent).toBe('Export');
  });

  it('keeps the save commands with the view they save', async () => {
    const { engine } = setup();
    const runtime = await engine.open('orders-1');
    render(
      <Harness
        engine={engine}
        runtime={runtime}
        actions={<button type="button">Export</button>}
      />,
    );

    const identity = group('view-identity');
    expect(identity.querySelector('[data-slot="save-actions"]')).not.toBeNull();
    // The title they act on is in the same group and the host's button is
    // not: the two sides of the bar are drawn apart.
    expect(identity.contains(title())).toBe(true);
    expect(
      within(identity).queryByRole('button', { name: 'Export' }),
    ).toBeNull();
  });

  /**
   * The controls for how the view is being looked at come before the host's
   * own, by the same rule: everything ahead of the host's buttons is this
   * package's.
   */
  it('puts what governs the view before what the host added', async () => {
    const { engine } = setup();
    const runtime = await engine.open('orders-1');
    render(
      <Harness
        engine={engine}
        runtime={runtime}
        trailing={<button type="button">Filter</button>}
        actions={<button type="button">Export</button>}
      />,
    );

    const texts = Array.from(group('view-controls').children).map(
      child => child.textContent,
    );
    expect(texts[0]).toBe('Filter');
    expect(texts[texts.length - 1]).toBe('Export');
    // A separator earns its place only when there are two sides to divide.
    expect(header().querySelectorAll('[data-slot="separator"]')).toHaveLength(
      1,
    );
  });

  it('draws no separator with only one side to the controls', async () => {
    const { engine } = setup();
    const runtime = await engine.open('orders-1');
    render(
      <Harness
        engine={engine}
        runtime={runtime}
        actions={<button type="button">Export</button>}
      />,
    );

    expect(header().querySelectorAll('[data-slot="separator"]')).toHaveLength(
      0,
    );
  });

  /**
   * The start of the line is the surface's, for whatever it has to put back
   * there — what a collapsed sidebar leaves behind, for instance. Nothing is
   * reserved for it, so a header without one reads exactly as before.
   */
  it('starts the line with whatever the surface puts there', async () => {
    const { engine } = setup();
    const runtime = await engine.open('orders-1');
    render(
      <Harness
        engine={engine}
        runtime={runtime}
        leading={<button type="button">Views</button>}
      />,
    );

    const first = header().querySelector('div')?.firstElementChild;
    expect(first?.textContent).toBe('Views');
  });

  it('draws no separator when the host adds nothing', async () => {
    const { engine } = setup();
    const runtime = await engine.open('orders-1');
    render(<Harness engine={engine} runtime={runtime} />);

    expect(header().querySelectorAll('[data-slot="separator"]')).toHaveLength(
      0,
    );
  });
});
