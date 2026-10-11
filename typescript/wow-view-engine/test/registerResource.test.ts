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
  QueryModels,
  type QueryDescriptorResult,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client';
import { describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  defaultRecordConfig,
  defineDataset,
  type DataViewDefinition,
  type Issue,
} from '../src/index.js';
import { nextTask, ordersDefinition, testSource } from './fixtures.js';
import { describedField, ordersDescriptor } from './fixtures/descriptor.js';

const ID = 'analytics.order_wide';

function descriptor(column: string, version: string): QueryModelDescriptor {
  const base = ordersDescriptor();
  return {
    ...base,
    model: QueryModels.DATASET,
    version,
    record: { ...base.record, identity: 'order_id', rootOperators: [] },
    fields: [describedField('order_id'), describedField(column)],
    elements: [],
  };
}

function datasetOf(described: QueryModelDescriptor): DataViewDefinition {
  return defineDataset(described, { id: ID, source: ID, title: 'Orders' });
}

function sourceOf(described: QueryModelDescriptor) {
  const result: QueryDescriptorResult = {
    notModified: false,
    descriptor: described,
    version: described.version,
  };
  return testSource({ describe: vi.fn(() => Promise.resolve(result)) });
}

function engineOf(issues: Issue[] = []) {
  return new ViewEngine({
    resources: [{ definition: ordersDefinition(), source: testSource() }],
    store: new MemoryViewStore(),
    onIssue: found => issues.push(found),
  });
}

function create(engine: ViewEngine) {
  const definition = engine.definitions.get(ID) as DataViewDefinition;
  return engine.create(ID, {
    title: 'New',
    scope: 'personal',
    config: defaultRecordConfig(definition),
  });
}

const names = (fields: readonly { name: string }[]) => fields.map(f => f.name);

describe('ViewEngine.register', () => {
  it('serves a dataset published after the engine was built', async () => {
    const engine = engineOf();
    expect(engine.definitions.has(ID)).toBe(false);
    const first = descriptor('city', 'sha256:1');
    const source = sourceOf(first);

    engine.register({ definition: datasetOf(first), source });

    expect(engine.definitions.has(ID)).toBe(true);
    expect(
      engine.definitionIssues(ID).filter(f => f.severity === 'error'),
    ).toEqual([]);
    const runtime = create(engine);
    expect(names(runtime.fields)).toEqual(['order_id', 'city']);
    await nextTask();
    expect(source.paged).toHaveBeenCalled();
  });

  it('replaces a dataset whole: new views run on the new definition, open ones keep theirs', async () => {
    const engine = engineOf();
    const first = descriptor('city', 'sha256:1');
    engine.register({ definition: datasetOf(first), source: sourceOf(first) });
    const before = create(engine);
    await nextTask();

    const second = descriptor('region', 'sha256:2');
    const replacing = sourceOf(second);
    engine.register({ definition: datasetOf(second), source: replacing });
    const after = create(engine);
    await nextTask();

    expect(names(before.fields)).toEqual(['order_id', 'city']);
    expect(names(after.fields)).toEqual(['order_id', 'region']);
    expect(replacing.paged).toHaveBeenCalled();
  });

  it('judges what it registers as it judges the resources it was built with', () => {
    const issues: Issue[] = [];
    const engine = engineOf(issues);
    engine.register({ definition: datasetOf(descriptor('city', 'sha256:1')) });
    expect(issues.map(found => found.code)).toContain(
      'definition.source.unregistered',
    );
    expect(() => create(engine)).toThrow();
  });
});
