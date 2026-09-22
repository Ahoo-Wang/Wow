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
  type FilterNode,
  type FilterTree,
  type ViewInstance,
  type ViewPermissions,
} from '../src/index.js';
import { useWorkbench, type WorkbenchOptions } from '../src/react/index.js';
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';
import { mine } from './fixtures/ui.js';

afterEach(cleanup);

const chart: ViewInstance = {
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
};

/** The row the user pressed: the warehouse bar. */
const ROW: FilterNode[] = [{ field: 'warehouse', operator: 'EQ', value: 'CN' }];

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
      result.current.drill(ROW);
    });

    // A record view, unsaved, under the origin's conditions plus the row's,
    // flattened into one simple group.
    expect(result.current.runtime?.kind).toBe('record');
    expect(result.current.state?.saved).toBeNull();
    expect(result.current.state?.title).toBe('Untitled view');
    expect(result.current.state?.draft.filter).toEqual({
      op: 'and',
      children: [{ field: 'status', operator: 'EQ', value: 'PENDING' }, ...ROW],
    });
    expect(result.current.state?.draft.filterMode).toBe('simple');
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
      result.current.drill(ROW);
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
      result.current.drill(ROW);
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
      result.current.drill(ROW);
    });
    expect(result.current.runtime?.kind).toBe('record');
  });

  it('is not on offer from a record view, without record views, or without a name', async () => {
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

    const unnamed = setup({ newView: undefined });
    await waitFor(() =>
      expect(unnamed.result.current.runtime?.kind).toBe('analysis'),
    );
    expect(unnamed.result.current.canDrill).toBe(false);
    act(() => {
      unnamed.result.current.drill(ROW);
    });
    expect(unnamed.result.current.runtime?.kind).toBe('analysis');
  });

  it('hands the target to a host that takes drilling over, and holds nothing (H5)', async () => {
    const onDrilldown = vi.fn();
    const { result } = setup({ onDrilldown });
    await waitFor(() => expect(result.current.runtime?.kind).toBe('analysis'));

    act(() => {
      result.current.drill(ROW);
    });

    expect(onDrilldown).toHaveBeenCalledTimes(1);
    expect(onDrilldown.mock.calls[0][0]).toMatchObject({
      definitionId: 'orders',
      origin: { title: 'By warehouse', conditions: ROW },
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
      result.current.drill(ROW);
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
      result.current.drill(ROW);
    });
    act(() => {
      result.current.back();
    });
    expect(result.current.leave.asking).toBe(false);
    expect(result.current.runtime?.kind).toBe('analysis');

    act(() => {
      result.current.drill(ROW);
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
      result.current.drill(ROW);
    });
    const drilled = result.current.runtime!;

    unmount();

    expect(unsaved.disposed).toBe(true);
    expect(drilled.disposed).toBe(true);
  });
});
