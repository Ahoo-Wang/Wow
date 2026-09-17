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

import { AggregationGroupType } from '@ahoo-wang/fetcher-wow';
import { describe, expect, it, vi } from 'vitest';
import {
  builtinFieldKinds,
  emptyDashboardConfig,
  isUsableDefinition,
  isViewCommandError,
  MemoryViewStore,
  validateDefinition,
  ViewEngine,
  type Issue,
  type ViewDefinition,
} from '../src/index.js';
import {
  analysisConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';

function codes(definition: ViewDefinition): string[] {
  return validateDefinition(definition, builtinFieldKinds).map(
    found => found.code,
  );
}

describe('validateDefinition accepts what the package assumes', () => {
  it('admits the shared fixtures', () => {
    expect(codes(ordersDefinition())).toEqual([]);
    expect(codes(overviewDefinition())).toEqual([]);
  });

  it('answers on a definition with neither capability nor views', () => {
    expect(
      codes({
        id: 'bare',
        title: 'Bare',
        kind: 'data',
        source: 'bare',
        fields: [],
      }),
    ).toEqual([]);
  });
});

describe('validateDefinition identity', () => {
  it('refuses a separator in the definition id, which would alias views', () => {
    // `('a:b', 'c')` and `('a', 'b:c')` compose the same instance id.
    expect(codes(ordersDefinition({ id: 'orders:eu' }))).toEqual([
      'definition.id.separator',
    ]);
  });

  it('refuses a separator in a view id, and a duplicate view id', () => {
    const found = codes(
      ordersDefinition({
        views: [
          { id: 'a:b', title: 'One', config: recordConfig() },
          { id: 'all', title: 'Two', config: recordConfig() },
          { id: 'all', title: 'Three', config: recordConfig() },
        ],
      }),
    );

    expect(found).toEqual([
      'definition.view.id-separator',
      'definition.view.id-duplicate',
    ]);
  });
});

describe('validateDefinition fields', () => {
  it('refuses a name outside Wow query syntax, which the compiler would throw on', () => {
    expect(
      codes(
        ordersDefinition({
          fields: [{ name: 'order id', label: 'Order', kind: 'string' }],
          record: undefined,
          analysis: undefined,
          views: [],
        }),
      ),
    ).toEqual(['definition.field.name-invalid']);
  });

  it('accepts a nested path and an `@` prefix', () => {
    expect(
      codes(
        ordersDefinition({
          fields: [
            { name: 'address.city', label: 'City', kind: 'string' },
            { name: '@timestamp', label: 'At', kind: 'datetime' },
            { name: 'lines.0.sku', label: 'First SKU', kind: 'string' },
          ],
          record: undefined,
          analysis: undefined,
          views: [],
        }),
      ),
    ).toEqual([]);
  });

  it('refuses a duplicate name and an unregistered kind', () => {
    expect(
      codes(
        ordersDefinition({
          fields: [
            { name: 'id', label: 'Order', kind: 'string' },
            { name: 'id', label: 'Again', kind: 'string' },
            { name: 'weird', label: 'Weird', kind: 'quaternion' },
          ],
          record: undefined,
          analysis: undefined,
          views: [],
        }),
      ),
    ).toEqual([
      'definition.field.duplicate',
      'definition.field.kind-unregistered',
    ]);
  });
});

describe('validateDefinition capabilities', () => {
  it('refuses a record capability `defaultRecordConfig` could not build from', () => {
    expect(
      codes(
        ordersDefinition({
          record: { rowKey: 'gone', paging: 'paged', layouts: [] },
          views: [],
        }),
      ),
    ).toEqual([
      'definition.record.layouts-empty',
      'definition.record.row-key-unknown',
    ]);
  });

  it('refuses an analysis capability over a field that is not declared', () => {
    expect(
      codes(
        ordersDefinition({
          analysis: {
            count: true,
            fields: [
              {
                field: 'gone',
                groups: [AggregationGroupType.TERMS],
                functions: [],
              },
            ],
          },
          views: [],
        }),
      ),
    ).toEqual(['definition.analysis.field-unknown']);
  });

  it('refuses an analysis capability that can construct no metric at all', () => {
    // `defaultAnalysisConfig` walks COUNT → functions → distinctCount →
    // percentile → any; a capability offering none has no starting config.
    expect(
      codes(
        ordersDefinition({
          analysis: {
            count: false,
            fields: [
              {
                field: 'warehouse',
                groups: [AggregationGroupType.TERMS],
                functions: [],
              },
            ],
          },
          views: [],
        }),
      ),
    ).toEqual(['definition.analysis.no-metric']);
  });

  it('accepts one built from a distinct count alone', () => {
    expect(
      codes(
        ordersDefinition({
          analysis: {
            count: false,
            fields: [
              {
                field: 'warehouse',
                groups: [AggregationGroupType.TERMS],
                functions: [],
                distinctCount: true,
              },
            ],
          },
          views: [],
        }),
      ),
    ).toEqual([]);
  });

  it('checks an element field scope where it is declared', () => {
    expect(
      codes(
        ordersDefinition({
          fields: [
            {
              name: 'lines',
              label: 'Lines',
              kind: 'array',
              // A name that repeats a root field is fine: every reference to
              // an element field is `field.element`, so they cannot collide.
              elements: [
                { name: 'id', label: 'Line', kind: 'string' },
                { name: 'id', label: 'Again', kind: 'string' },
                { name: 'not a name', label: 'Bad', kind: 'string' },
              ],
            },
          ],
          record: undefined,
          analysis: undefined,
          views: [],
        }),
      ),
    ).toEqual(['definition.field.duplicate', 'definition.field.name-invalid']);
  });

  it('cannot express an array that does not exist', () => {
    // Declaring elements on the field is what removes the dangling path: a
    // separate list keyed by path would admit one naming no field at all.
    const found = codes(
      ordersDefinition({
        analysis: {
          count: true,
          fields: [],
          elements: [{ path: 'ghost', aggregations: [] }],
        },
        views: [],
      }),
    );

    expect(found).toEqual(['definition.analysis.element-undeclared']);
  });

  it('refuses an aggregation over a name the element never declared', () => {
    // The root fields always had this check and the element ones did not, so
    // an aggregation could name nothing and reach a view as a metric with no
    // field behind it.
    expect(
      codes(
        ordersDefinition({
          fields: [
            {
              name: 'items',
              label: 'Items',
              kind: 'array',
              elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
            },
          ],
          record: undefined,
          analysis: {
            count: true,
            fields: [],
            elements: [
              {
                path: 'items',
                aggregations: [{ field: 'gone', groups: [], functions: [] }],
              },
            ],
          },
          views: [],
        }),
      ),
    ).toEqual(['definition.analysis.element-field-unknown']);
  });

  it('reports the qualified spelling as the mistake it now is', () => {
    // `qualify` no longer accepts both spellings, so writing the full path
    // where a relative name belongs is caught here rather than composing
    // `items.items.sku` and matching nothing.
    expect(
      codes(
        ordersDefinition({
          fields: [
            {
              name: 'items',
              label: 'Items',
              kind: 'array',
              elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
            },
          ],
          record: undefined,
          analysis: {
            count: true,
            fields: [],
            elements: [
              {
                path: 'items',
                aggregations: [
                  { field: 'items.sku', groups: [], functions: [] },
                ],
              },
            ],
          },
          views: [],
        }),
      ),
    ).toEqual(['definition.analysis.element-field-unknown']);
  });

  it('refuses an analysis expanding a field that holds no elements', () => {
    expect(
      codes(
        ordersDefinition({
          analysis: {
            count: true,
            fields: [],
            // `warehouse` is a plain string; there is nothing to expand.
            elements: [{ path: 'warehouse', aggregations: [] }],
          },
          views: [],
        }),
      ),
    ).toEqual(['definition.analysis.element-undeclared']);
  });

  it('refuses limits that are not positive integers, or a default above the max', () => {
    expect(
      codes(
        ordersDefinition({
          analysis: {
            count: true,
            fields: [],
            limits: { maxGroups: 0, maxLimit: 10, defaultLimit: 50 },
          },
          views: [],
        }),
      ),
    ).toEqual([
      'definition.analysis.limit-invalid',
      'definition.analysis.default-limit-too-large',
    ]);
  });
});

describe('validateDefinition system views', () => {
  it('refuses a config the definition declares no capability for', () => {
    expect(
      codes(
        ordersDefinition({
          analysis: undefined,
          views: [{ id: 'chart', title: 'Chart', config: analysisConfig() }],
        }),
      ),
    ).toEqual(['definition.view.kind-mismatch']);
  });

  it('refuses a dashboard config under a data definition, and the reverse', () => {
    expect(
      codes(
        ordersDefinition({
          views: [
            { id: 'board', title: 'Board', config: emptyDashboardConfig() },
          ],
        }),
      ),
    ).toEqual(['definition.view.kind-mismatch']);
    expect(
      codes(
        overviewDefinition({
          views: [{ id: 'rows', title: 'Rows', config: recordConfig() }],
        }),
      ),
    ).toEqual(['definition.view.kind-mismatch']);
  });

  it('runs the config through its own kernel, pointing at the view that holds it', () => {
    const found = validateDefinition(
      ordersDefinition({
        views: [
          {
            id: 'broken',
            title: 'Broken',
            config: recordConfig({ pageSize: 0 }),
          },
        ],
      }),
      builtinFieldKinds,
    );

    expect(found.map(entry => entry.code)).toEqual([
      'record.pageSize.not-positive',
    ]);
    expect(found[0].path).toEqual(['views', 0, 'config', 'pageSize']);
  });

  it('judges a dashboard system view locally, leaving panel references to the engine', () => {
    // The panel's instance cannot be loaded here, so it is reported as
    // unavailable — a warning, which keeps the definition usable.
    const found = validateDefinition(
      overviewDefinition({
        views: [
          {
            id: 'ops',
            title: 'Ops',
            config: {
              ...emptyDashboardConfig(),
              panels: [
                {
                  id: 'rows',
                  kind: 'view',
                  instanceId: 'orders-1',
                  bindings: [],
                  layout: { x: 0, y: 0, w: 6, h: 4 },
                },
              ],
            },
          },
        ],
      }),
      builtinFieldKinds,
    );

    expect(found.map(entry => entry.code)).toEqual([
      'dashboard.panel.unavailable',
    ]);
    expect(isUsableDefinition(found)).toBe(true);
  });
});

describe('ViewEngine and an unusable definition', () => {
  function engineWith(definition: ViewDefinition, issues: Issue[]) {
    return new ViewEngine({
      definitions: [definition],
      store: new MemoryViewStore({ instances: [] }),
      resolveSource: () => testSource(),
      onIssue: found => issues.push(found),
    });
  }

  it('reports what it found, once, at registration', () => {
    const issues: Issue[] = [];
    const engine = engineWith(ordersDefinition({ id: 'a:b' }), issues);

    expect(issues.map(found => found.code)).toEqual([
      'definition.id.separator',
    ]);
    expect(engine.definitionIssues('a:b').map(found => found.code)).toEqual([
      'definition.id.separator',
    ]);
    expect(engine.definitionIssues('unknown')).toEqual([]);
  });

  it('refuses to open or create against it, rather than failing deeper', async () => {
    const issues: Issue[] = [];
    const engine = engineWith(
      ordersDefinition({
        record: { rowKey: 'gone', paging: 'paged', layouts: [] },
        views: [],
      }),
      issues,
    );

    const refused = await engine
      .list('orders')
      .catch((error: unknown) => error);
    expect(isViewCommandError(refused) && refused.issue.code).toBe(
      'view.definition.invalid',
    );
    expect(() =>
      engine.create('orders', {
        title: 'New',
        scope: 'personal',
        config: recordConfig(),
      }),
    ).toThrow();
  });

  it('leaves a definition with only warnings usable', () => {
    const issues: Issue[] = [];
    const engine = engineWith(
      overviewDefinition({
        views: [
          {
            id: 'ops',
            title: 'Ops',
            config: {
              ...emptyDashboardConfig(),
              panels: [
                {
                  id: 'rows',
                  kind: 'view',
                  instanceId: 'orders-1',
                  bindings: [],
                  layout: { x: 0, y: 0, w: 6, h: 4 },
                },
              ],
            },
          },
        ],
      }),
      issues,
    );

    expect(issues.every(found => found.severity === 'warning')).toBe(true);
    expect(engine.list('overview')).resolves.toHaveLength(1);
  });

  it('says nothing about a definition that is fine', () => {
    const onIssue = vi.fn();
    new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store: new MemoryViewStore({ instances: [] }),
      resolveSource: () => testSource(),
      onIssue,
    });

    expect(onIssue).not.toHaveBeenCalled();
  });
  it('refuses a text comparison Wow does not know', () => {
    // `filter.contains` throws on an unknown comparison, and it would throw
    // while compiling a query rather than while reading the definition.
    const found = validateDefinition(
      ordersDefinition({
        fields: [
          {
            name: 'sku',
            label: 'SKU',
            kind: 'string',
            stringComparison: 'LOOSE' as never,
          },
        ],
        record: { rowKey: 'sku', paging: 'paged', layouts: ['table'] },
        analysis: undefined,
        views: [],
      }),
      builtinFieldKinds,
    );

    expect(found.map(entry => entry.code)).toContain(
      'definition.field.string-comparison-invalid',
    );
  });

  it('admits the two comparisons it does know', () => {
    for (const stringComparison of [
      'CASE_SENSITIVE',
      'CASE_INSENSITIVE',
    ] as const) {
      expect(
        validateDefinition(
          ordersDefinition({
            fields: [
              { name: 'sku', label: 'SKU', kind: 'string', stringComparison },
            ],
            record: { rowKey: 'sku', paging: 'paged', layouts: ['table'] },
            analysis: undefined,
            views: [],
          }),
          builtinFieldKinds,
        ),
      ).toEqual([]);
    }
  });
});
