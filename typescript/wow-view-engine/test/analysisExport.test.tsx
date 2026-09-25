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
 * 「导出数据…」 over an analysis (D25 Q28): the file is the rows as the table
 * reads them — groups first, then metrics, headed and read as the table
 * heads and reads them, the first N groups only, the totals row last where
 * it is shown, nothing a chart pads or folds in — whether the table or the
 * chart is on screen. The window is the record export's (D14) without its
 * 「所有／选中」.
 */

import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  projectAnalysis,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type RecordData,
  type ViewSource,
} from '../src/index.js';
import {
  DataWorkbench,
  zhCN,
  type WorkbenchFeatures,
} from '../src/ui/index.js';
import { analysisFile } from '../src/ui/analysis/exportOffer.js';
import {
  defaultMessages,
  formatIssue,
  formatMessage,
  type ViewMessages,
} from '../src/ui/messages.js';
import type { MessageFormatters } from '../src/ui/MessagesProvider.js';
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';

afterEach(cleanup);

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

/** Warehouses named by the definition, and an amount in dollars. */
function definition(): DataViewDefinition {
  const base = ordersDefinition();
  return ordersDefinition({
    fields: base.fields.map(field =>
      field.name === 'warehouse'
        ? {
            ...field,
            kind: 'enum',
            options: [
              { value: 'CN', label: 'China' },
              { value: 'US', label: 'United States' },
              { value: 'DE', label: 'Germany' },
            ],
          }
        : field.name === 'amount'
          ? {
              ...field,
              numberFormat: { style: 'currency', currency: 'USD' },
            }
          : field,
    ),
  });
}

/**
 * Two groups asked for, the metrics dragged in front of the dimension, the
 * totals row on: the shape every rule of the file has something to say about.
 */
const config = (overrides: Partial<AnalysisViewConfig> = {}) =>
  analysisConfig({
    metrics: [
      { alias: 'orders', type: 'COUNT' },
      {
        alias: 'amount_sum',
        type: 'NUMERIC',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'amount' },
      },
    ],
    table: {
      columns: [{ alias: 'amount_sum' }, { alias: 'orders' }],
      totals: true,
    },
    limit: 2,
    ...overrides,
  });

/** Three warehouses — one more than the two asked for — and the whole. */
const GROUPS: RecordData[] = [
  { warehouse: 'CN', orders: 1204, amount_sum: 5300.5 },
  { warehouse: 'US', orders: 7, amount_sum: 12 },
  { warehouse: 'DE', orders: 3, amount_sum: 1 },
];
const WHOLE: RecordData = { orders: 1214, amount_sum: 5313.5 };

const HEADER = 'Warehouse,Sum of Amount,Record count';
const FILE = [
  HEADER,
  'China,"$5,300.50","1,204"',
  'United States,$12.00,7',
  'Total,"$5,313.50","1,214"',
].join('\r\n');

describe('analysisFile', () => {
  const messages = formatters(defaultMessages);

  it('is the table’s reading: groups first, the first N, the totals last', () => {
    const view = projectAnalysis(definition(), config(), GROUPS, [WHOLE]);
    // The table draws the metrics first, as dragged; the file puts the
    // dimension first and keeps the metrics in the table's order.
    expect(view.columns.map(column => column.alias)).toEqual([
      'amount_sum',
      'orders',
      'warehouse',
    ]);
    const file = analysisFile(view, messages, { timeZone: 'UTC' });

    expect(file.columns.map(column => column.label)).toEqual(HEADER.split(','));
    // Each cell as its table cell reads: the warehouse by its name, the
    // amount in its field's currency, the count grouped; the probe row
    // (Germany) is not a group anybody asked for.
    expect(file.text).toBe(`\uFEFF${FILE}\r\n`);
  });

  it('says 「合计」 in the reader’s language, and leaves out a totals row not shown', () => {
    const view = projectAnalysis(
      definition(),
      config({ table: { columns: [] } }),
      GROUPS.slice(0, 2),
    );
    const file = analysisFile(view, formatters(zhCN), {});
    expect(file.rows).toHaveLength(2);
    expect(file.rows[1]?.[0]).toBe('United States');

    const totalled = projectAnalysis(
      definition(),
      config(),
      GROUPS.slice(0, 2),
      [WHOLE],
    );
    expect(analysisFile(totalled, formatters(zhCN), {}).rows[2]?.[0]).toBe(
      '合计',
    );
  });

  it('neutralizes a formula a group value reads as, never a negative figure', () => {
    // A warehouse code nobody named, read as the code itself, and sums below
    // zero read in their currency — 「-$12.00」 starts with a minus and is
    // still a number.
    const groups: RecordData[] = [
      { warehouse: '=cmd|calc', orders: 2, amount_sum: -12 },
      { warehouse: 'US', orders: 1, amount_sum: -1204.5 },
    ];
    const whole: RecordData = { orders: 3, amount_sum: -1216.5 };
    const view = projectAnalysis(definition(), config(), groups, [whole]);

    const file = analysisFile(view, messages, {});
    expect(file.text).toBe(
      `\uFEFF${[
        HEADER,
        "'=cmd|calc,-$12.00,2",
        'United States,"-$1,204.50",1',
        'Total,"-$1,216.50",3',
      ].join('\r\n')}\r\n`,
    );
    // The rows are the reading, before the writer neutralizes anything.
    expect(file.rows[0]?.[0]).toBe('=cmd|calc');

    const kept = analysisFile(
      view,
      messages,
      {},
      { neutralizeFormulas: false },
    );
    expect(kept.text.split('\r\n')[1]).toBe('=cmd|calc,-$12.00,2');
  });

  it('holds one row with no dimension: the whole range', () => {
    const view = projectAnalysis(
      definition(),
      config({ groups: [], table: { columns: [], totals: true } }),
      [WHOLE],
      [WHOLE],
    );
    const file = analysisFile(view, messages, {});
    expect(file.rows).toEqual([['1,214', '$5,313.50']]);
  });
});

/** The browser's half of a download, which jsdom has none of. */
function stubObjectUrls(fail = false) {
  const createObjectURL = vi.fn((blob: Blob) => {
    void blob;
    if (fail) throw new Error('disk full');
    return 'blob:analysis';
  });
  Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() });
  return createObjectURL;
}

function source(groups: RecordData[] = GROUPS): ViewSource {
  return testSource({
    aggregate: vi.fn((query: { groupBy?: unknown; limit?: number }) =>
      Promise.resolve(
        query.groupBy === undefined
          ? [WHOLE]
          : groups.slice(0, query.limit ?? groups.length),
      ),
    ),
  });
}

function show({
  from = source(),
  features,
}: { from?: ViewSource; features?: WorkbenchFeatures } = {}) {
  const engine = new ViewEngine({
    definitions: [definition()],
    store: new MemoryViewStore({
      instances: [
        {
          id: 'orders-1',
          definitionId: 'orders',
          title: 'By warehouse',
          scope: 'personal',
          revision: '1',
          config: config(),
        },
      ],
    }),
    resolveSource: () => from,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
      kinds={['analysis']}
      {...(features ? { features } : {})}
    />,
  );
  return { user: userEvent.setup({ pointerEventsCheck: 0 }) };
}

const exportButton = () => screen.findByRole('button', { name: 'Export' });

async function exported(
  user: ReturnType<typeof userEvent.setup>,
  createObjectURL: ReturnType<typeof stubObjectUrls>,
): Promise<string> {
  const before = createObjectURL.mock.calls.length;
  await user.click(await exportButton());
  const dialog = await screen.findByRole('dialog', { name: 'Export' });
  await user.click(within(dialog).getByRole('button', { name: 'Export' }));
  await waitFor(() =>
    expect(createObjectURL).toHaveBeenCalledTimes(before + 1),
  );
  const blob = createObjectURL.mock.calls[before][0];
  await user.click(
    dialog.querySelector<HTMLElement>('[data-slot="export-close"]')!,
  );
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  return blob.text();
}

describe('「导出」 on the analysis result’s toolbar', () => {
  it('says what the file holds, with no scope to pick, and hands it over', async () => {
    const createObjectURL = stubObjectUrls();
    const { user } = show();
    await user.click(await exportButton());

    const dialog = await screen.findByRole('dialog', { name: 'Export' });
    // No 「所有／选中」: the groups are in hand.
    expect(within(dialog).queryByRole('radio')).toBeNull();
    expect(dialog.textContent).toContain(
      'The first 2 groups (there are more; the file leaves them out), then a totals row',
    );
    expect(dialog.textContent).toContain('Conditions: All records');
    expect(dialog.textContent).toContain(
      '3 columns: Warehouse, Sum of Amount, Record count',
    );
    expect(dialog.textContent).toMatch(
      /File: By warehouse-\d{4}-\d{2}-\d{2}\.csv/,
    );

    await user.click(within(dialog).getByRole('button', { name: 'Export' }));
    await waitFor(() => expect(createObjectURL).toHaveBeenCalledTimes(1));
    expect(await createObjectURL.mock.calls[0][0].text()).toBe(`${FILE}\r\n`);
    // Straight to the outcome, which says what went.
    expect(within(dialog).getByRole('heading').textContent).toBe('Export');
    expect(dialog.textContent).toContain(
      'Exported: The first 2 groups (there are more; the file leaves them out), then a totals row',
    );
  });

  it('hands over the same file whether the table or the chart is showing', async () => {
    const createObjectURL = stubObjectUrls();
    const { user } = show();
    const fromTable = await exported(user, createObjectURL);

    await user.click(screen.getByRole('button', { name: 'Chart' }));
    await waitFor(() =>
      expect(document.querySelector('[data-slot="analysis-table"]')).toBeNull(),
    );
    const fromChart = await exported(user, createObjectURL);

    expect(fromChart).toBe(fromTable);
    expect(fromChart).toBe(`${FILE}\r\n`);
  });

  it('says why a file did not go, and tries again', async () => {
    stubObjectUrls(true);
    const { user } = show();
    await user.click(await exportButton());
    const dialog = await screen.findByRole('dialog', { name: 'Export' });
    await user.click(within(dialog).getByRole('button', { name: 'Export' }));

    await waitFor(() => expect(dialog.textContent).toContain('disk full'));
    const createObjectURL = stubObjectUrls();
    await user.click(within(dialog).getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(createObjectURL).toHaveBeenCalledTimes(1));
    expect(dialog.textContent).toContain('Exported:');
  });

  it('is not there over no group, nor where the host turned exports off', async () => {
    show({ from: source([]) });
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="analysis-empty"]'),
      ).not.toBeNull(),
    );
    expect(screen.queryByRole('button', { name: 'Export' })).toBeNull();
    cleanup();

    show({ features: { export: false } });
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="analysis-table"]'),
      ).not.toBeNull(),
    );
    expect(screen.queryByRole('button', { name: 'Export' })).toBeNull();
  });
});
