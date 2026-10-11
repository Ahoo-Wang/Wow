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
  DatasetResources,
  type DatasetOverrides,
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

  it('removes a resource: open views keep running, opening another reports it unknown', () => {
    const engine = engineOf();
    const described = descriptor('city', 'sha256:1');
    engine.register({
      definition: datasetOf(described),
      source: sourceOf(described),
    });
    const open = create(engine);

    expect(engine.unregister(ID)).toBe(true);
    expect(engine.unregister(ID)).toBe(false);
    expect(engine.definitions.has(ID)).toBe(false);
    expect(names(open.fields)).toEqual(['order_id', 'city']);
    expect(() => create(engine)).toThrow();
    expect(() => engine.resolveSource(ID)).toThrow();
    // A source another definition still reads is kept.
    expect(engine.unregister('orders')).toBe(true);
  });
});

describe('DatasetResources', () => {
  const entry = (
    name: string,
    version: string | null,
    title: string | null = null,
  ) => ({
    contextName: 'analytics',
    name,
    title,
    version,
  });

  function catalogOf(listed: { current: ReturnType<typeof entry>[] }) {
    const described = new Map<string, QueryModelDescriptor>();
    const sources = vi.fn((found: ReturnType<typeof entry>) => {
      const column = found.version === 'sha256:2' ? 'region' : 'city';
      const result = descriptor(column, found.version ?? '');
      described.set(found.name, result);
      return sourceOf(result);
    });
    return {
      catalog: { list: () => Promise.resolve(listed.current), source: sources },
      sources,
    };
  }

  it('registers what the directory lists, again when its version changes, and removes what it drops', async () => {
    const engine = engineOf();
    const listed = {
      current: [
        entry('order_wide', 'sha256:1', 'Orders'),
        entry('broken', null),
      ],
    };
    const { catalog, sources } = catalogOf(listed);
    const datasets = new DatasetResources(engine, catalog);

    const first = await datasets.refresh();
    expect(first.map(found => found.id)).toEqual([ID]);
    expect(engine.definitions.get(ID)?.title).toBe('Orders');
    expect(engine.definitions.has('analytics.broken')).toBe(false);

    await datasets.refresh();
    expect(sources).toHaveBeenCalledTimes(1);

    listed.current = [
      entry('order_wide', 'sha256:2', 'Orders'),
      entry('returns', 'sha256:1'),
    ];
    const second = await datasets.refresh();
    expect(second.map(found => found.id)).toEqual([ID, 'analytics.returns']);
    expect(
      names((engine.definitions.get(ID) as DataViewDefinition).fields),
    ).toEqual(['order_id', 'region']);
    expect(engine.definitions.get('analytics.returns')?.title).toBe('returns');

    listed.current = [];
    expect(await datasets.refresh()).toEqual([]);
    expect(datasets.resources).toEqual([]);
    expect(engine.definitions.has(ID)).toBe(false);
  });

  it('leaves out a dataset it cannot describe, or one a definition the host registered already names', async () => {
    const engine = engineOf();
    const own = descriptor('city', 'sha256:own');
    const definition = datasetOf(own);
    engine.register({ definition, source: sourceOf(own) });
    const errors: unknown[] = [];
    const datasets = new DatasetResources(
      engine,
      {
        list: () =>
          Promise.resolve([
            entry('order_wide', 'sha256:1'),
            entry('returns', 'sha256:1'),
          ]),
        // A source without `describe`: there is no descriptor to derive from.
        source: () => testSource(),
      },
      (_, error) => errors.push(error),
    );
    expect(await datasets.refresh()).toEqual([]);
    expect(errors).toHaveLength(1);
    expect(engine.definitions.get(ID)).toBe(definition);
    expect(engine.definitions.has('analytics.returns')).toBe(false);
  });

  it("registers a dataset again when the host's words over it change", async () => {
    const engine = engineOf();
    const words = { current: { title: 'Orders' } as DatasetOverrides };
    const source = vi.fn(() => sourceOf(descriptor('city', 'sha256:1')));
    const datasets = new DatasetResources(engine, {
      list: () => Promise.resolve([entry('order_wide', 'sha256:1')]),
      source,
      overrides: () => words.current,
    });
    await datasets.refresh();
    expect(engine.definitions.get(ID)?.title).toBe('Orders');
    await datasets.refresh();
    expect(source).toHaveBeenCalledTimes(1);

    words.current = {
      title: 'Order wide table',
      fields: { city: 'Ship-to city' },
    };
    await datasets.refresh();
    expect(source).toHaveBeenCalledTimes(2);
    const definition = engine.definitions.get(ID) as DataViewDefinition;
    expect(definition.title).toBe('Order wide table');
    expect(definition.fields.map(field => field.label)).toEqual([
      'Ship-to city',
      'order_id',
    ]);
  });
});
