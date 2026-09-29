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
  renderHook,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  systemInstanceId,
  type DashboardPanel,
  type Issue,
} from '../src/index.js';
import {
  DashboardWorkbench,
  EmbeddedDashboard,
  ViewSurface,
  zhCN,
} from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  resourcesOf,
  testSource,
} from './fixtures.js';
import { useViewMessages } from '../src/ui/MessagesProvider.js';
import {
  boardErrorTitle,
  panelsToFix,
  saidByBoard,
} from '../src/ui/panelsToFix.js';

afterEach(cleanup);

const error = (path: Issue['path']): Issue => ({
  code: 'dashboard.panel.failed',
  severity: 'error',
  path,
});

/**
 * The red line over a board whose only trouble is its panels talks about
 * the panels (todo 5, 2026-09-28): they are out and the rest draws, so
 * 「这个仪表盘要先修正才能运行」 said something untrue of the board.
 */
describe('the error line over a board', () => {
  it('counts the panels when every error is a panel’s own', () => {
    expect(
      panelsToFix([
        error(['panels', 0, 'view']),
        error(['panels', 0, 'bindings', 1]),
        error(['panels', 2]),
      ]),
    ).toBe(2);
    // A board a definition declares: its panels are under its view, and
    // the board open says the same panel again at its own path.
    expect(
      panelsToFix(
        [
          error(['views', 1, 'config', 'panels', 1, 'view']),
          error(['views', 1, 'config', 'panels', 2, 'bindings', 0]),
          error(['panels', 1]),
        ],
        1,
      ),
    ).toBe(2);
    // A warning is no error.
    expect(
      panelsToFix([
        error(['panels', 0]),
        { ...error(['fields', 0]), severity: 'warning' },
      ]),
    ).toBe(1);
  });

  it('counts none when any error is the board’s own', () => {
    expect(panelsToFix([])).toBe(0);
    expect(panelsToFix([error(['panels', 0]), error(['fields', 0])])).toBe(0);
    // "Too many panels" belongs to no one panel.
    expect(panelsToFix([error(['panels'])])).toBe(0);
    expect(panelsToFix([error(['views', 0, 'config', 'fields', 0])], 0)).toBe(
      0,
    );
  });

  it('counts only the open board’s panels, never a sibling board’s', () => {
    // The definition declares two boards; the one open is the second. The
    // first's broken panel 3 is no panel of this board — not counted, not
    // taken for panel 3 here, and no reason this one cannot run.
    const sibling = error(['views', 0, 'config', 'panels', 3, 'view']);
    expect(panelsToFix([error(['panels', 1]), sibling], 1)).toBe(1);
    expect(panelsToFix([error(['panels', 3]), sibling], 1)).toBe(1);
    expect(
      panelsToFix([error(['panels', 1]), error(['panels', 2]), sibling], 1),
    ).toBe(2);
    // Nor when the board open is not one the definition declares.
    expect(panelsToFix([error(['panels', 1]), sibling])).toBe(1);
    expect(
      panelsToFix(
        [error(['panels', 1]), error(['views', 1, 'config', 'panels', 1])],
        1,
      ),
    ).toBe(1);
    // The open board's own fields are the board's.
    expect(
      panelsToFix(
        [error(['panels', 1]), error(['views', 1, 'config', 'fields', 0])],
        1,
      ),
    ).toBe(0);
  });

  it('leaves out of the definition’s findings only what the open board says itself', () => {
    const own = error(['views', 1, 'config', 'panels', 0, 'view']);
    expect(saidByBoard(own, 1)).toBe(true);
    expect(saidByBoard(own, 0)).toBe(false);
    expect(saidByBoard(own, null)).toBe(false);
    expect(saidByBoard({ ...own, severity: 'warning' }, 1)).toBe(false);
    expect(saidByBoard(error(['views', 1, 'config', 'fields', 0]), 1)).toBe(
      false,
    );
  });

  it('heads the line with the panels, or with the board, in both catalogues', () => {
    const en = renderHook(() => useViewMessages()).result.current;
    const zh = renderHook(() => useViewMessages(), {
      wrapper: ({ children }) => (
        <ViewSurface messages={zhCN} locale="zh-CN">
          {children}
        </ViewSurface>
      ),
    }).result.current;
    const two = [error(['panels', 0]), error(['panels', 1])];
    const one = [error(['panels', 0]), error(['panels', 0, 'view'])];
    const board = [error(['panels', 0]), error(['fields', 0])];

    expect(boardErrorTitle(two, zh)).toBe('有 2 个面板要先修正才能显示');
    expect(boardErrorTitle(one, zh)).toBe('有 1 个面板要先修正才能显示');
    expect(boardErrorTitle(board, zh)).toBe('这个仪表盘要先修正才能运行');
    expect(boardErrorTitle(two, en)).toBe(
      '2 panels need fixing before they can show',
    );
    expect(boardErrorTitle(one, en)).toBe(
      '1 panel needs fixing before it can show',
    );
    expect(boardErrorTitle(board, en)).toBe(
      'This dashboard needs fixing before it runs',
    );
  });
});

/**
 * A definition declaring two boards, each with panels written wrong: the
 * one open (`second`) has two, its sibling (`first`) one. The definition's
 * check finds all three at `['views', n, 'config', 'panels', m, …]`; the
 * board open finds its own two again at `['panels', m, …]` and names them.
 */
function brokenBoards(): ViewEngine {
  const wrongOperator = (
    id: string,
    title: string,
    y: number,
    field = 'status',
  ) =>
    ({
      id,
      kind: 'view',
      title,
      owned: {
        definitionId: 'orders',
        config: analysisConfig({
          filter: {
            op: 'and',
            children: [{ field, operator: 'EQ', value: 'DONE' }],
          },
        }),
      },
      bindings: [],
      layout: { x: 0, y, w: 12, h: 4 },
    }) as DashboardPanel;
  const overview = overviewDefinition({
    views: [
      {
        id: 'first',
        title: 'First board',
        config: dashboardConfig({
          panels: [wrongOperator('sibling', 'Sibling panel', 0, 'warehouse')],
        }),
      },
      {
        id: 'second',
        title: 'Second board',
        config: dashboardConfig({
          panels: [
            wrongOperator('one', 'Panel one', 0),
            wrongOperator('two', 'Panel two', 4),
          ],
        }),
      },
    ],
  });
  // 状态 is an enum, which takes 属于 and never 等于.
  const orders = ordersDefinition();
  orders.fields = orders.fields.map(field =>
    field.name === 'status' || field.name === 'warehouse'
      ? {
          name: field.name,
          label: field.label,
          kind: 'enum',
          options: [{ value: 'DONE', label: 'Done' }],
        }
      : field,
  );
  return new ViewEngine({
    resources: resourcesOf([orders, overview], () => testSource()),
    store: new MemoryViewStore({ instances: [] }),
  });
}

/** The error strip, opened, as a reader reads it. */
async function openedStrip() {
  const strip = await waitFor(() => {
    const found = document.querySelector<HTMLElement>(
      '[data-slot="status-strip"][data-tone="error"]',
    );
    expect(found).not.toBeNull();
    return found!;
  });
  const fold = within(strip).getByRole('button', { name: /^Show \d+$/ });
  const shown = Number(/\d+/.exec(fold.textContent ?? '')![0]);
  fireEvent.click(fold);
  const items = [...strip.querySelectorAll('li')].map(
    item => item.textContent ?? '',
  );
  return {
    title: strip.querySelector('[data-slot="alert-title"]')!.textContent,
    shown,
    items,
  };
}

describe('the error line over a declared board, drawn', () => {
  it('says each of the open board’s panel findings once, by name, and counts only its panels', async () => {
    render(
      <ViewSurface>
        <DashboardWorkbench
          engine={brokenBoards()}
          definitionId="overview"
          instanceId={systemInstanceId('overview', 'second')}
        />
      </ViewSurface>,
    );
    await screen.findByRole('heading', { name: 'Second board' });
    const { title, shown, items } = await openedStrip();

    // Two panels of this board — the sibling's is no panel of it.
    expect(title).toBe('2 panels need fixing before they can show');
    // One entry per finding, and the fold says as many: the two panels
    // here, each once, by its name and its field's and operator's names —
    // never the config's `status` and `EQ` — and the sibling board's,
    // which only the definition says.
    expect(items).toEqual([
      'Panel one: Status does not support is.',
      'Panel two: Status does not support is.',
      'warehouse does not support EQ.',
    ]);
    expect(shown).toBe(items.length);
  });

  it('keeps an embedded board’s panel findings in their frames, said once', async () => {
    render(
      <ViewSurface>
        <EmbeddedDashboard
          engine={brokenBoards()}
          instanceId={systemInstanceId('overview', 'second')}
        />
      </ViewSurface>,
    );
    // An embed says a panel's findings in the panel alone (`boardFindings`)
    // and no definition's: nothing above the board says them again.
    await waitFor(() =>
      expect(screen.getAllByText('This panel cannot be used')).toHaveLength(2),
    );
    expect(screen.getAllByText('Status does not support is.')).toHaveLength(2);
    expect(
      document.querySelector('[data-slot="status-strip"][data-tone="error"]'),
    ).toBeNull();
  });
});
