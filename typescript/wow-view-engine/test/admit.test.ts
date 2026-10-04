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

import { describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  text,
  ViewEngine,
  type Issue,
  type ViewResource,
} from '../src/index.js';
import { admit } from '../src/testing/index.js';
import {
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { describedField, ordersDescriptor } from './fixtures/descriptor.js';

/**
 * `/testing`'s `admit` (host-integration.md 6): everything a host declares,
 * admitted as the engine admits it, over the descriptors it commits — the
 * one line a host's CI runs.
 */
describe('admit', () => {
  const descriptors = { orders: ordersDescriptor() };

  it('finds nothing in declarations that hold', () => {
    expect(
      admit([ordersDefinition(), overviewDefinition()], descriptors),
    ).toEqual([]);
  });

  it('says each finding with the definition it is about', () => {
    const board = overviewDefinition({
      views: [
        {
          id: 'board',
          title: text('board.title'),
          config: dashboardConfig({
            panels: [
              {
                id: 'gone',
                kind: 'view',
                instanceId: 'system:orders:gone',
                bindings: [],
                layout: { x: 0, y: 0, w: 6, h: 4 },
              },
            ],
          }),
        },
      ],
    });
    const found = admit(
      // A resource is taken as it is registered (host-integration.md 4) —
      // here as 9.2.1 took it, `{ definition }` alone, with no source
      // check (R2-102 review: patch-safe).
      [{ definition: ordersDefinition() }, board],
      descriptors,
      { text: key => (key === 'board.title' ? 'Board' : undefined) },
    );
    expect(found).toEqual([
      expect.objectContaining({
        definition: 'overview',
        code: 'dashboard.panel.view-undeclared',
        severity: 'error',
      }),
    ]);
  });

  it('narrows each definition to its descriptor, and says what that took', () => {
    const lacking = ordersDescriptor({
      fields: ['id', 'warehouse', 'status'].map(path => describedField(path)),
    });
    const found = admit([ordersDefinition()], { orders: lacking });
    expect(found.map(entry => [entry.definition, entry.code])).toContainEqual([
      'orders',
      'capability.field.unknown',
    ]);
  });

  it('says a source it was given no descriptor for goes unchecked', () => {
    expect(admit([ordersDefinition()], {})).toEqual([
      expect.objectContaining({
        definition: 'orders',
        code: 'definition.descriptor.missing',
        params: { source: 'orders' },
        severity: 'warning',
      }),
    ]);
  });

  /** What the engine's start tells `onIssue` of `resources`, by code. */
  function started(resources: readonly ViewResource[]): Issue[] {
    const told: Issue[] = [];
    new ViewEngine({
      resources,
      store: new MemoryViewStore(),
      environment: testEnvironment().environment,
      onIssue: found => told.push(found),
    });
    return told;
  }

  it('given a full resource list, holds each data resource to a registered source, as the engine does', () => {
    // R2-102: a list that names sources (one resource carries its own) is
    // the engine's registration, and a data resource whose key nobody
    // registers is said as the engine says it.
    const shipments = ordersDefinition({
      id: 'shipments',
      source: 'shipments',
    });
    const resources: ViewResource[] = [
      { definition: ordersDefinition(), source: testSource() },
      { definition: shipments },
      { definition: overviewDefinition() },
    ];
    const unregistered = {
      code: 'definition.source.unregistered',
      path: ['source'],
      params: { source: 'shipments' },
      severity: 'error',
    };
    expect(started(resources)).toEqual([unregistered]);
    expect(
      admit(resources, { ...descriptors, shipments: ordersDescriptor() }),
    ).toEqual([{ ...unregistered, definition: 'shipments' }]);
    // Another resource registering the key is a source for it, as the
    // engine reads the resources.
    expect(
      admit(
        [
          { definition: ordersDefinition() },
          {
            definition: ordersDefinition({ id: 'orders-again' }),
            source: testSource(),
          },
        ],
        descriptors,
      ),
    ).toEqual([]);
  });

  it('checks no source where no resource names one, as 9.2.1 did', () => {
    // A `{ definition }`-only list, or bare definitions, say nothing of
    // where rows come from: a host's test that held on 9.2.1 still holds.
    expect(
      admit(
        [
          { definition: ordersDefinition() },
          { definition: overviewDefinition() },
        ],
        descriptors,
      ),
    ).toEqual([]);
    expect(admit([ordersDefinition()], descriptors)).toEqual([]);
  });

  it('judges each definition as declared, keys and all, as the engine does', () => {
    // R2-102: a group's label is a key whose words are blank. The engine
    // judges the definition as declared, where the label is the key; admit
    // judged it as said, and refused a label the engine admits.
    const definition = ordersDefinition({
      fieldGroups: [{ id: 'main', label: text('group.main'), fields: ['id'] }],
    });
    const words = (key: string) => (key === 'group.main' ? '' : undefined);
    expect(
      started([{ definition, source: testSource() }]).map(found => found.code),
    ).not.toContain('definition.fieldGroup.invalid');
    expect(
      admit([definition], descriptors, { text: words }).map(
        found => found.code,
      ),
    ).not.toContain('definition.fieldGroup.invalid');
  });
});
