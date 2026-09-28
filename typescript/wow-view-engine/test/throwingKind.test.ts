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

import { FilterOperator } from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  MemoryViewStore,
  ViewEngine,
  withFieldKinds,
  type DashboardPanel,
  type DashboardViewConfig,
  type FieldKind,
  type ViewInstance,
} from '../src/index.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import {
  dashboardConfig,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

/** A host kind whose own rules throw on every value they are asked about. */
const brittle: FieldKind = {
  id: 'brittle',
  operators: ['EQ'],
  defaultOperator: 'EQ',
  emptyValue: () => null,
  validate: () => {
    throw new Error('brittle is broken');
  },
  compile: ({ field, leaf }) => ({
    op: FilterOperator.EQ,
    field: field.name,
    value: String(leaf.value),
  }),
  editor: () => ({ input: 'text' }),
  describe: ({ leaf, field }) => ({
    text: `${field.label} = ${String(leaf.value)}`,
    value: { kind: 'text', value: String(leaf.value) },
  }),
};

function view(id: string, config = recordConfig()): ViewInstance {
  return {
    id,
    definitionId: 'orders',
    title: id,
    scope: 'shared',
    revision: 'r1',
    config,
  };
}

function panel(instanceId: string, y: number): DashboardPanel {
  return {
    id: instanceId,
    kind: 'view',
    instanceId,
    bindings: [],
    layout: { x: 0, y, w: 6, h: 4 },
  } as DashboardPanel;
}

/** Opens a board over `plain`, and `graded`, whose filter asks `brittle`. */
async function openBoard(config: DashboardViewConfig) {
  const orders = ordersDefinition();
  const store = new MemoryViewStore({
    instances: [
      view('plain'),
      view(
        'graded',
        recordConfig({
          filter: {
            op: 'and',
            children: [{ field: 'grade', operator: 'EQ', value: 'A' }],
          },
        }),
      ),
    ],
  });
  const source = testSource();
  const engine = new ViewEngine({
    definitions: [
      {
        ...orders,
        fields: [
          ...orders.fields,
          { name: 'grade', label: 'Grade', kind: 'brittle' },
        ],
      },
      overviewDefinition(),
    ],
    store,
    resolveSource: () => source,
    environment: testEnvironment().environment,
    kinds: withFieldKinds(builtinFieldKinds, [brittle]),
  });
  const board = await store.create(
    { definitionId: 'overview', title: 'Overview', scope: 'shared', config },
    { requestId: 'r' },
  );
  const runtime = await engine.open(board.id);
  await nextTask();
  if (!(runtime instanceof DashboardViewRuntime))
    throw new Error(`expected a dashboard, got ${runtime.kind}`);
  return runtime;
}

/**
 * A host kind is host code running inside the kernel. Its `validate`
 * throwing escaped admission: an edit's load nobody awaits turned it into an
 * unhandled rejection, and an open rejected with the host's error. It is the
 * one condition the kernel cannot judge, so it is said of that leaf and only
 * the panel standing on it stays out.
 */
describe('a host kind whose validate throws', () => {
  it('puts out only the panel an edit added', async () => {
    const runtime = await openBoard(
      dashboardConfig({ panels: [panel('plain', 0)] }),
    );

    runtime.setBuilding(true);
    runtime.addPanel({ kind: 'view', instanceId: 'graded' });
    await nextTask();
    const [plain, graded] = runtime.getSnapshot().panels;

    expect(graded.issues).toEqual([
      {
        code: 'filter.kind.failed',
        severity: 'error',
        path: ['panels', 1, 'filter', 'children', 0],
        params: { kind: 'brittle', reason: 'brittle is broken' },
      },
    ]);
    expect(graded.runtime).toBeNull();
    expect(plain.runtime?.getSnapshot().query.status).toBe('success');
  });

  it('opens a board that already holds the panel', async () => {
    const runtime = await openBoard(
      dashboardConfig({ panels: [panel('plain', 0), panel('graded', 4)] }),
    );
    const [plain, graded] = runtime.getSnapshot().panels;

    expect(graded.issues.map(found => found.code)).toEqual([
      'filter.kind.failed',
    ]);
    expect(graded.runtime).toBeNull();
    expect(plain.runtime?.getSnapshot().query.status).toBe('success');
  });
});
