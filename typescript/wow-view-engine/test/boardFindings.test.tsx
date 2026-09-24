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
 * What a board says about itself, above its panels (Q-01, A-07): one
 * reading (`boardFindings`), which the controller hands every surface. It
 * was three — the controller's dropped "too many panels", and the embed
 * that builds a board dropped a draft's panel warning no panel wore yet,
 * which a save then wrote unseen.
 */

import { act, renderHook } from '@testing-library/react';
import { FilterOperator } from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  withFieldKinds,
  type DashboardRuntime,
  type DashboardViewConfig,
  type FieldKind,
  type Issue,
  type RuntimeLimits,
} from '../src/index.js';
import { boardFindings } from '../src/runtime/dashboard/panels.js';
import { useDashboard } from '../src/react/index.js';
import {
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { panel, pending } from './fixtures/dashboard.js';

/**
 * Warns on one panel field only, so a board condition mapped onto it is a
 * finding at panel level and nowhere else: the board's own validation of
 * the condition has nothing to say about it.
 */
const rounded: FieldKind = {
  id: 'rounded',
  operators: ['EQ'],
  defaultOperator: 'EQ',
  emptyValue: () => null,
  validate: ({ value, field, path }) =>
    field.name === 'mass' &&
    typeof value === 'number' &&
    !Number.isInteger(value)
      ? [{ code: 'filter.value.rounded', severity: 'warning', path }]
      : [],
  compile: ({ leaf, field }) => ({
    op: FilterOperator.EQ,
    field: field.name,
    value: Math.round(leaf.value as number),
  }),
  editor: () => ({ input: 'number' }),
  describe: ({ leaf, field }) => ({
    text: `${field.label} = ${String(leaf.value)}`,
    value: { kind: 'text', value: String(leaf.value) },
  }),
};

async function openBoard(
  config: DashboardViewConfig,
  limits: Partial<RuntimeLimits> = {},
) {
  const orders = ordersDefinition();
  const store = new MemoryViewStore({ instances: [pending] });
  const engine = new ViewEngine({
    definitions: [
      {
        ...orders,
        fields: [
          ...orders.fields,
          { name: 'mass', label: 'Mass', kind: 'rounded' },
        ],
      },
      overviewDefinition(),
    ],
    store,
    resolveSource: () => testSource(),
    environment: testEnvironment().environment,
    kinds: withFieldKinds(builtinFieldKinds, [rounded]),
    limits: { ...DEFAULT_RUNTIME_LIMITS, ...limits },
  });
  const instance = await store.create(
    { definitionId: 'overview', title: 'Overview', scope: 'personal', config },
    { requestId: 'r' },
  );
  const runtime = (await engine.open(instance.id)) as DashboardRuntime;
  const view = renderHook(() => useDashboard(runtime));
  await act(async () => {
    await Promise.resolve();
  });
  return { controller: () => view.result.current, runtime };
}

const codes = (issues: readonly Issue[]) => issues.map(found => found.code);

describe('what a board says about itself (boardFindings)', () => {
  it('keeps "too many panels" — no one panel’s, and it stops them all', async () => {
    const { controller } = await openBoard(
      dashboardConfig({
        panels: [
          panel({ id: 'a' }),
          panel({ id: 'b', layout: { x: 0, y: 4, w: 6, h: 4 } }),
        ],
      }),
      { maxDashboardPanels: 1 },
    );

    expect(codes(controller().issues)).toEqual(['dashboard.panels.too-many']);
  });

  it('says a draft panel warning no panel wears yet, until apply hands it over', async () => {
    const { controller, runtime } = await openBoard(
      dashboardConfig({
        fields: [{ name: 'weight', label: 'Weight', kind: 'rounded' }],
        panels: [
          panel({ bindings: [{ globalField: 'weight', panelField: 'mass' }] }),
        ],
      }),
    );
    expect(controller().issues).toEqual([]);

    act(() =>
      runtime.edit({
        fixed: {
          op: 'and',
          children: [{ field: 'weight', operator: 'EQ', value: 2.5 }],
        },
      }),
    );
    expect(controller().issues).toHaveLength(1);
    expect(controller().issues[0]).toMatchObject({
      code: 'filter.value.rounded',
      path: expect.arrayContaining(['panels', 0, 'filter']),
    });
    expect(controller().panels[0].issues).toEqual([]);

    act(() => runtime.apply());
    expect(controller().issues).toEqual([]);
    expect(codes(controller().panels[0].issues)).toEqual([
      'filter.value.rounded',
    ]);
  });

  it('leaves a panel’s own finding to the panel', async () => {
    const { controller } = await openBoard(
      dashboardConfig({ panels: [panel({ instanceId: 'deleted' })] }),
    );

    expect(codes(controller().panels[0].issues)).toEqual([
      'dashboard.panel.unavailable',
    ]);
    expect(controller().issues).toEqual([]);
  });

  it('reads one snapshot the same way wherever it is asked', () => {
    const own: Issue = {
      code: 'dashboard.fields.invalid',
      severity: 'error',
      path: ['fields'],
    };
    const worn: Issue = {
      code: 'filter.value.rounded',
      severity: 'warning',
      path: ['panels', 0, 'filter'],
    };
    const unworn: Issue = { ...worn, path: ['panels', 1, 'filter'] };

    expect(
      boardFindings({
        issues: [own, worn, unworn],
        panels: [{ issues: [{ ...worn }] }, { issues: [] }],
      }),
    ).toEqual([own, unworn]);
  });
});
