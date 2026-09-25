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

import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { QueryErrorCodes, WowError } from '@ahoo-wang/wow-client';
import {
  MemoryViewStore,
  ViewEngine,
  type DataViewDefinition,
  type FilterTree,
  type Issue,
  type RecordViewRuntime,
  type ViewErrorEvent,
  type ViewSource,
} from '../src/index.js';
import { queryFailureIssue } from '../src/runtime/queryFailure.js';
import { sourceFailure } from '../src/runtime/sourceReason.js';
import { useFilterEditor } from '../src/react/index.js';
import { en, FilterPanel, formatIssue, zhCN } from '../src/ui/index.js';
import {
  mine,
  ordersDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

/** A Wow rejection as fetcher's `ExchangeError` carries it. */
function rejected(
  code: string | undefined,
  name: string,
  errorMsg: string,
  errorCode = 'QuerySchemaValidation',
) {
  const body = {
    errorCode,
    errorMsg,
    ...(code === undefined
      ? {}
      : { bindingErrors: [{ name, msg: errorMsg, code }] }),
  };
  const extractResult = vi.fn(() => Promise.resolve(body));
  return Object.assign(new Error('Request failed with status code 400'), {
    exchange: { response: { status: 400 }, extractResult },
  });
}

/** The orders, with an `items` array whose entries declare what they hold. */
function withItems(): DataViewDefinition {
  const base = ordersDefinition();
  return {
    ...base,
    fields: [
      ...base.fields,
      {
        name: 'items',
        label: 'Items',
        kind: 'elementMatch',
        elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
      },
    ],
  };
}

const FILTER: FilterTree = {
  op: 'and',
  children: [
    { field: 'warehouse', operator: 'EQ', value: 'CN' },
    { op: 'or', children: [{ field: 'status', operator: 'EQ', value: 'x' }] },
    {
      field: 'items',
      operator: 'ELEMENT_MATCH',
      value: {
        op: 'and',
        children: [{ field: 'items.sku', operator: 'EQ', value: 'A-1' }],
      },
    },
  ],
};

async function issueFor(
  code: string | undefined,
  name: string,
  errorMsg = `Rule broken at [${name}].`,
): Promise<Issue> {
  return queryFailureIssue(
    await sourceFailure(rejected(code, name, errorMsg)),
    withItems(),
    FILTER,
  );
}

describe('sourceFailure', () => {
  it('reads the violation of a rejected query off the body, once', async () => {
    const error = rejected('UNKNOWN_FIELD', 'state.missing', 'Unknown.');
    const [first, second] = await Promise.all([
      sourceFailure(error),
      sourceFailure(error),
    ]);

    expect(first).toEqual({
      reason: 'Unknown.',
      violation: {
        code: 'UNKNOWN_FIELD',
        path: 'state.missing',
        message: 'Unknown.',
      },
      errorCode: 'QuerySchemaValidation',
      status: 400,
    });
    expect(second).toBe(first);
    expect(error.exchange.extractResult).toHaveBeenCalledOnce();
  });

  it('reads a WowError a stream ended with the same way', async () => {
    const error = new WowError({
      errorCode: 'IllegalArgument',
      errorMsg: 'Unknown type [NOPE] at [filter].',
      bindingErrors: [
        {
          name: 'filter',
          msg: 'Unknown type [NOPE] at [filter].',
          code: 'UNKNOWN_TYPE',
        },
      ],
    });

    await expect(sourceFailure(error)).resolves.toEqual({
      reason: 'Unknown type [NOPE] at [filter].',
      violation: {
        code: 'UNKNOWN_TYPE',
        path: 'filter',
        message: 'Unknown type [NOPE] at [filter].',
      },
      errorCode: 'IllegalArgument',
    });
  });

  it('has no violation where the service named no rule', async () => {
    // A budget rejection: text only. So is a binding error without a code.
    const budget = rejected(
      undefined,
      '',
      'HTTP list query limit[1001] must be between 1 and 1000.',
      'IllegalArgument',
    );
    await expect(sourceFailure(budget)).resolves.toEqual({
      reason: 'HTTP list query limit[1001] must be between 1 and 1000.',
      errorCode: 'IllegalArgument',
      status: 400,
    });
    const uncoded = rejected(undefined, '', 'x');
    Object.assign(uncoded.exchange, {
      extractResult: () =>
        Promise.resolve({
          errorCode: 'CommandValidation',
          errorMsg: 'x',
          bindingErrors: [{ name: 'a', msg: 'b' }, 'junk', null],
        }),
    });
    await expect(sourceFailure(uncoded)).resolves.toEqual({
      reason: 'x',
      errorCode: 'CommandValidation',
      status: 400,
    });
    await expect(sourceFailure('down')).resolves.toEqual({ reason: 'down' });
  });

  it('falls back to the reason and an empty path for a partial entry', async () => {
    const error = rejected(undefined, '', 'Model search is unsupported.');
    Object.assign(error.exchange, {
      extractResult: () =>
        Promise.resolve({
          errorCode: 'QuerySchemaValidation',
          errorMsg: 'Model search is unsupported.',
          bindingErrors: [{ code: 'MODEL_SEARCH_UNSUPPORTED', msg: ' ' }],
        }),
    });

    await expect(sourceFailure(error)).resolves.toMatchObject({
      violation: {
        code: 'MODEL_SEARCH_UNSUPPORTED',
        path: '',
        message: 'Model search is unsupported.',
      },
    });
  });
});

describe('queryFailureIssue', () => {
  it('names the rule, the field by its label, and the condition on it', async () => {
    const found = await issueFor('UNKNOWN_FIELD', 'status');

    expect(found).toEqual({
      code: 'runtime.query.failed.unknown_field',
      severity: 'error',
      path: ['children', 1, 'children', 0],
      params: {
        reason: 'Rule broken at [status].',
        code: 'UNKNOWN_FIELD',
        path: 'status',
        field: 'Status',
        name: 'status',
      },
    });
    expect(formatIssue(en, found)).toBe(
      'The service does not know the field Status. The view may be ahead of the data; remove the condition, column or group on it.',
    );
    expect(formatIssue(zhCN, found)).toBe(
      '服务端不认识字段「Status」。视图可能比数据新；去掉用到它的条件、列或维度。',
    );
  });

  it('points an element field at the element-match on its array', async () => {
    const found = await issueFor('ELEMENT_SCOPE_REQUIRED', 'items.sku');

    expect(found.path).toEqual(['children', 2]);
    expect(found.params).toMatchObject({
      field: 'Items · SKU',
      name: 'items.sku',
    });
    expect(formatIssue(zhCN, found)).toBe(
      '「Items · SKU」只能在它所属的列表里，按列表元素的条件筛选。',
    );
  });

  it('names a field no condition holds, and marks no row', async () => {
    // A column or a group on it: the strip says it, no pill carries it.
    const found = await issueFor(
      'UNSUPPORTED_CAPABILITY',
      'amount',
      'Field [amount] does not support [SORT].',
    );

    expect(found.path).toEqual([]);
    expect(formatIssue(en, found)).toBe(
      'Amount cannot be used this way on this data: Field [amount] does not support [SORT].',
    );
  });

  it('keeps the path when the definition declares no field there', async () => {
    const found = await issueFor('PROTECTED_AGGREGATION', 'state.secret');

    expect(found.path).toEqual([]);
    expect(found.params).not.toHaveProperty('name');
    expect(formatIssue(en, found)).toBe(
      'state.secret is protected and cannot be summarised.',
    );
  });

  it('words a comparison on a protected field in both languages', async () => {
    const found = await issueFor('PROTECTED_COMPARISON', 'state.secret');

    expect(formatIssue(en, found)).toBe(
      'state.secret is protected and cannot be used to filter, sort or search.',
    );
    expect(formatIssue(zhCN, found)).toBe(
      '「state.secret」受保护，不能用来筛选、排序或搜索。',
    );
  });

  it('words a sort by two independent lists in both languages', async () => {
    const found = await issueFor('PARALLEL_ARRAY_SORT', 'state.tags');

    expect(formatIssue(en, found)).toBe(
      'state.tags and another list field in the sort cannot be sorted by together; keep one of them.',
    );
    expect(formatIssue(zhCN, found)).toBe(
      '「state.tags」与排序里的另一个列表字段不能同时排序，只保留其中一个。',
    );
  });

  it('words an equality against a whole list in both languages', async () => {
    const found = await issueFor('ARRAY_EQUALITY', 'state.tags');

    expect(formatIssue(en, found)).toBe(
      'state.tags cannot be compared with a whole list here; match its items instead.',
    );
    expect(formatIssue(zhCN, found)).toBe(
      '这里不能拿「state.tags」和整个列表比较，请改为匹配其中的元素。',
    );
  });

  it('words a model-level rule without a field', async () => {
    const found = await issueFor('MODEL_SEARCH_UNSUPPORTED', '');

    expect(found.path).toEqual([]);
    expect(formatIssue(en, found)).toBe('This data does not support search.');
    expect(formatIssue(zhCN, found)).toBe('这份数据不支持搜索。');
  });

  it('words a decoding rule with the service’s words, and marks no row', async () => {
    const found = await issueFor(
      'UNKNOWN_TYPE',
      'filter',
      'Unknown type [NOPE] at [filter].',
    );

    expect(found.path).toEqual([]);
    expect(formatIssue(zhCN, found)).toBe(
      '服务端不认识这个视图用到的运算或指标，可能版本比视图引擎旧：Unknown type [NOPE] at [filter].',
    );
  });

  it('falls back to the service’s words for a code it has no wording for', async () => {
    const found = await issueFor('SOME_FUTURE_RULE', 'status', 'New rule.');

    expect(found.code).toBe('runtime.query.failed.some_future_rule');
    expect(formatIssue(en, found)).toBe('Could not load the data: New rule.');
    expect(formatIssue(zhCN, found)).toBe('没能加载数据：New rule.');
  });

  it('is the plain failure without a violation', async () => {
    const found = await issueFor(undefined, '', 'HTTP page size[101] …');

    expect(found).toEqual({
      code: 'runtime.query.failed',
      severity: 'error',
      path: [],
      params: { reason: 'HTTP page size[101] …' },
    });
  });

  it('words every code wow-client knows, in both languages', () => {
    for (const code of Object.values(QueryErrorCodes)) {
      const key = `runtime.query.failed.${code.toLowerCase()}`;
      expect(en, key).toHaveProperty([key]);
      expect(zhCN, key).toHaveProperty([key]);
    }
  });
});

describe('a rejected query in the view', () => {
  function watched(source: ViewSource) {
    const events: ViewErrorEvent[] = [];
    const engine = new ViewEngine({
      definitions: [withItems()],
      store: new MemoryViewStore({
        instances: [
          {
            ...mine,
            config: recordConfig({ filter: FILTER, filterMode: 'advanced' }),
          },
        ],
      }),
      resolveSource: () => source,
      environment: {
        ...testEnvironment().environment,
        onError: event => events.push(event),
      },
    });
    return { engine, events };
  }

  it('tells the host once, after the body is read, with the violation', async () => {
    const error = rejected(
      'VALUE_MISMATCH',
      'status',
      'Filter value does not match [status].',
    );
    const { engine, events } = watched(
      testSource({ paged: vi.fn(() => Promise.reject(error)) }),
    );

    const runtime = (await engine.open('orders-1')) as RecordViewRuntime;
    await vi.waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('error'),
    );

    expect(events).toEqual([
      {
        kind: 'query',
        error,
        context: {
          operation: 'query',
          definitionId: 'orders',
          instanceId: 'orders-1',
          runtimeId: runtime.id,
          errorCode: 'QuerySchemaValidation',
          violation: {
            code: 'VALUE_MISMATCH',
            path: 'status',
            message: 'Filter value does not match [status].',
          },
        },
      },
    ]);
    expect(error.exchange.extractResult).toHaveBeenCalledOnce();
    expect(runtime.getSnapshot().query.error).toMatchObject({
      code: 'runtime.query.failed.value_mismatch',
      path: ['children', 1, 'children', 0],
    });
  });

  it('carries the violation of a summary query it fell back from', async () => {
    const error = rejected('PROTECTED_AGGREGATION', 'amount', 'Protected.');
    const events: ViewErrorEvent[] = [];
    const engine = new ViewEngine({
      definitions: [withItems()],
      store: new MemoryViewStore({
        instances: [
          {
            ...mine,
            config: recordConfig({
              summaries: [{ field: 'amount', fn: 'SUM' }],
            }),
          },
        ],
      }),
      resolveSource: () =>
        testSource({ aggregate: vi.fn(() => Promise.reject(error)) }),
      environment: {
        ...testEnvironment().environment,
        onError: event => events.push(event),
      },
    });

    const runtime = (await engine.open('orders-1')) as RecordViewRuntime;
    await vi.waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('success'),
    );

    expect(events).toHaveLength(1);
    expect(events[0].context).toMatchObject({
      operation: 'summaries',
      violation: { code: 'PROTECTED_AGGREGATION', path: 'amount' },
    });
  });

  it('marks the refused condition in the editor, and blocks nothing', async () => {
    const error = rejected('UNSUPPORTED_CAPABILITY', 'warehouse', 'No.');
    const { engine } = watched(
      testSource({ paged: vi.fn(() => Promise.reject(error)) }),
    );
    const runtime = (await engine.open('orders-1')) as RecordViewRuntime;
    await vi.waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('error'),
    );
    let blocked = -1;
    function Probe() {
      const filter = useFilterEditor(runtime);
      blocked = filter.blocked;
      return <FilterPanel filter={filter} />;
    }
    render(<Probe />);

    const pill = await screen.findByRole('group', {
      name: 'Warehouse condition',
    });
    expect(pill.hasAttribute('data-invalid')).toBe(true);
    expect(
      screen
        .getByRole('group', { name: 'Status condition' })
        .hasAttribute('data-invalid'),
    ).toBe(false);
    expect(blocked).toBe(0);
  });

  it('lets go of the row once the draft no longer holds that condition there', async () => {
    const error = rejected('UNSUPPORTED_CAPABILITY', 'warehouse', 'No.');
    const { engine } = watched(
      testSource({ paged: vi.fn(() => Promise.reject(error)) }),
    );
    const runtime = (await engine.open('orders-1')) as RecordViewRuntime;
    await vi.waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('error'),
    );
    function Probe() {
      return <FilterPanel filter={useFilterEditor(runtime)} />;
    }
    render(<Probe />);
    await screen.findByRole('group', { name: 'Warehouse condition' });

    // The condition at that place is now on another field.
    runtime.edit({
      filter: {
        op: 'and',
        children: [{ field: 'id', operator: 'EQ', value: '1' }],
      },
    });

    await waitFor(() =>
      expect(
        screen
          .getByRole('group', { name: 'Order condition' })
          .hasAttribute('data-invalid'),
      ).toBe(false),
    );
  });
});
