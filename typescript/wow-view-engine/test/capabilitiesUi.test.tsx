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

import { cleanup, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { SearchMode, type QueryModelDescriptor } from '@ahoo-wang/wow-client';
import {
  builtinFieldKinds,
  elementFields,
  emptyFilter,
  maxSortFields,
  MemoryViewStore,
  validateDefinition,
  validateRecord,
  ViewEngine,
  type DataViewDefinition,
} from '../src/index.js';
import { useFilterEditor, useSearchBox } from '../src/react/index.js';
import { treeController } from '../src/react/useFilterEditor.js';
import { narrowDefinition } from '../src/capabilities/index.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';
import {
  describedField,
  ordersDescriptor,
  read,
} from './fixtures/descriptor.js';

afterEach(cleanup);

/** Orders with the compensation console's 「搜索错误」 (G15). */
const searchable: DataViewDefinition = ordersDefinition({
  fields: [
    ...ordersDefinition().fields,
    { name: 'errorMsg', label: 'Error', kind: 'string' },
    {
      name: 'keyword',
      label: 'Search errors',
      kind: 'search',
      searchFields: ['errorMsg'],
      searchMode: 'PHRASE',
    },
  ],
});

/** No full-text search, and no condition on `status`. */
function mongoDescriptor(): QueryModelDescriptor {
  const base = ordersDescriptor();
  return {
    ...base,
    fields: [
      ...base.fields.map(field =>
        field.path === 'status'
          ? describedField('status', { filter: { operators: [] } })
          : field,
      ),
      describedField('errorMsg'),
    ],
  };
}

async function opened(descriptor: QueryModelDescriptor | null) {
  const engine = new ViewEngine({
    definitions: [searchable],
    store: new MemoryViewStore({ instances: [] }),
    resolveSource: () =>
      testSource(
        descriptor ? { describe: () => Promise.resolve(read(descriptor)) } : {},
      ),
  });
  const runtime = await engine.open('system:orders:all');
  if (runtime.kind === 'dashboard') throw new Error('expected a data view');
  return runtime;
}

describe('what the controls offer on a narrowed definition', () => {
  it('hides the search box where the source has no search (G15)', async () => {
    const plain = await opened(null);
    const mongo = await opened(mongoDescriptor());

    expect(
      renderHook(() => useSearchBox(plain)).result.current?.field.name,
    ).toBe('keyword');
    expect(renderHook(() => useSearchBox(mongo)).result.current).toBeNull();
  });

  it('offers no condition on a field the source admits none on', async () => {
    const runtime = await opened(mongoDescriptor());
    const { result } = renderHook(() => useFilterEditor(runtime));

    const offered = result.current.fieldsFor().map(field => field.name);
    expect(offered).not.toContain('status');
    expect(offered).toContain('warehouse');
  });
});

describe("an element's search (N4)", () => {
  const withLines = ordersDefinition({
    fields: [
      ...ordersDefinition().fields,
      {
        name: 'lines',
        label: 'Lines',
        kind: 'elementMatch',
        elements: [
          { name: 'sku', label: 'SKU', kind: 'string' },
          {
            name: 'q',
            label: 'Search lines',
            kind: 'search',
            searchFields: ['sku'],
          },
        ],
      },
    ],
  });
  const described = (search: boolean): QueryModelDescriptor => {
    const base = ordersDescriptor();
    return {
      ...base,
      fields: [
        ...base.fields,
        describedField('lines'),
        describedField('lines.sku', { scope: 'lines' }),
      ],
      elements: [
        {
          path: 'lines',
          filter: true,
          aggregate: true,
          ...(search
            ? { search: { modes: [SearchMode.TERMS], fields: ['lines.sku'] } }
            : {}),
        },
      ],
    };
  };
  /** What the element match's own picker offers. */
  const offered = (definition: DataViewDefinition) => {
    const lines = definition.fields.find(field => field.name === 'lines')!;
    return treeController({
      tree: emptyFilter(),
      fields: elementFields(lines),
      kinds: builtinFieldKinds,
      issues: [],
      onChange: () => undefined,
    })
      .fieldsFor()
      .map(field => field.name);
  };
  const narrowed = (search: boolean) =>
    narrowDefinition(withLines, described(search), builtinFieldKinds)
      .definition;

  it('is offered inside the element match where it is declared, or described', () => {
    expect(offered(withLines)).toEqual(['lines.sku', 'lines.q']);
    expect(offered(narrowed(true))).toEqual(['lines.sku', 'lines.q']);
  });

  it('is not offered where the source searches no element', () => {
    expect(offered(narrowed(false))).toEqual(['lines.sku']);
  });
});

describe('a sort bound the definition declares', () => {
  const bounded = ordersDefinition({
    record: {
      rowKey: 'id',
      paging: 'paged',
      layouts: ['table'],
      maxSortFields: 1,
    },
  });

  it('lowers what a paged view may sort by, and admission holds a view to it', () => {
    expect(maxSortFields(ordersDefinition())).toBe(4);
    expect(maxSortFields(bounded)).toBe(1);

    const config = recordConfig({
      sort: [
        { field: 'amount', direction: 'DESC' },
        { field: 'id', direction: 'ASC' },
      ],
    });
    expect(
      validateRecord(bounded, config, builtinFieldKinds).map(
        found => found.code,
      ),
    ).toContain('record.sort.too-many');
    expect(
      validateRecord(ordersDefinition(), config, builtinFieldKinds).map(
        found => found.code,
      ),
    ).not.toContain('record.sort.too-many');
  });

  it('must be a whole number of fields', () => {
    const wrong = ordersDefinition({
      record: {
        rowKey: 'id',
        paging: 'paged',
        layouts: ['table'],
        maxSortFields: 1.5,
      },
    });

    expect(validateDefinition(bounded, builtinFieldKinds)).toEqual([]);
    expect(validateDefinition(wrong, builtinFieldKinds)).toEqual([
      {
        code: 'definition.record.max-sort-fields-invalid',
        severity: 'error',
        path: ['record', 'maxSortFields'],
        params: { value: '1.5' },
      },
    ]);
  });
});
