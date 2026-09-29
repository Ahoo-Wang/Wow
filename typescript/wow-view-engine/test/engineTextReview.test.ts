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
 * Render-time words (host-integration.md 3.1) as #3761's review held them:
 * a baseline that stays right across languages, a board's own fields said,
 * only a definition's keys ever said — never a reader's title or a row —
 * a key without words reported whichever words are in force, and the
 * words an engine started with kept under a Provider that lacks one.
 */

import { describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  systemInstanceId,
  text,
  type DashboardPanel,
  type DashboardViewConfig,
  type Issue,
  type RecordViewConfig,
  type ViewSource,
} from '../src/index.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import {
  analysisConfig,
  dashboardConfig,
  deferred,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

const WORDS: Record<string, Record<string, string>> = {
  en: {
    'orders.title': 'Orders',
    'orders.all': 'All orders',
    'board.title': 'Overview',
    'board.today': 'Today',
    'board.region': 'Region',
  },
  zh: {
    'orders.title': '订单',
    'orders.all': '全部订单',
    'board.title': '概览',
    'board.today': '今天',
    'board.region': '区域',
  },
};
const say = (language: string) => (key: string) => WORDS[language][key];

const HEADING: DashboardPanel = {
  id: 'heading',
  kind: 'heading',
  content: text('board.today'),
  layout: { x: 0, y: 0, w: 24, h: 1 },
};

function keyedBoard(): DashboardViewConfig {
  return dashboardConfig({
    fields: [{ name: 'region', label: text('board.region'), kind: 'string' }],
    panels: [HEADING],
  });
}

function engineOf(
  options: {
    store?: MemoryViewStore;
    source?: ViewSource;
    text?: (key: string) => string | undefined;
    issues?: Issue[];
  } = {},
) {
  const issues = options.issues ?? [];
  const engine = new ViewEngine({
    resources: [
      {
        definition: ordersDefinition({
          title: text('orders.title'),
          views: [
            { id: 'all', title: text('orders.all'), config: recordConfig() },
          ],
        }),
        source: options.source ?? testSource(),
      },
      {
        definition: overviewDefinition({
          title: text('board.title'),
          views: [{ id: 'main', title: 'Main', config: keyedBoard() }],
        }),
      },
    ],
    store: options.store ?? new MemoryViewStore(),
    environment: testEnvironment().environment,
    ...(options.text ? { text: options.text } : {}),
    onIssue: found => issues.push(found),
  });
  return { engine, issues };
}

async function savedBoard(engine: ViewEngine): Promise<DashboardViewRuntime> {
  const board = engine.create('overview', {
    title: 'Mine',
    scope: 'personal',
    config: keyedBoard(),
  });
  if (!(board instanceof DashboardViewRuntime)) throw new Error('board');
  await engine.save(board);
  board.setBuilding(true);
  return board;
}

const headingOf = (board: DashboardViewRuntime) =>
  board.getSnapshot().draft.panels.find(panel => panel.id === 'heading');

describe('a saved baseline in every language (review of #3761, 7)', () => {
  it('is clean after the words change, and moved and moved back is clean again', async () => {
    const { engine } = engineOf();
    engine.setText(say('zh'));
    const board = await savedBoard(engine);
    expect(board.getSnapshot().dirty).toBe(false);

    engine.setText(say('en'));
    expect(board.getSnapshot().dirty).toBe(false);
    board.place('heading', { x: 0, y: 0, w: 12, h: 1 });
    expect(board.getSnapshot().dirty).toBe(true);
    board.place('heading', HEADING.layout);
    expect(board.getSnapshot().dirty).toBe(false);
  });

  it('reverts to the saved board in the words in force now', async () => {
    const { engine } = engineOf();
    engine.setText(say('zh'));
    const board = await savedBoard(engine);
    engine.setText(say('en'));
    board.place('heading', { x: 0, y: 0, w: 12, h: 1 });
    board.revert();
    expect(headingOf(board)).toMatchObject({
      content: 'Today',
      layout: HEADING.layout,
    });
  });
});

describe('a board’s own fields (review of #3761, 8)', () => {
  it('are said in the words in force', async () => {
    const { engine } = engineOf();
    engine.setText(say('zh'));
    const board = await savedBoard(engine);
    expect(board.fields.map(field => field.label)).toEqual(['区域']);
    engine.setText(say('en'));
    expect(board.fields.map(field => field.label)).toEqual(['Region']);
  });
});

describe('only a definition’s keys are said (review of #3761, 9)', () => {
  const forged = `mine`;

  it('leaves a reader’s title and a row’s cell as they came, markers and all', async () => {
    const mine: RecordViewConfig = recordConfig();
    const store = new MemoryViewStore({
      instances: [
        {
          id: 'mine',
          definitionId: 'orders',
          // What a reader typed: even a definition's own key is their words.
          title: text('orders.title'),
          scope: 'personal',
          revision: 'r1',
          config: mine,
        },
      ],
    });
    const source = testSource({
      paged: vi.fn(() =>
        Promise.resolve({
          total: 1,
          list: [{ id: text('orders.title'), warehouse: forged, amount: 1 }],
        }),
      ),
    });
    const { engine } = engineOf({ store, source });
    engine.setText(say('en'));

    const listing = await engine.list('orders');
    expect(listing.items.map(item => item.title)).toEqual([
      'All orders',
      text('orders.title'),
    ]);
    const view = await engine.open('mine');
    await nextTask();
    const state = view.getSnapshot();
    expect(state.title).toBe(text('orders.title'));
    const rows = JSON.stringify(state.result?.data);
    expect(rows).toContain(JSON.stringify(forged).slice(1, -1));
    expect(rows).toContain(JSON.stringify(text('orders.title')).slice(1, -1));

    const save = vi.spyOn(store, 'rename');
    await engine.rename('mine', state.title);
    expect(save.mock.calls[0]).toContain(text('orders.title'));
  });

  it('says nothing for an engine whose definitions hold no key', async () => {
    const store = new MemoryViewStore({
      instances: [
        {
          id: 'mine',
          definitionId: 'orders',
          title: forged,
          scope: 'personal',
          revision: 'r1',
          config: recordConfig(),
        },
      ],
    });
    const engine = new ViewEngine({
      resources: [{ definition: ordersDefinition(), source: testSource() }],
      store,
      environment: testEnvironment().environment,
    });
    engine.setText(() => 'said');
    const view = await engine.open('mine');
    expect(view.getSnapshot().title).toBe(forged);
  });
});

describe('a key the words in force lack (review of #3761, 11)', () => {
  it('is reported when the words are set, where it sits', () => {
    const { engine, issues } = engineOf();
    engine.setText(key => (key === 'orders.all' ? undefined : WORDS.en[key]));
    expect(
      issues
        .filter(found => found.code === 'definition.text.unknown')
        .map(found => found.params?.key),
    ).toEqual(['orders.all']);
  });
});

describe('the words set, checked on their own (second review of #3761, 4)', () => {
  const lacking = (language: string) => (key: string) =>
    key === 'orders.all' ? undefined : WORDS[language][key];

  it('says the starting words filled a gap, rather than nothing', () => {
    const issues: Issue[] = [];
    const { engine } = engineOf({ text: say('en'), issues });
    issues.length = 0;
    engine.setText(lacking('zh'));
    expect(
      issues.map(found => [found.code, found.params?.key, found.severity]),
    ).toEqual([['definition.text.fallback', 'orders.all', 'warning']]);
  });

  it('says a key once per language, however often the language changes', () => {
    const { engine, issues } = engineOf();
    engine.setText(lacking('zh'), 'zh-CN');
    engine.setText(say('en'), 'en');
    engine.setText(lacking('zh'), 'zh-CN');
    engine.setText(lacking('en'), 'en');
    expect(
      issues
        .filter(found => found.code.startsWith('definition.text.'))
        .map(found => found.params?.key),
    ).toEqual(['orders.all', 'orders.all']);
  });

  it('says it again once a language that had the words lacks them again, and for a new language (third review of #3761, 4)', () => {
    const { engine, issues } = engineOf();
    const told = () =>
      issues
        .filter(found => found.code.startsWith('definition.text.'))
        .map(found => found.params?.key);
    engine.setText(lacking('zh'), 'zh-CN');
    expect(told()).toEqual(['orders.all']);
    engine.setText(say('zh'), 'zh-CN');
    engine.setText(lacking('zh'), 'zh-CN');
    expect(told()).toEqual(['orders.all', 'orders.all']);
    engine.setText(lacking('en'), 'fr');
    expect(told()).toEqual(['orders.all', 'orders.all', 'orders.all']);
  });

  it('says nothing of a fallback when the words set give no definition’s key at all (third review of #3761, 3)', () => {
    const issues: Issue[] = [];
    const { engine } = engineOf({ text: say('en'), issues });
    issues.length = 0;
    // What a Provider with no words of its own for the definitions says
    // them in: the engine's own catalogue, which holds none of their keys.
    engine.setText(key => (key === 'label.filter.apply' ? 'Apply' : undefined));
    expect(issues).toEqual([]);
    expect(engine.definitions.get('orders')?.title).toBe('Orders');
  });
});

describe('the words an engine started with (review of #3761, 12)', () => {
  it('say what the words set later lack', () => {
    const { engine } = engineOf({ text: say('zh') });
    engine.setText(key => (key === 'orders.title' ? 'Orders' : undefined));
    expect(engine.definitions.get('orders')?.title).toBe('Orders');
    expect(engine.definitions.get('overview')?.title).toBe('概览');
  });
});

describe('a baseline keyed against what the save sent (second review of #3761, 2)', () => {
  function gated() {
    const store = new MemoryViewStore();
    const create = store.create.bind(store);
    const gate = deferred<void>();
    vi.spyOn(store, 'create').mockImplementation(async (...args) => {
      await gate.promise;
      return create(...args);
    });
    return { store, gate };
  }

  function unsaved(engine: ViewEngine): DashboardViewRuntime {
    const board = engine.create('overview', {
      title: 'Mine',
      scope: 'personal',
      config: keyedBoard(),
    });
    if (!(board instanceof DashboardViewRuntime)) throw new Error('board');
    return board;
  }

  it('holds when the draft moves while the save is in flight', async () => {
    const { store, gate } = gated();
    const { engine } = engineOf({ store });
    engine.setText(say('zh'));
    const board = unsaved(engine);
    const saving = engine.save(board);
    board.setBuilding(true);
    board.place('heading', { x: 0, y: 0, w: 12, h: 1 });
    gate.resolve();
    await saving;
    expect(board.getSnapshot().dirty).toBe(true);

    engine.setText(say('en'));
    board.place('heading', HEADING.layout);
    expect(board.getSnapshot().dirty).toBe(false);
    board.place('heading', { x: 0, y: 0, w: 12, h: 1 });
    board.revert();
    expect(headingOf(board)).toMatchObject({ content: 'Today' });
  });

  it('holds when the words change while the save is in flight', async () => {
    const { store, gate } = gated();
    const { engine } = engineOf({ store });
    engine.setText(say('zh'));
    const board = unsaved(engine);
    const saving = engine.save(board);
    engine.setText(say('en'));
    gate.resolve();
    await saving;
    expect(board.getSnapshot().dirty).toBe(false);
    expect(headingOf(board)).toMatchObject({ content: 'Today' });
    const [stored] = await store.list('overview');
    const kept = await store.get(stored.id);
    expect(JSON.stringify(kept.config)).toContain('今天');
  });

  it('holds when the store adds to what it keeps', async () => {
    const store = new MemoryViewStore();
    const create = store.create.bind(store);
    vi.spyOn(store, 'create').mockImplementation((input, ...rest) =>
      create(
        { ...input, config: { ...input.config, keptBy: 'store' } as never },
        ...rest,
      ),
    );
    const { engine } = engineOf({ store });
    engine.setText(say('zh'));
    const board = unsaved(engine);
    await engine.save(board);
    board.setBuilding(true);
    engine.setText(say('en'));
    board.place('heading', { x: 0, y: 0, w: 12, h: 1 });
    board.revert();
    expect(headingOf(board)).toMatchObject({ content: 'Today' });
  });
});

describe('a title nobody stored yet (second review of #3761, 5)', () => {
  it('is said on a board’s own panel, which its definition wrote', async () => {
    const owned = {
      id: 'owned',
      kind: 'view',
      title: text('board.today'),
      owned: { definitionId: 'orders', config: analysisConfig() },
      bindings: [],
      layout: { x: 0, y: 1, w: 12, h: 4 },
    } as DashboardPanel;
    const engine = new ViewEngine({
      resources: [
        { definition: ordersDefinition(), source: testSource() },
        {
          definition: overviewDefinition({
            views: [
              {
                id: 'main',
                title: text('board.title'),
                config: dashboardConfig({ panels: [HEADING, owned] }),
              },
            ],
          }),
        },
      ],
      store: new MemoryViewStore(),
      environment: testEnvironment().environment,
      onIssue: () => {},
    });
    engine.setText(say('en'));
    const board = await engine.open(systemInstanceId('overview', 'main'));
    if (!(board instanceof DashboardViewRuntime)) throw new Error('board');
    await nextTask();
    expect(board.panelRuntime('owned')?.getSnapshot().title).toBe('Today');
  });

  it('is said when it is a definition’s key', () => {
    const { engine } = engineOf();
    engine.setText(say('en'));
    const view = engine.create('orders', {
      title: text('orders.all'),
      scope: 'personal',
      config: recordConfig(),
    });
    expect(view.getSnapshot().title).toBe('All orders');
  });
});
