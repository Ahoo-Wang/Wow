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

import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type DataViewConfig,
  type FilterNode,
  type FilterTree,
  type ViewConfig,
  type ViewInstance,
  type ViewPermissions,
} from '../src/index.js';
import { useWorkbench, type WorkbenchOptions } from '../src/react/index.js';
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';
import { mine } from './fixtures/ui.js';

afterEach(cleanup);

const chart = {
  id: 'orders-chart',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'shared',
  revision: '1',
  config: analysisConfig({
    filter: {
      op: 'and',
      children: [{ field: 'status', operator: 'EQ', value: 'PENDING' }],
    },
  }),
} satisfies ViewInstance;

/** The open view's draft; every view here is a record view or an analysis. */
function draftOf(state: { draft: ViewConfig } | null | undefined) {
  return state?.draft as DataViewConfig | undefined;
}

/** The row the user pressed: the warehouse bar. */
const ROW: FilterNode[] = [{ field: 'warehouse', operator: 'EQ', value: 'CN' }];

/** What the caller names the records: `/ui` says it, this layer carries it. */
const TITLE = 'Orders · Warehouse is CN';

const readOnly = (): ViewPermissions => ({
  createPersonal: false,
  createShared: false,
  reorder: false,
  setDefault: false,
  instance: () => ({ save: false, rename: false, delete: false }),
});

function setup(
  options: Partial<WorkbenchOptions> = {},
  permissions?: () => ViewPermissions,
) {
  // One source for the whole engine, so its calls count across views.
  const source = testSource();
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances: [mine, chart], permissions }),
    resolveSource: () => source,
  });
  const rendered = renderHook(() =>
    useWorkbench(engine, 'orders', {
      kinds: ['record', 'analysis'],
      instanceId: 'orders-chart',
      newView: { title: 'Untitled view' },
      ...options,
    }),
  );
  return { engine, source, ...rendered };
}

describe('drilling from an analysis view', () => {
  it('opens the records behind a row as a held record view, with its origin', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.runtime?.kind).toBe('analysis'));
    expect(result.current.canDrill).toBe(true);
    const origin = result.current.runtime!;

    act(() => {
      result.current.drill(ROW, TITLE);
    });

    // A record view, unsaved, under the origin's conditions plus the row's,
    // flattened into one simple group.
    expect(result.current.runtime?.kind).toBe('record');
    expect(result.current.state?.saved).toBeNull();
    // Named as the caller named it, not as a view made from nothing.
    expect(result.current.state?.title).toBe(TITLE);
    expect(draftOf(result.current.state)?.filter).toEqual({
      op: 'and',
      children: [{ field: 'status', operator: 'EQ', value: 'PENDING' }, ...ROW],
    });
    expect(draftOf(result.current.state)?.filterMode).toBe('simple');
    // And the workbench knows where it came from.
    expect(result.current.held?.origin).toMatchObject({
      runtime: origin,
      title: 'By warehouse',
      conditions: ROW,
    });
    // The origin stays open underneath, with the result it was drilled on.
    expect(origin.disposed).toBe(false);
  });

  it('goes back to the result it was drilled on, without running it again', async () => {
    const { result, source } = setup();
    await waitFor(() => expect(result.current.runtime?.kind).toBe('analysis'));
    await waitFor(() =>
      expect(result.current.state?.query.status).toBe('success'),
    );
    const origin = result.current.runtime!;
    const ran = vi.mocked(source.aggregate).mock.calls.length;

    act(() => {
      result.current.drill(ROW, TITLE);
    });
    const drilled = result.current.runtime!;
    act(() => {
      result.current.back();
    });

    // The same runtime, the same result, and not one more query.
    expect(result.current.runtime).toBe(origin);
    expect(result.current.held).toBeNull();
    expect(vi.mocked(source.aggregate).mock.calls.length).toBe(ran);
    // The drilled view is let go: it is nobody's to come back to.
    expect(drilled.disposed).toBe(true);
  });

  it('inherits the scope the origin ran under (H4)', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.runtime?.kind).toBe('analysis'));
    const scope: FilterTree = {
      op: 'and',
      children: [{ field: 'warehouse', operator: 'NE', value: 'XX' }],
    };
    act(() => {
      result.current.runtime!.setScopeFilter(scope);
    });

    act(() => {
      result.current.drill(ROW, TITLE);
    });

    expect(result.current.runtime?.scopeFilter).toEqual(scope);
  });

  it('needs no permission: a reader drills too (H1)', async () => {
    const { result } = setup({}, readOnly);
    await waitFor(() => expect(result.current.runtime?.kind).toBe('analysis'));
    // Nothing may be made from nothing here...
    expect(result.current.creatable).toEqual([]);
    // ...but the records behind a row are only looked at.
    expect(result.current.canDrill).toBe(true);
    act(() => {
      result.current.drill(ROW, TITLE);
    });
    expect(result.current.runtime?.kind).toBe('record');
  });

  it('is not on offer from a record view or without record views', async () => {
    const fromRecord = setup({ instanceId: 'orders-1' });
    await waitFor(() =>
      expect(fromRecord.result.current.runtime?.kind).toBe('record'),
    );
    expect(fromRecord.result.current.canDrill).toBe(false);

    const analysisOnly = setup({ kinds: ['analysis'] });
    await waitFor(() =>
      expect(analysisOnly.result.current.runtime?.kind).toBe('analysis'),
    );
    expect(analysisOnly.result.current.canDrill).toBe(false);

    act(() => {
      analysisOnly.result.current.drill(ROW, TITLE);
    });
    expect(analysisOnly.result.current.runtime?.kind).toBe('analysis');

    // The name is the caller's, so a workbench that offers no view made from
    // nothing still drills.
    const unnamed = setup({ newView: undefined });
    await waitFor(() =>
      expect(unnamed.result.current.runtime?.kind).toBe('analysis'),
    );
    expect(unnamed.result.current.canDrill).toBe(true);
  });

  it('hands the target to a host that takes drilling over, and holds nothing (H5)', async () => {
    const onDrilldown = vi.fn();
    const { result } = setup({ onDrilldown });
    await waitFor(() => expect(result.current.runtime?.kind).toBe('analysis'));

    act(() => {
      result.current.drill(ROW, TITLE);
    });

    expect(onDrilldown).toHaveBeenCalledTimes(1);
    expect(onDrilldown.mock.calls[0][0]).toMatchObject({
      definitionId: 'orders',
      origin: { title: 'By warehouse', conditions: ROW },
      title: TITLE,
      config: { kind: 'record' },
      scopeFilter: null,
    });
    expect(result.current.runtime?.kind).toBe('analysis');
    expect(result.current.held).toBeNull();
  });

  it('keeps an unsaved analysis view it drilled out of, and puts it back', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.runtime?.kind).toBe('analysis'));
    act(() => {
      result.current.create('analysis');
    });
    const unsaved = result.current.runtime!;
    expect(result.current.state?.saved).toBeNull();

    act(() => {
      result.current.drill(ROW, TITLE);
    });
    expect(result.current.runtime?.kind).toBe('record');
    expect(unsaved.disposed).toBe(false);

    act(() => {
      result.current.back();
    });
    expect(result.current.runtime).toBe(unsaved);
    expect(result.current.held?.origin).toBeNull();
  });

  it('asks before leaving a drilled view the user shaped, and not an untouched one', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.runtime?.kind).toBe('analysis'));
    act(() => {
      result.current.drill(ROW, TITLE);
    });
    act(() => {
      result.current.back();
    });
    expect(result.current.leave.asking).toBe(false);
    expect(result.current.runtime?.kind).toBe('analysis');

    act(() => {
      result.current.drill(ROW, TITLE);
    });
    act(() => {
      result.current.runtime!.edit({ pageSize: 7 });
    });
    act(() => {
      result.current.back();
    });
    expect(result.current.leave.asking).toBe(true);
    expect(result.current.runtime?.kind).toBe('record');
    act(() => {
      result.current.leave.confirm();
    });
    expect(result.current.runtime?.kind).toBe('analysis');
  });

  it('lets both runtimes go on unmount', async () => {
    const { result, unmount } = setup();
    await waitFor(() => expect(result.current.runtime?.kind).toBe('analysis'));
    act(() => {
      result.current.create('analysis');
    });
    const unsaved = result.current.runtime!;
    act(() => {
      result.current.drill(ROW, TITLE);
    });
    const drilled = result.current.runtime!;

    unmount();

    expect(unsaved.disposed).toBe(true);
    expect(drilled.disposed).toBe(true);
  });
});

describe('following a group into a view of its own', () => {
  /** The origin's config, narrowed to the row, as the result would pass it. */
  const focused = () => ({
    ...chart.config,
    filter: {
      op: 'and' as const,
      children: [
        { field: 'status', operator: 'EQ' as const, value: 'PENDING' },
        ...ROW,
      ],
    },
  });
  const NARROWED = 'By warehouse · Warehouse is CN';

  it('opens the config as a held view beside its origin, named as asked', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.runtime?.kind).toBe('analysis'));
    const origin = result.current.runtime!;

    act(() => {
      result.current.follow(focused(), NARROWED, ROW);
    });

    // A new analysis view, unsaved, not the saved one written over.
    expect(result.current.runtime).not.toBe(origin);
    expect(result.current.runtime?.kind).toBe('analysis');
    expect(result.current.state?.saved).toBeNull();
    expect(result.current.state?.title).toBe(NARROWED);
    expect(draftOf(result.current.state)?.filter).toEqual(focused().filter);
    expect(result.current.held?.origin).toMatchObject({
      runtime: origin,
      title: 'By warehouse',
      conditions: ROW,
    });
    // The saved view is as it was: nothing to save, nothing to revert.
    expect(origin.getSnapshot().dirty).toBe(false);
    expect(origin.disposed).toBe(false);
  });

  it('goes back to the result it came from, without running it again', async () => {
    const { result, source } = setup();
    await waitFor(() =>
      expect(result.current.state?.query.status).toBe('success'),
    );
    const origin = result.current.runtime!;
    const before = origin.getSnapshot().result;

    act(() => {
      result.current.follow(focused(), NARROWED, ROW);
    });
    // The narrowed question runs, once, as its own view.
    await waitFor(() =>
      expect(result.current.state?.query.status).toBe('success'),
    );
    const ran = vi.mocked(source.aggregate).mock.calls.length;
    const followed = result.current.runtime!;

    act(() => {
      result.current.back();
    });

    expect(result.current.runtime).toBe(origin);
    expect(result.current.held).toBeNull();
    expect(origin.getSnapshot().result).toBe(before);
    expect(vi.mocked(source.aggregate).mock.calls.length).toBe(ran);
    expect(followed.disposed).toBe(true);
  });

  it('chains: records drilled from a followed group go back to it, and it to the saved view', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.runtime?.kind).toBe('analysis'));
    const saved = result.current.runtime!;
    act(() => {
      result.current.follow(focused(), NARROWED, ROW);
    });
    const followed = result.current.runtime!;

    act(() => {
      result.current.drill(ROW, TITLE);
    });
    expect(result.current.runtime?.kind).toBe('record');
    expect(result.current.held?.origin?.title).toBe(NARROWED);

    act(() => {
      result.current.back();
    });
    expect(result.current.runtime).toBe(followed);
    expect(followed.disposed).toBe(false);
    act(() => {
      result.current.back();
    });
    expect(result.current.runtime).toBe(saved);
  });

  it('chains a split after a focus, and steps back one question at a time', async () => {
    const { result, source } = setup();
    await waitFor(() =>
      expect(result.current.state?.query.status).toBe('success'),
    );
    const saved = result.current.runtime!;
    act(() => {
      result.current.follow(focused(), NARROWED, ROW);
    });
    await waitFor(() =>
      expect(result.current.state?.query.status).toBe('success'),
    );
    const narrowed = result.current.runtime!;
    const narrowedResult = narrowed.getSnapshot().result;

    // A second follow-up of the group already narrowed to. This fixture
    // offers one dimension, so the question it asks is the split's shape —
    // a new config handed over whole — rather than a split's dimension;
    // which config a follow-up builds is `useAnalysisResult`'s to test.
    const byStatus = { ...focused(), limit: 5, sort: [] };
    act(() => {
      result.current.follow(byStatus, `${NARROWED} · by status`, ROW);
    });
    expect(result.current.held?.origin?.runtime).toBe(narrowed);
    await waitFor(() =>
      expect(result.current.state?.query.status).toBe('success'),
    );
    const ran = vi.mocked(source.aggregate).mock.calls.length;

    act(() => {
      result.current.back();
    });
    expect(result.current.runtime).toBe(narrowed);
    expect(narrowed.getSnapshot().result).toBe(narrowedResult);
    act(() => {
      result.current.back();
    });
    expect(result.current.runtime).toBe(saved);
    expect(result.current.held).toBeNull();
    expect(vi.mocked(source.aggregate).mock.calls.length).toBe(ran);
    expect(saved.getSnapshot().dirty).toBe(false);
  });

  it('leaves nothing behind, so asks nothing: the origin keeps its unsaved edits', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.runtime?.kind).toBe('analysis'));
    const origin = result.current.runtime!;
    act(() => {
      origin.edit({ limit: 7 });
    });
    const edited = origin.getSnapshot().draft;

    act(() => {
      result.current.follow(focused(), NARROWED, ROW);
    });
    expect(result.current.leave.asking).toBe(false);
    expect(result.current.state?.title).toBe(NARROWED);

    act(() => {
      result.current.drill(ROW, TITLE);
    });
    expect(result.current.leave.asking).toBe(false);
    expect(result.current.runtime?.kind).toBe('record');

    act(() => {
      result.current.back();
    });
    act(() => {
      result.current.back();
    });
    expect(result.current.runtime).toBe(origin);
    expect(origin.getSnapshot().draft).toBe(edited);
    expect(origin.getSnapshot().dirty).toBe(true);
  });

  it('inherits the scope the origin ran under, and needs no permission', async () => {
    const { result } = setup({}, readOnly);
    await waitFor(() => expect(result.current.runtime?.kind).toBe('analysis'));
    const scope: FilterTree = {
      op: 'and',
      children: [{ field: 'warehouse', operator: 'NE', value: 'XX' }],
    };
    act(() => {
      result.current.runtime!.setScopeFilter(scope);
    });

    act(() => {
      result.current.follow(focused(), NARROWED, ROW);
    });

    expect(result.current.state?.title).toBe(NARROWED);
    expect(result.current.runtime?.scopeFilter).toEqual(scope);
  });
});

describe('the name a view opened from a group goes by', () => {
  const focusedFilter: FilterTree = {
    op: 'and',
    children: [{ field: 'status', operator: 'EQ', value: 'PENDING' }, ...ROW],
  };
  const NARROWED = 'By warehouse · Warehouse is CN';

  it('is its subject once the group is taken off, and the group again once it is back', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.runtime?.kind).toBe('analysis'));
    act(() => {
      result.current.follow(
        { ...chart.config, filter: focusedFilter },
        NARROWED,
        ROW,
        'By warehouse',
      );
    });
    expect(result.current.state?.title).toBe(NARROWED);

    // The applied bar's ✕: the value goes, the query runs without it.
    act(() => {
      result.current.filter.clearValue([1]);
      result.current.filter.submit();
    });
    expect(result.current.state?.title).toBe('By warehouse');
    // What a follow-up from here is named after, and what it goes back to.
    act(() => {
      result.current.drill([], 'Orders', 'Orders');
    });
    expect(result.current.held?.origin?.title).toBe('By warehouse');
    act(() => {
      result.current.back();
    });

    // Put back as it was, the name says the group again.
    act(() => {
      result.current.runtime!.edit({ filter: focusedFilter });
      result.current.runtime!.apply();
    });
    expect(result.current.state?.title).toBe(NARROWED);
  });

  it('follows what ran, not a condition still being typed', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.runtime?.kind).toBe('analysis'));
    act(() => {
      result.current.follow(
        { ...chart.config, filter: focusedFilter },
        NARROWED,
        ROW,
        'By warehouse',
      );
    });
    act(() => {
      result.current.filter.clearValue([1]);
    });
    expect(result.current.state?.title).toBe(NARROWED);
  });

  it('names records drilled from a group by their subject once the group goes', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.runtime?.kind).toBe('analysis'));
    act(() => {
      result.current.drill(ROW, TITLE, 'Orders');
    });
    expect(result.current.state?.title).toBe(TITLE);
    act(() => {
      result.current.filter.clearValue([1]);
      result.current.filter.submit();
    });
    expect(result.current.state?.title).toBe('Orders');
    // The runtime keeps the name it was made under; only what the
    // workbench calls it has moved.
    expect(result.current.runtime?.getSnapshot().title).toBe(TITLE);
  });

  it('stands whatever the conditions become when no subject was given', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.runtime?.kind).toBe('analysis'));
    act(() => {
      result.current.drill(ROW, TITLE);
    });
    act(() => {
      result.current.filter.clearValue([1]);
      result.current.filter.submit();
    });
    expect(result.current.state?.title).toBe(TITLE);
  });
});
