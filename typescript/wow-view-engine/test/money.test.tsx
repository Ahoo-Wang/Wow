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
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  AggregationFunction,
  AggregationGroupType,
  type QueryModelDescriptor,
  type QuerySemanticType,
} from '@ahoo-wang/wow-client';
import {
  MemoryViewStore,
  ViewEngine,
  builtinFieldKinds,
  compileAnalysis,
  compileRecord,
  compileSummaries,
  numberFormatOf,
  pageSummaries,
  projectAnalysis,
  projectSummaries,
  recordProjection,
  serializeCsv,
  validateDefinition,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type FieldDefinition,
  type RecordColumnView,
  type RecordData,
} from '../src/index.js';
import { narrowDefinition } from '../src/capabilities/index.js';
import { numericOf, semanticText } from '../src/capabilities/fields.js';
import { currencyIssues } from '../src/analysis/currency.js';
import { cellText, csvCellText, formatNumber } from '../src/ui/display.js';
import { inRowCurrency } from '../src/ui/currency.js';
import { analysisCellText } from '../src/ui/analysis/tableColumns.js';
import { analysisFile } from '../src/ui/analysis/exportOffer.js';
import { defaultMessages } from '../src/ui/messages.js';
import { DataWorkbench, zhCN } from '../src/ui/index.js';
import {
  analysisConfig,
  ordersDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';
import { formattersFor } from './fixtures/columns.js';
import { describedField, ordersDescriptor } from './fixtures/descriptor.js';

const en = formattersFor(defaultMessages);
const zh = formattersFor(zhCN);
const EN = { locale: 'en-US', timeZone: 'UTC' };
const ZH = { locale: 'zh-CN', timeZone: 'UTC' };

const CONTEXT = { timeZone: 'UTC', now: new Date('2026-09-25T00:00:00Z') };

/** Orders whose amount is money in the currency each order holds. */
function moneyDefinition(
  overrides: Partial<DataViewDefinition> = {},
): DataViewDefinition {
  return ordersDefinition({
    fields: [
      { name: 'id', label: 'Order', kind: 'string', sortable: true },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'currency', label: 'Currency', kind: 'string' },
      {
        name: 'amount',
        label: 'Amount',
        kind: 'number',
        summary: ['SUM'],
        numeric: { type: 'money', scale: 2, currencyField: 'currency' },
      },
    ],
    analysis: {
      count: true,
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        {
          field: 'currency',
          groups: [AggregationGroupType.TERMS],
          functions: [],
          any: true,
          distinctCount: true,
        },
        {
          field: 'amount',
          groups: [],
          functions: [AggregationFunction.SUM],
        },
      ],
    },
    ...overrides,
  });
}

function sumConfig(
  overrides: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig {
  return analysisConfig({
    metrics: [
      {
        alias: 'total',
        type: 'NUMERIC',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'amount' },
      },
    ],
    chart: {
      type: 'bar',
      cartesian: { x: 'warehouse', series: [{ metric: 'total' }] },
    },
    ...overrides,
  });
}

describe('the descriptor’s numeric semantics (#3552)', () => {
  const narrowed = (semantic: QuerySemanticType, field?: FieldDefinition) => {
    const definition = ordersDefinition(
      field
        ? {
            fields: ordersDefinition().fields.map(one =>
              one.name === 'amount' ? field : one,
            ),
          }
        : {},
    );
    const descriptor: QueryModelDescriptor = ordersDescriptor({
      fields: [
        ...ordersDescriptor().fields.filter(one => one.path !== 'amount'),
        describedField('amount', { types: ['NUMBER'] as never, semantic }),
      ],
    });
    return narrowDefinition(
      definition,
      descriptor,
      builtinFieldKinds,
    ).definition.fields.find(one => one.name === 'amount');
  };

  it('writes DECIMAL and MONEY onto the field as its `numeric`', () => {
    expect(narrowed({ type: 'DECIMAL', scale: 3 })?.numeric).toEqual({
      type: 'decimal',
      scale: 3,
    });
    expect(
      narrowed({ type: 'MONEY', scale: 0, currency: 'jpy' })?.numeric,
    ).toEqual({ type: 'money', scale: 0, currency: 'JPY' });
    expect(
      narrowed({ type: 'MONEY', scale: 2, currencyField: 'currency' })?.numeric,
    ).toEqual({ type: 'money', scale: 2, currencyField: 'currency' });
  });

  it('keeps what the definition declares, and its own format wins', () => {
    const declared: FieldDefinition = {
      name: 'amount',
      label: 'Amount',
      kind: 'number',
      numberFormat: { style: 'currency', currency: 'USD' },
    };
    const field = narrowed(
      { type: 'MONEY', scale: 0, currency: 'JPY' },
      declared,
    );
    expect(numberFormatOf(field)).toEqual({
      style: 'currency',
      currency: 'USD',
    });
  });

  it('reads a semantic it could not write as none, rather than guessing', () => {
    expect(numericOf({ type: 'DECIMAL', scale: -1 })).toBeUndefined();
    expect(numericOf({ type: 'DECIMAL', scale: 1.5 })).toBeUndefined();
    expect(
      numericOf({ type: 'MONEY', scale: 2, currency: 'yuan' }),
    ).toBeUndefined();
    expect(
      numericOf({ type: 'MONEY', scale: 2, currencyField: 'a b' }),
    ).toBeUndefined();
    expect(numericOf({ type: 'TEMPORAL_DATE' })).toBeUndefined();
  });

  it('names every semantic in a mismatch — money is never called a date', () => {
    expect(semanticText({ type: 'TEMPORAL_DATE' })).toBe('date');
    expect(semanticText({ type: 'DECIMAL', scale: 2 })).toBe('decimal scale 2');
    expect(semanticText({ type: 'MONEY', scale: 2, currency: 'CNY' })).toBe(
      'money CNY scale 2',
    );
    expect(
      semanticText({ type: 'MONEY', scale: 2, currencyField: 'currency' }),
    ).toBe('money by currency scale 2');
  });

  it('says so when a date field is described as money', () => {
    const descriptor = ordersDescriptor({
      fields: [
        ...ordersDescriptor().fields,
        describedField('placedAt', {
          types: ['NUMBER'] as never,
          semantic: { type: 'MONEY', scale: 2, currency: 'CNY' },
        }),
      ],
    });
    const definition = ordersDefinition({
      fields: [
        ...ordersDefinition().fields,
        { name: 'placedAt', label: 'Placed', kind: 'datetime' },
      ],
    });
    const { findings } = narrowDefinition(
      definition,
      descriptor,
      builtinFieldKinds,
    );
    expect(
      findings.find(
        found => found.code === 'capability.field.temporal-mismatch',
      )?.params,
    ).toEqual({
      field: 'placedAt',
      declared: 'epoch MILLISECONDS',
      described: 'money CNY scale 2',
    });
  });

  it('refuses a `numeric` the engine cannot write, at admission', () => {
    const definition = ordersDefinition({
      fields: [
        ...ordersDefinition().fields.filter(one => one.name !== 'amount'),
        {
          name: 'amount',
          label: 'Amount',
          kind: 'number',
          numeric: { type: 'money', scale: 2, currency: 'yuan' },
        },
      ],
    });
    expect(
      validateDefinition(definition, builtinFieldKinds).map(
        found => found.code,
      ),
    ).toContain('definition.field.numeric-invalid');
    expect(
      validateDefinition(moneyDefinition(), builtinFieldKinds).filter(
        found => found.severity === 'error',
      ),
    ).toEqual([]);
  });
});

describe('formatting by scale and currency', () => {
  const format = (numeric: FieldDefinition['numeric']) =>
    numberFormatOf({ numeric });

  it('writes JPY with no decimals and CNY with two', () => {
    const jpy = format({ type: 'money', scale: 0, currency: 'JPY' });
    const cny = format({ type: 'money', scale: 2, currency: 'CNY' });
    expect(formatNumber(1234.5, jpy, 'en-US')).toBe('¥1,235');
    expect(formatNumber(1234.5, cny, 'zh-CN')).toBe('¥1,234.50');
    // In the surface's language: yen on a Chinese page is marked as yen.
    expect(formatNumber(1234.5, jpy, 'zh-CN')).toBe('JP¥1,235');
  });

  it('writes a decimal to its scale, in no currency', () => {
    const decimal = format({ type: 'decimal', scale: 3 });
    expect(formatNumber(1234.5, decimal, 'en-US')).toBe('1,234.500');
  });

  it('writes a record’s amount in the currency that record holds', () => {
    const column = {
      kind: 'number',
      numberFormat: format({
        type: 'money',
        scale: 2,
        currencyField: 'currency',
      }),
      currencyPath: 'currency',
    };
    const text = (row: RecordData) =>
      cellText(row.amount, inRowCurrency(column, row), en, EN);
    expect(text({ amount: 12, currency: 'USD' })).toBe('$12.00');
    expect(text({ amount: 10, currency: 'cny' })).toBe('CN¥10.00');
    // A currency the engine cannot write is never guessed: digits only.
    expect(text({ amount: 12, currency: 'dollars' })).toBe('12.00');
    expect(text({ amount: 12 })).toBe('12.00');
  });

  it('writes a nested amount in the currency beside it', () => {
    const column = {
      kind: 'number',
      numberFormat: { minimumFractionDigits: 2, maximumFractionDigits: 2 },
      currencyPath: 'price.currency',
    };
    const row = { price: { amount: 5, currency: 'EUR' } };
    expect(cellText(5, inRowCurrency(column, row), en, EN)).toBe('€5.00');
  });
});

describe('record views of row-currency money', () => {
  it('ask for the currency with the amount, shown or not', () => {
    const definition = moneyDefinition();
    const config = recordConfig({
      table: { columns: [{ field: 'id' }, { field: 'amount' }] },
    });
    expect(recordProjection(definition, config).include).toContain('currency');
    const query = compileRecord(
      definition,
      config,
      builtinFieldKinds,
      CONTEXT,
      { index: 1 },
    );
    expect(JSON.stringify(query)).toContain('currency');
  });

  it('total a page in its one currency, and refuse a total across several', () => {
    const definition = moneyDefinition();
    const config = recordConfig({
      summaries: [{ field: 'amount', fn: 'SUM' }],
    });
    const one = projectSummaries(definition, config, {
      scope: 'page',
      rows: [
        { amount: 1, currency: 'JPY' },
        { amount: 2, currency: 'JPY' },
      ],
    });
    expect(one.cells[0]).toMatchObject({
      value: 3,
      currency: { type: 'one', code: 'JPY' },
    });
    const mixed = pageSummaries(one.cells, [
      { key: 1, data: { amount: 1, currency: 'JPY' } },
      { key: 2, data: { amount: 2, currency: 'USD' } },
    ]);
    expect(mixed.cells[0]).toMatchObject({
      value: null,
      currency: { type: 'mixed', count: 2 },
    });
  });

  it('ask the whole range which currencies it holds', () => {
    const definition = moneyDefinition();
    const config = recordConfig({
      summaries: [{ field: 'amount', fn: 'SUM' }],
    });
    const query = compileSummaries(
      definition,
      config,
      builtinFieldKinds,
      CONTEXT,
    );
    expect(query?.metrics.map(metric => metric.alias)).toEqual([
      'amount_sum',
      'amount_sum__currencies',
      'amount_sum__currency',
    ]);
    const total = projectSummaries(definition, config, {
      scope: 'total',
      result: [
        {
          amount_sum: 30,
          amount_sum__currencies: 2,
          amount_sum__currency: 'JPY',
        },
      ],
    });
    expect(total.cells[0]).toMatchObject({
      value: null,
      currency: { type: 'mixed', count: 2 },
    });
  });

  it('write the amount raw in a file, its currency in a column beside it', () => {
    const column: RecordColumnView & { currencyOf?: RecordColumnView } = {
      field: 'amount',
      label: 'Amount',
      kind: 'number',
      cell: 'number',
      sortable: false,
      numberFormat: { minimumFractionDigits: 2, maximumFractionDigits: 2 },
      numeric: { type: 'money', scale: 2, currencyField: 'currency' },
      currencyPath: 'currency',
    };
    expect(csvCellText(1204.5, column, en, EN)).toBe('1204.5');
    // A format the author wrote stays the file's too.
    expect(
      csvCellText(
        1204.5,
        { numberFormat: { style: 'currency', currency: 'USD' } },
        en,
        EN,
      ),
    ).toBe('$1,204.50');
    const text = serializeCsv(
      [{ amount: 1204.5, currency: 'USD' }],
      [
        column,
        {
          ...column,
          field: 'currency',
          label: 'Amount (currency)',
          currencyOf: column,
        },
      ],
      (value, one) =>
        one.currencyOf ? String(value) : csvCellText(value, one, en, EN),
    );
    expect(text).toContain('Amount,Amount (currency)\r\n1204.5,USD\r\n');
  });
});

describe('analyses of row-currency money', () => {
  it('ask each money metric which currencies its records hold', () => {
    const query = compileAnalysis(
      moneyDefinition(),
      sumConfig(),
      builtinFieldKinds,
      CONTEXT,
    );
    expect(query.metrics.map(metric => metric.alias)).toEqual([
      'total',
      'total__currencies',
      'total__currency',
    ]);
  });

  it('ask nothing the source cannot answer, and say the total is unchecked', () => {
    const definition = moneyDefinition({
      analysis: {
        ...moneyDefinition().analysis!,
        fields: moneyDefinition().analysis!.fields.map(field =>
          field.field === 'currency'
            ? { ...field, any: false, distinctCount: false }
            : field,
        ),
      },
    });
    const query = compileAnalysis(
      definition,
      sumConfig(),
      builtinFieldKinds,
      CONTEXT,
    );
    expect(query.metrics.map(metric => metric.alias)).toEqual(['total']);
    expect(
      currencyIssues(definition, sumConfig(), []).map(found => found.code),
    ).toEqual(['analysis.metric.currency-unchecked']);
  });

  it('writes a result in its one currency, axis and all', () => {
    const view = projectAnalysis(moneyDefinition(), sumConfig(), [
      {
        warehouse: 'A',
        total: 1234.5,
        total__currencies: 1,
        total__currency: 'JPY',
      },
      {
        warehouse: 'B',
        total: 10,
        total__currencies: 1,
        total__currency: 'JPY',
      },
    ]);
    const total = view.columns.find(column => column.alias === 'total')!;
    expect(total.numberFormat).toMatchObject({
      style: 'currency',
      currency: 'JPY',
    });
    expect(
      analysisCellText(view.rows[0].total, total, en, EN, view.rows[0]),
    ).toBe('¥1,234.50');
  });

  it('shows no sum where a group mixes currencies, and says why', () => {
    const definition = moneyDefinition();
    const view = projectAnalysis(definition, sumConfig(), [
      {
        warehouse: 'A',
        total: 1234.5,
        total__currencies: 2,
        total__currency: 'JPY',
      },
      {
        warehouse: 'B',
        total: 10,
        total__currencies: 1,
        total__currency: 'USD',
      },
    ]);
    const total = view.columns.find(column => column.alias === 'total')!;
    expect(view.rows[0].total).toBeNull();
    // What is drawn is in one currency, so the axis is in it.
    expect(total.numberFormat?.currency).toBe('USD');
    expect(analysisCellText(null, total, zh, ZH, view.rows[0])).toBe(
      '多种货币',
    );
    expect(analysisCellText(10, total, en, EN, view.rows[1])).toBe('$10.00');
    expect(currencyIssues(definition, sumConfig(), view.rows)).toEqual([
      expect.objectContaining({
        code: 'analysis.result.mixed-currency',
        severity: 'warning',
        params: {
          metric: 'total',
          field: 'currency',
          currencyField: 'currency',
        },
      }),
    ]);
  });

  it('reads each currency in its own row once grouped by currency', () => {
    const config = sumConfig({
      groups: [{ alias: 'currency', field: 'currency', type: 'TERMS' }],
      chart: {
        type: 'bar',
        cartesian: { x: 'currency', series: [{ metric: 'total' }] },
      },
    });
    const view = projectAnalysis(moneyDefinition(), config, [
      {
        currency: 'JPY',
        total: 1234.5,
        total__currencies: 1,
        total__currency: 'JPY',
      },
      {
        currency: 'CNY',
        total: 10,
        total__currencies: 1,
        total__currency: 'CNY',
      },
    ]);
    const total = view.columns.find(column => column.alias === 'total')!;
    const texts = view.rows.map(row =>
      analysisCellText(row.total, total, zh, ZH, row),
    );
    // The field's scale holds for every currency it is in (#3552).
    expect(texts).toEqual(['JP¥1,234.50', '¥10.00']);
    expect(currencyIssues(moneyDefinition(), config, view.rows)).toEqual([]);
    // The file: the number itself, and its currency beside it.
    const file = analysisFile(view, zh, ZH);
    expect(file.rows).toEqual([
      ['JPY', '1234.5', 'JPY'],
      ['CNY', '10', 'CNY'],
    ]);
    expect(file.columns.map(column => column.label).slice(2)).toEqual([
      expect.stringContaining('（币种）'),
    ]);
  });

  it('keeps a derived ratio of mixed amounts off the screen too', () => {
    const config = sumConfig({
      metrics: [
        ...sumConfig().metrics,
        { alias: 'orders', type: 'COUNT' },
        {
          alias: 'aov',
          type: 'DERIVED',
          expression: {
            type: 'BINARY',
            operator: 'DIVIDE',
            left: { type: 'METRIC_REF', metric: 'total' },
            right: { type: 'METRIC_REF', metric: 'orders' },
          } as never,
          format: { style: 'currency' },
        },
      ],
    });
    const view = projectAnalysis(moneyDefinition(), config, [
      {
        warehouse: 'A',
        total: 30,
        orders: 3,
        aov: 10,
        total__currencies: 2,
        total__currency: 'JPY',
      },
      {
        warehouse: 'B',
        total: 30,
        orders: 3,
        aov: 10,
        total__currencies: 1,
        total__currency: 'USD',
      },
    ]);
    const aov = view.columns.find(column => column.alias === 'aov')!;
    expect(view.rows[0].aov).toBeNull();
    expect(analysisCellText(null, aov, en, EN, view.rows[0])).toBe(
      'Mixed currencies',
    );
    expect(analysisCellText(10, aov, en, EN, view.rows[1])).toBe('$10.00');
  });
});

describe('what a result grouped by its currency says', () => {
  it('nothing, though its whole still holds several', () => {
    const config = sumConfig({
      groups: [{ alias: 'currency', field: 'currency', type: 'TERMS' }],
    });
    expect(
      currencyIssues(moneyDefinition(), config, [
        {
          currency: 'JPY',
          total: 1,
          total__currencies: 1,
          total__currency: 'JPY',
        },
        { total: null, total__currencies: 2, total__currency: 'JPY' },
      ]),
    ).toEqual([]);
  });
});

describe('the analysis workbench over mixed currencies', () => {
  afterEach(cleanup);

  /** Two warehouses, one of them selling in two currencies, and the whole. */
  function source() {
    return testSource({
      aggregate: vi.fn(
        (query: { groupBy?: { field?: string }[] }): Promise<RecordData[]> => {
          const byCurrency = query.groupBy?.some(
            group => group.field === 'currency',
          );
          if (byCurrency)
            return Promise.resolve([
              {
                warehouse: 'A',
                currency: 'JPY',
                total: 100,
                total__currencies: 1,
                total__currency: 'JPY',
              },
              {
                warehouse: 'A',
                currency: 'USD',
                total: 5,
                total__currencies: 1,
                total__currency: 'USD',
              },
            ]);
          return Promise.resolve([
            {
              warehouse: 'A',
              total: 105,
              total__currencies: 2,
              total__currency: 'JPY',
            },
            {
              warehouse: 'B',
              total: 7,
              total__currencies: 1,
              total__currency: 'USD',
            },
          ]);
        },
      ),
    });
  }

  it('says which groups have no total, and groups by currency on one press', async () => {
    const from = source();
    const engine = new ViewEngine({
      definitions: [moneyDefinition()],
      store: new MemoryViewStore({
        instances: [
          {
            id: 'by-warehouse',
            definitionId: 'orders',
            title: 'By warehouse',
            scope: 'personal',
            revision: '1',
            config: sumConfig({ layout: 'table' }),
          },
        ],
      }),
      resolveSource: () => from,
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="by-warehouse"
        kinds={['analysis']}
        locale="en-US"
      />,
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });

    const strip = await waitFor(() => {
      const found = document.querySelector('[data-slot="analysis-currency"]');
      expect(found).not.toBeNull();
      return found as HTMLElement;
    });
    expect(strip.textContent).toContain('several currencies');
    expect(await screen.findByText('Mixed currencies')).toBeTruthy();
    expect(screen.getByText('$7.00')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Group by Currency' }));
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="analysis-currency"]'),
      ).toBeNull(),
    );
    expect(await screen.findByText('¥100.00')).toBeTruthy();
    expect(screen.getByText('$5.00')).toBeTruthy();
    expect(screen.queryByText('Mixed currencies')).toBeNull();
  });
});

describe('a derived amount over money in each record’s currency', () => {
  it('is admitted without a currency of its own: its rows say theirs', async () => {
    const { validateAnalysis } = await import('../src/index.js');
    const config = sumConfig({
      metrics: [
        ...sumConfig().metrics,
        { alias: 'orders', type: 'COUNT' },
        {
          alias: 'aov',
          type: 'DERIVED',
          expression: {
            type: 'BINARY',
            operator: 'DIVIDE',
            left: { type: 'METRIC_REF', metric: 'total' },
            right: { type: 'METRIC_REF', metric: 'orders' },
          } as never,
          format: { style: 'currency' },
        },
      ],
    });
    const codes = (definition: DataViewDefinition) =>
      validateAnalysis(definition, config, builtinFieldKinds).map(
        found => found.code,
      );
    expect(codes(moneyDefinition())).not.toContain(
      'analysis.derived.currency-unknown',
    );
    const plain = moneyDefinition({
      fields: moneyDefinition().fields.map(field =>
        field.name === 'amount' ? { ...field, numeric: undefined } : field,
      ),
    });
    expect(codes(plain)).toContain('analysis.derived.currency-unknown');
  });
});
