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

import { afterEach, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import {
  createFilterConfiguration,
  compileFilterConfiguration,
} from '../src/filter/filterCore.js';
import { filter } from '@ahoo-wang/fetcher-wow';
import { RecordActions } from '../src/record/page/RecordActions.js';
import { RecordCell } from '../src/record/table/RecordCell.js';
import { createSession } from '../src/record/engine/sessionState.js';
import { definition, instance } from './engine/fixtures.js';
import type { ViewExtensions } from '../src/record/recordReactTypes.js';
afterEach(cleanup);
function sample(extensions: ViewExtensions) {
  const reference = { name: 'toString' };
  const model = {
    ...definition,
    recordActions: { global: reference, table: reference, row: reference },
  };
  const saved = instance();
  const session = createSession(saved, model, {});
  const props = {
    definition: model,
    instance: saved,
    record: { state: { id: 'row', amount: 1 } },
    rowKey: 'row',
    index: 0,
    appliedFilter: session.appliedFilter,
    extensions,
    refresh: async () => {},
  };
  return (
    <>
      <RecordActions
        kind="global"
        definition={model}
        session={session}
        extensions={extensions}
        refresh={props.refresh}
      />
      <RecordActions
        kind="table"
        definition={model}
        session={session}
        extensions={extensions}
        refresh={props.refresh}
      />
      <RecordCell {...props} column={{ kind: 'actions', id: 'actions' }} />
      <RecordCell
        {...props}
        column={{
          kind: 'field',
          id: 'amount',
          field: 'state.amount',
          renderer: reference,
        }}
      />
    </>
  );
}
it('does not resolve inherited names from empty action or cell registries', () => {
  render(
    sample({ cells: {}, globalActions: {}, tableActions: {}, rowActions: {} }),
  );
  expect(screen.getAllByRole('alert')).toHaveLength(4);
  expect(screen.queryByText('[object Undefined]')).toBeNull();
});
it('allows explicitly registered names even if they match Object.prototype', () => {
  const Component = () => <span>Registered</span>;
  const registry = { toString: Component };
  render(
    sample({
      cells: registry,
      globalActions: registry,
      tableActions: registry,
      rowActions: registry,
    }),
  );
  expect(screen.getAllByText('Registered')).toHaveLength(4);
});

it('applies the same explicit-registration rule to filter compilers', () => {
  const config = createFilterConfiguration({
    id: 'custom',
    operator: 'EQ',
    field: 'state.amount',
    component: { name: 'toString' },
    props: { value: 1 },
  });
  expect(
    compileFilterConfiguration(config, definition.fields, undefined, {}).errors,
  ).toHaveLength(1);
  const result = compileFilterConfiguration(
    config,
    definition.fields,
    undefined,
    { toString: { compile: () => filter.eq('state.amount', 1) } },
  );
  expect(result.errors).toEqual([]);
  expect(result.expression).toEqual(filter.eq('state.amount', 1));
});
