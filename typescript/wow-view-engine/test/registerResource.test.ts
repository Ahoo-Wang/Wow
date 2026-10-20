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

import type { QueryDescriptorResult } from '../src/index.js';
import { QueryModels, type QueryModelDescriptor } from '@ahoo-wang/wow-client/dsl';
import { describe, expect, it, vi } from 'vitest';
import {
  DatasetResources,
  type DatasetOverrides,
  type DatasetWordingStore,
  type StoredDatasetWording,
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

  describe('with a store of wording (D91)', () => {
    function storeOf(initial: Record<string, StoredDatasetWording>) {
      const stored = new Map(Object.entries(initial));
      const store: DatasetWordingStore = {
        list: vi.fn(() => Promise.resolve(new Map(stored))),
        set: vi.fn(
          (dataset: string, wording: DatasetOverrides, revision: string) => {
            const next = String(Number(revision) + 1);
            stored.set(dataset, { wording, revision: next });
            return Promise.resolve(next);
          },
        ),
      };
      return store;
    }

    it("registers a dataset in its stored wording, over the host's", async () => {
      const engine = engineOf();
      const store = storeOf({
        [ID]: {
          wording: { title: 'Orders', fields: { city: 'City' } },
          revision: '2',
        },
      });
      const datasets = new DatasetResources(engine, {
        list: () => Promise.resolve([entry('order_wide', 'sha256:1')]),
        source: () => sourceOf(descriptor('city', 'sha256:1')),
        overrides: () => ({ title: 'From code' }),
        wordings: store,
      });
      await datasets.refresh();

      expect(engine.definitions.get(ID)?.title).toBe('Orders');
      const editor = engine.wordingOf(ID)!;
      expect(editor).toMatchObject({
        dataset: ID,
        derivedTitle: 'order_wide',
        wording: { title: 'Orders', fields: { city: 'City' } },
        revision: '2',
      });
      expect(editor.fields.map(field => field.name)).toEqual([
        'order_id',
        'city',
      ]);
    });

    it('saves at the revision in force and registers again in the new words, without describing again', async () => {
      const engine = engineOf();
      const store = storeOf({});
      const source = vi.fn(() => sourceOf(descriptor('city', 'sha256:1')));
      const datasets = new DatasetResources(engine, {
        list: () =>
          Promise.resolve([entry('order_wide', 'sha256:1', 'Orders')]),
        source,
        wordings: store,
      });
      await datasets.refresh();
      const editor = engine.wordingOf(ID)!;
      expect(editor.revision).toBe('0');

      await editor.save(
        { title: '订单', fields: { city: '城市' } },
        { requestId: 'r1' },
      );

      expect(store.set).toHaveBeenCalledWith(
        ID,
        { title: '订单', fields: { city: '城市' } },
        '0',
        { requestId: 'r1' },
      );
      expect(source).toHaveBeenCalledTimes(1);
      const definition = engine.definitions.get(ID) as DataViewDefinition;
      expect(definition.title).toBe('订单');
      expect(definition.fields.map(field => field.label)).toContain('城市');
      expect(engine.wordingOf(ID)?.revision).toBe('1');
      // The next refresh reads it back and changes nothing.
      await datasets.refresh();
      expect(source).toHaveBeenCalledTimes(1);
    });

    it('keeps the wording read last when the store cannot be read, and says so', async () => {
      const engine = engineOf();
      const store = storeOf({
        [ID]: { wording: { title: 'Orders' }, revision: '1' },
      });
      const errors: unknown[] = [];
      const datasets = new DatasetResources(
        engine,
        {
          list: () => Promise.resolve([entry('order_wide', 'sha256:1')]),
          source: () => sourceOf(descriptor('city', 'sha256:1')),
          wordings: store,
        },
        undefined,
        error => errors.push(error),
      );
      await datasets.refresh();
      vi.mocked(store.list).mockRejectedValueOnce(new Error('down'));
      await datasets.refresh();

      expect(errors).toHaveLength(1);
      expect(engine.definitions.get(ID)?.title).toBe('Orders');
    });

    it('registers no editor where the catalog keeps no wording', async () => {
      const engine = engineOf();
      const datasets = new DatasetResources(engine, {
        list: () => Promise.resolve([entry('order_wide', 'sha256:1')]),
        source: () => sourceOf(descriptor('city', 'sha256:1')),
      });
      await datasets.refresh();
      expect(engine.wordingOf(ID)).toBeUndefined();
      engine.unregister(ID);
      expect(engine.wordingOf(ID)).toBeUndefined();
    });
  });
});
