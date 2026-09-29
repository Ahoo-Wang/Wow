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

import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  systemInstanceId,
  text,
  type DashboardPanel,
  type DashboardViewConfig,
  type ViewDefinition,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import { RequestRunner } from '../src/runtime/requestRunner.js';
import {
  consoleIssueReporter,
  issueHint,
  type IssueConsole,
} from '../src/runtime/issueReport.js';
import {
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
    'board.main': 'Main board',
    'board.heading': 'Today',
  },
  zh: {
    'orders.title': '订单',
    'orders.all': '全部订单',
    'board.title': '概览',
    'board.main': '主看板',
    'board.heading': '今天',
  },
};

const say = (language: string) => (key: string) => WORDS[language][key];

/** Orders and a board, every label a key, each with a system view. */
function keyedDefinitions(): ViewDefinition[] {
  const heading: DashboardPanel = {
    id: 'heading',
    kind: 'heading',
    content: text('board.heading'),
    layout: { x: 0, y: 0, w: 24, h: 1 },
  };
  return [
    ordersDefinition({
      title: text('orders.title'),
      views: [{ id: 'all', title: text('orders.all'), config: recordConfig() }],
    }),
    overviewDefinition({
      title: text('board.title'),
      views: [
        {
          id: 'main',
          title: text('board.main'),
          config: dashboardConfig({ panels: [heading] }),
        },
      ],
    }),
  ];
}

function keyedEngine(store = new MemoryViewStore()) {
  const source = testSource();
  const [orders, board] = keyedDefinitions();
  const engine = new ViewEngine({
    resources: [{ definition: orders, source }, { definition: board }],
    store,
    environment: testEnvironment().environment,
    onIssue: () => {},
  });
  return { engine, store, source };
}

describe('one engine, every language (host-integration.md 3.1)', () => {
  it('keeps the keys and says them in the words set last', async () => {
    const { engine } = keyedEngine();
    expect(engine.definitions.get('orders')?.title).toBe('orders.title');

    engine.setText(say('en'));
    expect(engine.definitions.get('orders')?.title).toBe('Orders');
    const runtime = await engine.open(systemInstanceId('orders', 'all'));
    expect(runtime.getSnapshot().title).toBe('All orders');
    expect(runtime.definition.title).toBe('Orders');

    const told = vi.fn();
    runtime.subscribe(told);
    engine.setText(say('zh'));
    // The same runtime, told once, now in the other words: nothing reopened.
    expect(told).toHaveBeenCalled();
    expect(engine.openRuntimes()).toContain(runtime);
    expect(runtime.getSnapshot().title).toBe('全部订单');
    expect(runtime.definition.title).toBe('订单');
    expect(engine.definitions.get('overview')?.title).toBe('概览');
  });

  it('reads a snapshot once per language, so a store of it stays still', async () => {
    const { engine } = keyedEngine();
    engine.setText(say('en'));
    const runtime = await engine.open(systemInstanceId('orders', 'all'));
    const first = runtime.getSnapshot();
    expect(runtime.getSnapshot()).toBe(first);
    // What holds no key is itself: the rows are not copied.
    expect(first.result).toBe(
      (runtime as unknown as { store: { state: typeof first } }).store.state
        .result,
    );
    engine.setText(say('zh'));
    expect(runtime.getSnapshot()).not.toBe(first);
  });

  it('lists the declared views in the words in force', async () => {
    const { engine } = keyedEngine();
    engine.setText(say('zh'));
    const listing = await engine.list('orders');
    expect(listing.items.map(item => item.title)).toEqual(['全部订单']);
  });

  it('never lets a key reach the store: a copy is saved in the words the reader saw', async () => {
    const { engine, store } = keyedEngine();
    engine.setText(say('zh'));
    const create = vi.spyOn(store, 'create');
    const board = await engine.open(systemInstanceId('overview', 'main'));
    await engine.saveAs(board, { title: '我的概览', scope: 'personal' });
    const view = await engine.open(systemInstanceId('orders', 'all'));
    await engine.saveAs(view, {
      title: view.getSnapshot().title,
      scope: 'personal',
    });

    const saved = create.mock.calls.map(call => call[0]);
    expect(JSON.stringify(saved)).not.toContain('');
    const config = saved[0].config as DashboardViewConfig;
    expect(config.panels[0]).toMatchObject({ content: '今天' });
    expect(saved[1].title).toBe('全部订单');
  });

  it('checks the words it starts with, and a key they lack is warned of', () => {
    const found: string[] = [];
    const [orders] = keyedDefinitions();
    new ViewEngine({
      resources: [{ definition: orders, source: testSource() }],
      store: new MemoryViewStore(),
      text: key => (key === 'orders.title' ? 'Orders' : undefined),
      onIssue: issue =>
        found.push(`${issue.code}:${String(issue.params?.key)}`),
    });
    expect(found).toEqual(['definition.text.unknown:orders.all']);
  });
});

describe('resources (host-integration.md 4)', () => {
  it('pairs each definition with its source by the key it names, the first registered winning', async () => {
    const first = testSource();
    const second = testSource();
    const engine = new ViewEngine({
      resources: [
        { definition: ordersDefinition(), source: first },
        { definition: ordersDefinition({ id: 'again' }), source: second },
        { definition: overviewDefinition() },
      ],
      store: new MemoryViewStore(),
      environment: testEnvironment().environment,
    });
    expect(engine.resolveSource('orders')).toBe(first);
    expect(() => engine.resolveSource('elsewhere')).toThrow(
      expect.objectContaining({
        issue: expect.objectContaining({ code: 'runtime.source.unresolved' }),
      }),
    );
  });
});

function manyPanels(count: number): DashboardViewConfig {
  const panels: DashboardPanel[] = Array.from({ length: count }, (_, at) => ({
    id: `panel-${at}`,
    kind: 'view',
    instanceId: 'pending',
    bindings: [],
    layout: { x: 0, y: at * 4, w: 24, h: 4 },
  }));
  return dashboardConfig({ panels });
}

describe('the query queue makes room for a board (host-integration.md 4)', () => {
  it('opens a board larger than the host budget whole, and gives the room back', async () => {
    const answers: Array<() => void> = [];
    const source: ViewSource = testSource({
      paged: vi.fn(() => {
        const answer = deferred<{ total: number; list: never[] }>();
        answers.push(() => answer.resolve({ total: 0, list: [] }));
        return answer.promise;
      }),
    });
    const pending: ViewInstance = {
      id: 'pending',
      definitionId: 'orders',
      title: 'Pending',
      scope: 'shared',
      revision: 'r1',
      config: recordConfig(),
    };
    const store = new MemoryViewStore({ instances: [pending] });
    const engine = new ViewEngine({
      resources: [
        { definition: ordersDefinition(), source },
        { definition: overviewDefinition() },
      ],
      store,
      environment: testEnvironment().environment,
      limits: { maxConcurrentQueries: 1, maxQueuedQueries: 2 },
    });
    const created = await store.create(
      {
        definitionId: 'overview',
        title: 'Big',
        scope: 'personal',
        config: manyPanels(8),
      },
      { requestId: 'r' },
    );
    const board = await engine.open(created.id);
    if (!(board instanceof DashboardViewRuntime)) throw new Error('board');
    for (let round = 0; round < 12; round += 1) {
      answers.splice(0).forEach(answer => answer());
      await nextTask();
    }
    const errors = board
      .getSnapshot()
      .panels.map(panel => panel.runtime?.getSnapshot().query.error?.code);
    expect(errors.filter(Boolean)).toEqual([]);
    expect(vi.mocked(source.paged)).toHaveBeenCalledTimes(8);

    engine.close(board);
    const runner = (engine as unknown as { runner: RequestRunner }).runner;
    expect(runner.capacity).toBe(2);
  });

  it('holds room until it is released, once', () => {
    const runner = new RequestRunner();
    const base = runner.capacity;
    const release = runner.reserve(9);
    expect(runner.capacity).toBe(base + 9);
    release();
    release();
    expect(runner.capacity).toBe(base);
  });
});

describe('the development default of onIssue', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  function sink(): IssueConsole & { lines: string[] } {
    const lines: string[] = [];
    const write =
      (tag: string) =>
      (...data: unknown[]) =>
        lines.push(`${tag} ${String(data[0])}`);
    return {
      lines,
      groupCollapsed: write('group'),
      groupEnd: () => lines.push('end'),
      warn: write('warn'),
      error: write('error'),
      info: write('info'),
    };
  }

  it('groups one turn of findings by resource, each with how to fix it', async () => {
    const out = sink();
    const report = consoleIssueReporter(out);
    report(
      {
        code: 'definition.text.unknown',
        path: ['title'],
        severity: 'warning',
      },
      'orders',
    );
    report({
      code: 'capability.field.unknown',
      path: [],
      severity: 'note',
      params: { definition: 'orders' },
    });
    report({ code: 'view.change.notify-failed', path: [], severity: 'error' });
    expect(out.lines).toEqual([]);
    await nextTask();
    expect(out.lines).toEqual([
      'group [view-engine] orders: 2 findings',
      `warn definition.text.unknown at title — ${issueHint('definition.text.unknown')}`,
      `info capability.field.unknown — ${issueHint('capability.')}`,
      'end',
      'group [view-engine] engine: 1 finding',
      `error view.change.notify-failed — ${issueHint('view.change.notify-failed')}`,
      'end',
    ]);
  });

  it('writes to the console in development when the host gives no onIssue, and not otherwise', async () => {
    const group = vi
      .spyOn(console, 'groupCollapsed')
      .mockImplementation(() => {});
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(console, 'groupEnd').mockImplementation(() => {});
    const [orders] = keyedDefinitions();
    const register = () =>
      new ViewEngine({
        resources: [{ definition: orders, source: testSource() }],
        store: new MemoryViewStore(),
        text: () => undefined,
      });

    register();
    await nextTask();
    expect(group).not.toHaveBeenCalled();

    vi.stubEnv('NODE_ENV', 'development');
    register();
    await nextTask();
    expect(group).toHaveBeenCalledWith('[view-engine] orders: 2 findings');
  });
});

describe('a stored board that cannot hold room (review of #3761, 6)', () => {
  function boardStore(config: unknown) {
    const store = new MemoryViewStore({
      instances: [
        {
          id: 'broken',
          definitionId: 'overview',
          title: 'Broken',
          scope: 'personal',
          revision: 'r1',
          config: config as DashboardViewConfig,
        },
      ],
    });
    const engine = new ViewEngine({
      resources: [
        { definition: ordersDefinition(), source: testSource() },
        { definition: overviewDefinition() },
      ],
      store,
      environment: testEnvironment().environment,
      onIssue: () => {},
    });
    return engine;
  }

  it.each([
    [
      'without panels',
      (() => {
        const board: Partial<DashboardViewConfig> = dashboardConfig();
        delete board.panels;
        return board;
      })(),
    ],
    ['with panels that are not a list', { ...dashboardConfig(), panels: null }],
  ])(
    'opens one %s refused by its admission, not by a TypeError',
    async (_, config) => {
      const engine = boardStore(config);
      const board = await engine.open('broken');
      expect(board.getSnapshot().issues.map(found => found.code)).toContain(
        'dashboard.shape.invalid',
      );
    },
  );

  it('lets go of a board whose building fails after it was made', async () => {
    const engine = boardStore(dashboardConfig());
    vi.spyOn(RequestRunner.prototype, 'reserve').mockImplementation(() => {
      throw new Error('no room');
    });
    const dispose = vi.spyOn(DashboardViewRuntime.prototype, 'dispose');
    await expect(engine.open('broken')).rejects.toThrow('no room');
    expect(dispose).toHaveBeenCalledTimes(1);
    expect(engine.openRuntimes()).toEqual([]);
  });
});

describe('a data resource registered without a source (review of #3761, 10)', () => {
  function engineOver(issues: string[]) {
    const store = new MemoryViewStore({
      instances: [
        {
          id: 'pending',
          definitionId: 'orders',
          title: 'Pending',
          scope: 'shared',
          revision: 'r1',
          config: recordConfig(),
        },
        {
          id: 'shipped',
          definitionId: 'shipments',
          title: 'Shipped',
          scope: 'shared',
          revision: 'r1',
          config: recordConfig(),
        },
        {
          id: 'board',
          definitionId: 'overview',
          title: 'Board',
          scope: 'shared',
          revision: 'r1',
          config: dashboardConfig({
            panels: ['pending', 'shipped'].map((instanceId, at) => ({
              id: instanceId,
              kind: 'view',
              instanceId,
              bindings: [],
              layout: { x: at * 12, y: 0, w: 12, h: 4 },
            })) as DashboardPanel[],
          }),
        },
      ],
    });
    const source = testSource();
    const engine = new ViewEngine({
      resources: [
        { definition: ordersDefinition() },
        {
          definition: ordersDefinition({ id: 'shipments', source: 'ships' }),
          source,
        },
        { definition: overviewDefinition() },
      ],
      store,
      environment: testEnvironment().environment,
      onIssue: found =>
        issues.push(`${found.code}:${String(found.params?.source)}`),
    });
    return { engine, source };
  }

  it('is reported at registration, on the definition', () => {
    const issues: string[] = [];
    const { engine } = engineOver(issues);
    expect(issues).toContain('definition.source.unregistered:orders');
    expect(engine.definitionIssues('orders')).toContainEqual(
      expect.objectContaining({
        code: 'definition.source.unregistered',
        path: ['source'],
      }),
    );
    expect(engine.definitionIssues('shipments')).toEqual([]);
  });

  it('puts out only the panel over it: the board opens, and the other panel runs', async () => {
    const { engine, source } = engineOver([]);
    const board = await engine.open('board');
    if (!(board instanceof DashboardViewRuntime)) throw new Error('board');
    await nextTask();
    const panels = board.getSnapshot().panels;
    expect(panels.find(panel => panel.id === 'shipped')?.runtime).toBeTruthy();
    expect(panels.find(panel => panel.id === 'pending')?.runtime).toBeFalsy();
    expect(source.paged).toHaveBeenCalled();
  });
});
