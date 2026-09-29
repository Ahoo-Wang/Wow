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

/**
 * Editors show words and give keys back (D2): a label an editor left as it
 * was shown goes back as the key it was said from — typed away and back,
 * after a row above it went, and across a change of language while the
 * editor was open. The guard of words at the leaf is
 * `textAtTheLeaf.test.tsx`; this is its other half, what comes back.
 */

import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ViewEngine,
  systemInstanceId,
  type DashboardViewConfig,
  MemoryViewStore,
  text,
  type ViewRuntime,
} from '../src/index.js';
import {
  DashboardWorkbench,
  DataWorkbench,
  MessagesProvider,
  ViewHost,
  ViewSurface,
  keptKey,
  useSaidText,
  useSay,
} from '../src/ui/index.js';
import { RenameInput } from '../src/ui/RenameInput.js';
import { testSource } from './fixtures.js';
import {
  EN,
  ZH,
  keyedEngine,
  keyedOrders,
  markersIn,
} from './fixtures/keyed.js';
import { openTray } from './fixtures/workbench.js';

afterEach(cleanup);

async function settled() {
  await act(async () => {
    await new Promise(resolve => setTimeout(resolve, 0));
  });
}

const KEY = /^/;

function board(engine: ViewEngine, words: Record<string, string>) {
  return (
    <ViewHost engine={engine} messages={words}>
      <DashboardWorkbench definitionId="overview" instanceId="mine" />
    </ViewHost>
  );
}

/** The reader's board opened and being built, in English. */
async function building() {
  const { engine } = keyedEngine();
  const view = render(board(engine, EN));
  await waitFor(() => expect(screen.getAllByRole('table').length).toBe(3));
  fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
  await settled();
  const runtime = engine
    .openRuntimes()
    .find(
      open => open.kind === 'dashboard',
    ) as ViewRuntime<DashboardViewConfig>;
  const draft = () => runtime.getSnapshot().draft as DashboardViewConfig;
  const panel = (id: string) => draft().panels.find(entry => entry.id === id)!;
  return {
    engine,
    runtime,
    draft,
    panel,
    switchTo: async (words: Record<string, string>) => {
      view.rerender(board(engine, words));
      await settled();
    },
  };
}

async function panelMenu(name: string, item: string | RegExp) {
  fireEvent.click(
    screen.getByRole('button', { name: `Actions for “${name}”` }),
  );
  fireEvent.click(await screen.findByRole('menuitem', { name: item }));
  await settled();
}

describe('the board’s editors give keys back (D2)', () => {
  it('a filter’s name typed away and back is the key again, and the board is clean', async () => {
    const { draft, runtime } = await building();
    const key = draft().fields[0].label;
    expect(key).toMatch(KEY);
    fireEvent.click(
      screen.getByRole('button', { name: 'Settings of “Region”' }),
    );
    await settled();
    const box = document.querySelector<HTMLInputElement>(
      '[data-slot="dashboard-filter-name"]',
    )!;
    expect(box.value).toBe('Region');
    fireEvent.change(box, { target: { value: 'Regions' } });
    expect(draft().fields[0].label).toBe('Regions');
    fireEvent.change(box, { target: { value: 'Region' } });
    expect(draft().fields[0].label).toBe(key);
    expect(runtime.getSnapshot().dirty).toBe(false);
    // What is typed stays in the box as typed, a space and all.
    fireEvent.change(box, { target: { value: 'Area ' } });
    expect(box.value).toBe('Area ');
    expect(draft().fields[0].label).toBe('Area');
  });

  it('a link that moved up when the one above it went keeps its own keys', async () => {
    const { draft } = await building();
    const before = draft().panels.find(entry => entry.id === 'links')!;
    const second = before.kind === 'links' ? before.items[1] : undefined;
    expect(second?.label).toMatch(KEY);
    await panelMenu('Links', 'Edit content…');
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Remove link 1' }),
    );
    fireEvent.click(within(dialog).getByRole('button', { name: 'Update' }));
    await settled();
    const after = draft().panels.find(entry => entry.id === 'links')!;
    expect(after.kind === 'links' && after.items).toEqual([second]);
    expect(after.title).toBe(before.title);
  });

  it('a note’s form left as it opened, the language changed meanwhile, gives the keys back', async () => {
    const { panel, switchTo } = await building();
    const before = panel('note');
    await panelMenu('Note', 'Edit content…');
    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByRole('textbox', { name: 'Panel title (optional)' }),
    ).toHaveProperty('value', 'Note');
    await switchTo(ZH);
    fireEvent.click(
      within(dialog).getByRole('button', { name: /Update|更新/ }),
    );
    await settled();
    expect(panel('note')).toEqual(before);
    expect(markersIn(document.body)).toEqual([]);
  });

  it('a panel’s title left as it opened, the language changed meanwhile, stays the key', async () => {
    const { panel, switchTo } = await building();
    const key = panel('note').title;
    expect(key).toMatch(KEY);
    await panelMenu('Note', 'Rename');
    const box = screen.getByRole('textbox', { name: 'Panel title' });
    expect((box as HTMLInputElement).value).toBe('Note');
    await switchTo(ZH);
    fireEvent.keyDown(box, { key: 'Enter' });
    await settled();
    expect(panel('note').title).toBe(key);
  });

  it('an untitled panel renamed to the name the board calls it keeps no title', async () => {
    const user = userEvent.setup();
    const { panel, runtime, switchTo } = await building();
    expect(panel('by').title).toBeUndefined();
    const rename = async (name: string) => {
      await user.click(
        screen.getByRole('button', { name: `Actions for “${name}”` }),
      );
      await user.click(
        within(await screen.findByRole('menu')).getByRole('menuitem', {
          name: /^(Rename|重命名)$/,
        }),
      );
      return document.querySelector<HTMLInputElement>(
        '[data-slot="panel-title-input"]',
      )!;
    };
    const box = await rename('By warehouse');
    expect(box.value).toBe('By warehouse');
    // Left as it opened.
    await user.keyboard('{Enter}');
    expect(panel('by').title).toBeUndefined();
    // Opened, the language switched, and typed as the board now calls it:
    // still the name the board makes up, and no title of its own.
    const again = await rename('By warehouse');
    await switchTo(ZH);
    const name = '中:By warehouse';
    await user.clear(again);
    await user.type(again, `${name}{Enter}`);
    expect(panel('by').title).toBeUndefined();
    expect(runtime.getSnapshot().dirty).toBe(false);
  });

  it('a panel promoted to a view is saved under its title’s key', async () => {
    const { engine, panel, switchTo } = await building();
    const key = panel('own').title;
    expect(key).toMatch(KEY);
    const saved = vi
      .spyOn(engine, 'saveOwnedView')
      .mockResolvedValue(null as never);
    await panelMenu('Own view', 'Save as a view…');
    let dialog = await screen.findByRole('dialog');
    expect(
      (
        within(dialog).getByRole('textbox', {
          name: 'Title',
        }) as HTMLInputElement
      ).value,
    ).toBe('Own view');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save view' }));
    await settled();
    expect(saved).toHaveBeenLastCalledWith(
      expect.anything(),
      'own',
      expect.objectContaining({ title: key }),
    );
    // Left as it opened while the language changed: still the key.
    await switchTo(ZH);
    dialog = screen.getByRole('dialog');
    fireEvent.click(
      within(dialog)
        .getAllByRole('button')
        .find(
          button =>
            button.getAttribute('type') === 'submit' ||
            /Save view|保存视图/.test(button.textContent ?? ''),
        )!,
    );
    await settled();
    expect(saved).toHaveBeenLastCalledWith(
      expect.anything(),
      'own',
      expect.objectContaining({ title: key }),
    );
  });
});

describe('the analysis editors give keys back (D2)', () => {
  function page(engine: ViewEngine, instance: string, words: object) {
    return (
      <ViewHost engine={engine} messages={words as Record<string, string>}>
        <DataWorkbench
          definitionId="orders"
          instanceId={systemInstanceId('orders', instance)}
        />
      </ViewHost>
    );
  }

  it('a metric’s name box left as it opened, the language changed meanwhile, keeps the key', async () => {
    const { engine } = keyedEngine();
    const view = render(page(engine, 'by-warehouse', EN));
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    await openTray();
    await settled();
    const [runtime] = engine.openRuntimes() as ViewRuntime[];
    const label = () => {
      const draft = runtime.getSnapshot().draft;
      return draft.kind === 'analysis' ? draft.metrics[0].label : undefined;
    };
    const key = label();
    fireEvent.click(
      screen.getByRole('button', { name: 'More settings for Order count' }),
    );
    fireEvent.click(
      await screen.findByRole('menuitem', { name: 'Display name…' }),
    );
    const box = await screen.findByRole('textbox', {
      name: 'Display name for Order count',
    });
    view.rerender(page(engine, 'by-warehouse', ZH));
    await settled();
    fireEvent.keyDown(box, { key: 'Enter' });
    await settled();
    expect(label()).toBe(key);
    expect(runtime.getSnapshot().dirty).toBe(false);
  });

  it('a reference line’s caption, after the line above it went, gives back its own key', async () => {
    const { engine } = keyedEngine();
    render(page(engine, 'chart', EN));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Visualize' })).toBeTruthy(),
    );
    const [runtime] = engine.openRuntimes() as ViewRuntime[];
    const lines = () => {
      const draft = runtime.getSnapshot().draft;
      return draft.kind === 'analysis'
        ? (draft.chart?.cartesian?.referenceLines ?? [])
        : [];
    };
    const [, floor] = lines();
    expect(floor.label).toMatch(KEY);
    fireEvent.click(screen.getByRole('button', { name: 'Visualize' }));
    await settled();
    fireEvent.click(screen.getAllByRole('button', { name: /options/ })[0]);
    await settled();
    const captions = () =>
      screen.queryAllByRole('textbox', {
        name: 'Caption',
      }) as HTMLInputElement[];
    for (const tab of screen.queryAllByRole('tab')) {
      if (captions().length > 0) break;
      fireEvent.click(tab);
      await settled();
    }
    expect(captions().map(box => box.value)).toEqual([
      'Target line',
      'Floor line',
    ]);
    const first = screen.getByRole('group', { name: 'Reference line 1' });
    fireEvent.click(
      within(first).getByRole('button', { name: 'Remove reference line' }),
    );
    await settled();
    const [box] = captions();
    expect(box.value).toBe('Floor line');
    fireEvent.change(box, { target: { value: 'Floor' } });
    fireEvent.change(box, { target: { value: 'Floor line' } });
    expect(lines()).toHaveLength(1);
    expect(lines()[0].label).toBe(floor.label);
    // The deleted line's words are no key of this one's.
    fireEvent.change(box, { target: { value: 'Target line' } });
    expect(lines()[0].label).toBe('Target line');
  });
});

describe('the pieces a host’s own editor is built of (D2)', () => {
  it('keptKey gives the key back for the words shown now or the words it opened on', () => {
    const say = (value: string) => (value === 'k' ? 'Now' : value);
    expect(keptKey('Now', 'k', say)).toBe('k');
    expect(keptKey('Then', 'k', say, 'Then')).toBe('k');
    expect(keptKey('Other', 'k', say, 'Then')).toBe('Other');
  });

  it('useSaidText opens again on a value it did not give back itself', () => {
    const given: string[] = [];
    function Box({ value }: { value: string }) {
      const [held, setHeld] = useState(value);
      const [outside, setOutside] = useState(value);
      if (outside !== value) {
        setOutside(value);
        setHeld(value);
      }
      const text = useSaidText(held);
      return (
        <input
          aria-label="box"
          value={text.shown}
          onChange={event => {
            const next = text.back(event.target.value);
            given.push(next);
            setHeld(next);
          }}
        />
      );
    }
    const a = text('leaf.a');
    const b = text('leaf.b');
    const words = { 'leaf.a': 'Alpha', 'leaf.b': 'Beta' };
    const view = render(
      <MessagesProvider messages={words as never}>
        <Box value={a} />
      </MessagesProvider>,
    );
    const box = screen.getByRole('textbox') as HTMLInputElement;
    expect(box.value).toBe('Alpha');
    fireEvent.change(box, { target: { value: 'Alph' } });
    fireEvent.change(box, { target: { value: 'Alpha' } });
    expect(given).toEqual(['Alph', a]);
    view.rerender(
      <MessagesProvider messages={words as never}>
        <Box value={b} />
      </MessagesProvider>,
    );
    expect(box.value).toBe('Beta');
    fireEvent.change(box, { target: { value: 'Bet' } });
    fireEvent.change(box, { target: { value: 'Beta' } });
    expect(given.slice(2)).toEqual(['Bet', b]);
  });

  it('a name box left as it opened is no rename, whatever its words became since', () => {
    const commit = vi.fn();
    const cancel = vi.fn();
    const view = render(
      <RenameInput
        initial="Note"
        label="Name"
        onCommit={commit}
        onCancel={cancel}
      />,
    );
    view.rerender(
      <RenameInput
        initial="中:Note"
        label="Name"
        onCommit={commit}
        onCancel={cancel}
      />,
    );
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
    expect(commit).not.toHaveBeenCalled();
    expect(cancel).toHaveBeenCalledTimes(1);
  });

  it('a surface of a host’s own, given its engine, says keys in the engine’s starting words', () => {
    const engine = new ViewEngine({
      resources: [{ definition: keyedOrders(), source: testSource() }],
      store: new MemoryViewStore(),
      text: key => EN[key],
    });
    const key = engine.definitions.get('orders')!.title;
    function Title() {
      return <h1>{useSay()(key)}</h1>;
    }
    render(
      <ViewSurface engine={engine}>
        <Title />
      </ViewSurface>,
    );
    expect(screen.getByRole('heading').textContent).toBe('Orders');
    cleanup();
    render(
      <ViewSurface>
        <Title />
      </ViewSurface>,
    );
    // Without it, a key reads as itself.
    expect(screen.getByRole('heading').textContent).toBe('orders.0');
  });
});
