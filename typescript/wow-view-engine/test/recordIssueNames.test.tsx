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

/**
 * A record view's finding says what the reader sees — a field by its label,
 * a summary, an operator and a layout in the words of their controls — and
 * never the path or the protocol's key. Each case is raised by the kernel
 * itself, and said in both catalogues. The analysis view keeps the same rule
 * (`analysisIssueNames.test.ts`).
 */

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  MemoryViewStore,
  validateRecord,
  ViewEngine,
  type Issue,
  type RecordViewConfig,
} from '../src/index.js';
import { DataWorkbench, zhCN } from '../src/ui/index.js';
import { recordIssueNamer } from '../src/ui/record/issueNames.js';
import {
  defaultMessages,
  formatIssue,
  formatMessage,
  type ViewMessages,
} from '../src/ui/messages.js';
import type { MessageFormatters } from '../src/ui/MessagesProvider.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';

/** Only tables: a saved card layout is one the definition no longer offers. */
const definition = ordersDefinition({
  record: { rowKey: 'id', paging: 'paged', layouts: ['table'] },
});

function formatters(catalogue: ViewMessages): MessageFormatters {
  return {
    label: (key, params, fallback) => {
      const found = formatMessage(catalogue, key, params);
      return found === key && fallback !== undefined ? fallback : found;
    },
    issue: found => formatIssue(catalogue, found),
    issues: found => found.map(each => formatIssue(catalogue, each)).join(' '),
  };
}

const CASES: {
  code: string;
  config: Partial<RecordViewConfig>;
  en: string;
  zh: string;
  keys: string[];
}[] = [
  {
    code: 'record.sort.not-sortable',
    config: { sort: [{ field: 'warehouse', direction: 'ASC' }] },
    en: 'Warehouse cannot be sorted on.',
    zh: '「Warehouse」不能用来排序。',
    keys: ['warehouse'],
  },
  {
    code: 'record.summary.unsupported',
    config: { summaries: [{ field: 'amount', fn: 'AVG' }] },
    en: 'Amount',
    zh: '「Amount」不提供平均汇总。',
    keys: ['amount', 'AVG'],
  },
  {
    code: 'record.layout.unsupported',
    config: { layout: 'card' },
    en: 'Cards',
    zh: '这里没有「卡片」布局。',
    keys: ["'card'", ' card '],
  },
];

describe('a record finding says what the screen shows', () => {
  it.each(CASES)('$code', ({ code, config, en, zh, keys }) => {
    const raised = validateRecord(
      definition,
      recordConfig(config),
      builtinFieldKinds,
    ).filter(found => found.code === code);
    expect(raised).not.toEqual([]);

    const [english, chinese] = [defaultMessages, zhCN].map(catalogue => {
      const messages = formatters(catalogue);
      const name = recordIssueNamer(definition.fields, messages);
      return raised.map(found => messages.issue(name(found))).join(' ');
    });
    expect(english).toContain(en);
    expect(chinese).toContain(zh);
    for (const sentence of [english, chinese])
      for (const key of keys) expect(sentence).not.toContain(key);
  });

  it('names an operator as its select does', () => {
    const found: Issue = {
      code: 'filter.operator.unsupported',
      severity: 'error',
      path: ['filter', 'children', 0],
      params: { field: 'status', operator: 'GT' },
    };
    const messages = formatters(zhCN);
    expect(
      messages.issue(recordIssueNamer(definition.fields, messages)(found)),
    ).toBe(`「Status」不支持「${zhCN['label.operator.GT']}」。`);
  });

  it('leaves a field the definition does not have as the kernel said it', () => {
    const found: Issue = {
      code: 'filter.field.unknown',
      severity: 'error',
      path: ['filter', 'children', 0],
      params: { field: 'gone' },
    };
    const name = recordIssueNamer(definition.fields, formatters(zhCN));
    expect(name(found)).toBe(found);
  });
});

describe('the record workbench’s status line', () => {
  it('names the field a refused sort orders by by its label', async () => {
    const engine = new ViewEngine({
      definitions: [definition],
      store: new MemoryViewStore({
        instances: [
          {
            id: 'mine',
            definitionId: 'orders',
            title: 'Mine',
            scope: 'personal',
            revision: '1',
            config: recordConfig({
              sort: [{ field: 'warehouse', direction: 'ASC' }],
            }),
          },
        ],
      }),
      resolveSource: () => testSource(),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="mine"
        kinds={['record']}
      />,
    );
    const line = await screen.findByText(/cannot be sorted on/);
    expect(line.textContent).toContain('Warehouse');
    expect(line.textContent).not.toContain('warehouse');
  });
});
