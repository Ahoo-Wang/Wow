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

// Run after building the package: node packages/view-engine/examples/core.mjs
import assert from 'node:assert/strict';
import {
  ViewEngine,
  compileBuiltinFilter,
  compileFilterConfiguration,
  createFilterConfiguration,
  newFilterNode,
} from '@ahoo-wang/fetcher-view-engine';

const definition = {
  id: 'orders',
  title: 'Orders',
  sourceId: 'orders',
  rowKey: 'id',
  allowedOperators: ['MATCH_ALL', 'GTE'],
  fields: [
    { field: 'id', label: 'Order', type: 'string' },
    { field: 'amount', label: 'Amount', type: 'number', operators: ['GTE'] },
  ],
};
const initial = {
  id: 'mine',
  definitionId: definition.id,
  title: 'My orders',
  kind: 'record',
  scope: { type: 'personal' },
  revision: '1',
  config: {
    filters: createFilterConfiguration(newFilterNode('MATCH_ALL')),
    sort: [],
    pagination: { mode: 'paged', size: 2 },
    presentation: {
      layout: 'table',
      table: {
        columns: [
          { id: 'id', kind: 'field', field: 'id' },
          { id: 'amount', kind: 'field', field: 'amount' },
        ],
      },
    },
  },
};
const records = [
  { id: 'order-1', amount: 10 },
  { id: 'order-2', amount: 30 },
  { id: 'order-3', amount: 50 },
];
// Use actual JSON at the persistence boundary, just as a remote host would.
const saved = new Map([[initial.id, JSON.stringify(initial)]]);
const queries = [];
const writes = [];
let delayedQuery;
function deferred() {
  let resolve;
  const promise = new Promise(complete => {
    resolve = complete;
  });
  return { promise, resolve };
}
const source = {
  async paged(query, _attributes, controller) {
    controller?.signal.throwIfAborted();
    queries.push(structuredClone(query));
    if (delayedQuery) {
      const pending = delayedQuery;
      delayedQuery = undefined;
      pending.started.resolve(controller);
      // Deliberately complete after abort to exercise the public lifecycle guard.
      return pending.result.promise;
    }
    // ponytail: this local demo supports only MATCH_ALL/GTE; use a QueryApi for a full backend.
    assert.ok(['MATCH_ALL', 'GTE'].includes(query.filter.op));
    const matched = records.filter(
      record =>
        query.filter.op === 'MATCH_ALL' || record.amount >= query.filter.value,
    );
    const { index, size } = query.pagination;
    return {
      total: matched.length,
      list: structuredClone(matched.slice((index - 1) * size, index * size)),
    };
  },
};
const host = {
  resolveSource(id) {
    assert.equal(id, definition.sourceId);
    return source;
  },
  definition: {
    async load(id) {
      assert.equal(id, definition.id);
      return structuredClone(definition);
    },
  },
  instance: {
    async list(id) {
      assert.equal(id, definition.id);
      return {
        instances: [...saved.values()].map(value => JSON.parse(value)),
        defaultInstanceId: initial.id,
      };
    },
    async load(id) {
      assert.ok(saved.has(id));
      return JSON.parse(saved.get(id));
    },
    async save(instance) {
      assert.equal(
        instance.revision,
        JSON.parse(saved.get(instance.id)).revision,
      );
      const next = {
        ...structuredClone(instance),
        revision: String(Number(instance.revision) + 1),
      };
      saved.set(next.id, JSON.stringify(next));
      writes.push('save');
      return JSON.parse(saved.get(next.id));
    },
    async create(instance) {
      assert.equal('id' in instance, false);
      assert.equal('revision' in instance, false);
      const created = {
        ...structuredClone(instance),
        id: 'shared-copy',
        revision: '1',
      };
      saved.set(created.id, JSON.stringify(created));
      writes.push('create');
      return JSON.parse(saved.get(created.id));
    },
  },
  permission: {
    getInstance() {
      return { save: true, saveAsPersonal: true, saveAsShared: true };
    },
  },
};
const filterCompilers = {
  'amount-preset': {
    compile(props, context) {
      if (props.selectedId === undefined) return undefined;
      assert.equal(props.selectedId, 'minimum-25');
      return compileBuiltinFilter({ value: 25 }, context);
    },
    clear: props => ({ ...props, selectedId: undefined }),
  },
};
const engineOptions = { definitionId: definition.id, host, filterCompilers };
const engine = new ViewEngine(engineOptions);
async function verifyReloadedDraft(expectedDraft) {
  const reloaded = new ViewEngine(engineOptions);
  try {
    await reloaded.load();
    assert.deepEqual(
      reloaded.getSnapshot().sessions.mine.filterDraft,
      expectedDraft,
    );
  } finally {
    reloaded.dispose();
  }
}
const session = () => {
  const state = engine.getSnapshot();
  return state.sessions[state.selectedInstanceId];
};
let notifications = 0;
const unsubscribe = engine.subscribe(() => {
  notifications++;
});
try {
  await engine.load();
  assert.equal(engine.getSnapshot().status, 'ready');
  assert.equal(engine.getSnapshot().selectedInstanceId, initial.id);
  assert.deepEqual(
    session().rows.map(row => row.id),
    ['order-1', 'order-2'],
  );
  assert.equal(session().total, 3);
  assert.equal(queries.length, 1);
  assert.ok(notifications > 0);
  assert.throws(() => {
    session().rows[0].amount = 999;
  }, TypeError);
  assert.equal(records[0].amount, 10);

  // An intentionally unset numeric control is valid and remains in the editor baseline.
  const unset = createFilterConfiguration(newFilterNode('GTE', 'amount'));
  const compiledUnset = compileFilterConfiguration(
    unset,
    definition.fields,
    definition.allowedOperators,
  );
  assert.deepEqual(compiledUnset, {
    expression: { op: 'MATCH_ALL' },
    errors: [],
  });
  engine.setFilterDraft(unset, undefined, true);
  assert.equal(session().filterPending, false);
  assert.equal(session().dirty, true);
  await engine.save();
  assert.equal(queries.length, 1);
  assert.equal(
    JSON.parse(saved.get('mine')).config.filters.root.id,
    unset.root.id,
  );
  assert.deepEqual(JSON.parse(saved.get('mine')).config.filters.root.props, {});
  await verifyReloadedDraft(session().filterDraft);
  assert.equal(session().filterDraft.root.operator, 'GTE');
  assert.equal(session().filterDraft.root.props.value, undefined);
  assert.equal(session().filterPending, false);

  const invalid = {
    ...unset,
    root: { ...unset.root, props: { value: 'not-a-number' } },
  };
  const compiledInvalid = compileFilterConfiguration(
    invalid,
    definition.fields,
    definition.allowedOperators,
  );
  assert.ok(compiledInvalid.errors.length > 0);
  assert.equal(compiledInvalid.expression, undefined);
  engine.setFilterDraft(invalid, undefined, false);
  const callsBeforeInvalid = queries.length;
  await assert.rejects(engine.applyFilter());
  await assert.rejects(engine.save());
  assert.equal(queries.length, callsBeforeInvalid);
  assert.deepEqual(writes, ['save']);

  const valid = { ...unset, root: { ...unset.root, props: { value: 25 } } };
  engine.setFilterDraft(valid, undefined, true);
  const compiled = compileFilterConfiguration(
    valid,
    definition.fields,
    definition.allowedOperators,
  );
  assert.deepEqual(compiled.errors, []);
  await assert.rejects(engine.save());
  await engine.applyFilter();
  assert.deepEqual(
    session().rows.map(row => row.id),
    ['order-2', 'order-3'],
  );
  assert.deepEqual(queries.at(-1).filter, {
    op: 'GTE',
    field: 'amount',
    value: 25,
  });
  assert.equal(session().filterPending, false);
  await engine.save();
  assert.equal(session().baseline.revision, '3');
  assert.equal(session().dirty, false);

  // The EQ/GTE result cannot reconstruct a preset ID or its editable display label.
  const opaque = {
    ...unset,
    root: {
      ...unset.root,
      component: { name: 'amount-preset' },
      props: {
        selectedId: 'minimum-25',
        displayLabel: 'My manually named threshold',
      },
    },
  };
  const beforeMetadata = queries.length;
  engine.setFilterDraft(opaque, undefined, true);
  assert.equal(session().filterPending, false);
  assert.equal(session().dirty, true);
  assert.equal('props' in session().appliedFilter, false);
  await engine.save();
  assert.equal(queries.length, beforeMetadata);
  const persisted = JSON.parse(saved.get('mine'));
  assert.deepEqual(persisted.config.filters.root.props, opaque.root.props);
  assert.equal('filter' in persisted.config, false);
  await verifyReloadedDraft(session().filterDraft);
  await engine.saveAs({
    title: 'Shared orders',
    scope: { type: 'public', source: 'shared' },
  });
  assert.deepEqual(writes, ['save', 'save', 'save', 'create']);
  assert.equal(engine.getSnapshot().selectedInstanceId, 'shared-copy');
  assert.deepEqual(session().instance.scope, {
    type: 'public',
    source: 'shared',
  });
  assert.equal(session().dirty, false);
  assert.deepEqual(
    session().rows.map(row => row.id),
    ['order-2', 'order-3'],
  );
  assert.equal(JSON.parse(saved.get('mine')).title, 'My orders');

  const pending = { started: deferred(), result: deferred() };
  delayedQuery = pending;
  const refresh = engine.refresh();
  const controller = await pending.started.promise;
  const beforeDispose = engine.getSnapshot();
  const notificationsBeforeDispose = notifications;
  engine.dispose();
  assert.equal(controller.signal.aborted, true);
  pending.result.resolve({ total: 1, list: [{ id: 'late', amount: 999 }] });
  await refresh;
  assert.equal(engine.getSnapshot(), beforeDispose);
  assert.equal(notifications, notificationsBeforeDispose);
  await assert.rejects(engine.refresh());
  console.log(
    'Core example passed: load, immutable snapshots, unset and opaque props JSON save without querying, new-engine reload, invalid filters, Query-before-Save, saveAs, dispose and stale results.',
  );
} finally {
  unsubscribe();
  engine.dispose();
}
