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

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { FilterSummaryItem, FilterValue } from '../src/index.js';
import { FilterValueEditor, ViewSurface } from '../src/ui/index.js';
import type { MessageFormatters } from '../src/ui/index.js';
import { summaryText } from '../src/ui/summary.js';
import { en } from '../src/ui/messages/en.js';
import { zhCN } from '../src/ui/messages/zh-CN.js';
import { formatMessage, type MessageKey } from '../src/ui/messages.js';

afterEach(cleanup);

const say = (key: MessageKey, params: Record<string, string | number>) =>
  formatMessage(en, key, params, 'en');

/**
 * English says 「1 record」 and 「2 records」; the catalogue words each form
 * and the language's rule picks one by `count` (`pluralForm`), so no caller
 * decides it. The console's walkthrough read 「1 records in all」 and 「last 30
 * day」 (compensation view-engine-rebuild.md, W10).
 */
describe('a count picks its form', () => {
  it('reads the one form for one and the general form otherwise', () => {
    expect(say('label.pagination.total', { count: 1 })).toBe('1 record in all');
    expect(say('label.pagination.total', { count: 0 })).toBe(
      '0 records in all',
    );
    expect(say('label.pagination.total', { count: 2 })).toBe(
      '2 records in all',
    );
  });

  it('follows the language: Chinese has one form, Polish has three', () => {
    expect(
      formatMessage(zhCN, 'label.pagination.total', { count: 1 }, 'zh-CN'),
    ).toBe('共 1 条记录');
    const polish = {
      files: '{count} plików',
      'files-one': '{count} plik',
      'files-few': '{count} pliki',
    };
    const files = (count: number) =>
      formatMessage(polish, 'files', { count }, 'pl');
    expect([1, 3, 5, 22].map(files)).toEqual([
      '1 plik',
      '3 pliki',
      '5 plików',
      '22 pliki',
    ]);
  });

  it('keeps the general form without a number to count by', () => {
    expect(formatMessage(en, 'label.export.rows', { count: '1' })).toBe(
      '1 records',
    );
    expect(formatMessage(en, 'label.export.rows')).toBe('{count} records');
    // A locale the runtime cannot read counts as English.
    expect(
      formatMessage(en, 'label.export.rows', { count: 1 }, 'not a locale!'),
    ).toBe('1 record');
  });

  /** Every sentence that read 「1 <plural>」 before, one case each. */
  it.each<[MessageKey, Record<string, string | number>, string]>([
    ['label.pagination.total', {}, '1 record in all'],
    ['label.export.rows', {}, '1 record'],
    ['label.export.columns', { names: 'Order' }, '1 column: Order'],
    ['label.export.done', {}, '1 record exported'],
    ['label.export.groups', {}, '1 group'],
    [
      'label.export.groups-first',
      {},
      'The first group (there are more; the file leaves them out)',
    ],
    ['label.filter.value-count', { value: 'Paid' }, 'Paid (1 record)'],
    ['label.status.groups', {}, '1 group'],
    ['label.conflict.summary.dashboard', {}, '1 panel'],
    ['label.view.warnings-count', {}, '1 thing worth noting'],
    ['label.value.items', {}, '1 item'],
    ['label.value.fields', {}, '1 field'],
    ['label.panel.columns', {}, '1 column'],
    ['label.panel.rows', {}, '1 row'],
    ['label.bulk.more-reasons', {}, 'one more reason'],
    [
      'label.analysis.caption',
      { seconds: '0.2' },
      'Showing 1 group · took 0.2 s',
    ],
    ['label.chart.treemap.omitted', {}, '1 group not above zero is not drawn'],
    [
      'label.chart.boxplot.omitted',
      {},
      '1 group without all five numbers is not drawn',
    ],
    ['label.chart.radar.omitted', {}, '1 more group is only in the table'],
    [
      'label.chart.parallel.omitted',
      {},
      '1 group missing a number is not drawn',
    ],
    [
      'label.chart.sentence',
      { high: 'A', highValue: '3', low: 'A', lowValue: '3' },
      '1 group; highest A, 3; lowest A, 3.',
    ],
    [
      'label.chart.sentence.scatter',
      { x: 'X', xLow: '1', xHigh: '1', y: 'Y', yLow: '2', yHigh: '2' },
      '1 point; X from 1 to 1, Y from 2 to 2.',
    ],
    [
      'label.chart.sentence.boxplot',
      { high: 'A', highValue: '3', low: 'A', lowValue: '3' },
      '1 group; highest median A, 3; lowest median A, 3.',
    ],
    [
      'label.chart.sentence.sankey',
      { high: 'A → B', highValue: '3' },
      '1 flow; the largest A → B, 3.',
    ],
    ['label.chart.map.unplaced', {}, '1 region is not on this map'],
    ['label.chart.map.omitted', {}, '1 region without a number is not drawn'],
    [
      'label.chart.themeRiver.uncertain',
      {},
      '1 point has no row and is drawn as 0; the rows may be cut short',
    ],
    ['label.relative.unit.day', {}, 'day'],
  ])('%s says one as one', (key, params, one) => {
    expect(say(key, { ...params, count: 1 })).toBe(one);
    expect(say(key, { ...params, count: 2 })).not.toBe(one);
  });
});

describe('a relative window counts its unit', () => {
  const words: MessageFormatters = {
    label: (key, params) => formatMessage(en, key, params, 'en'),
    issue: () => '',
    issues: () => '',
  };
  const window = (amount: number, unit: 'day' | 'hour', bound = 'window') =>
    summaryText(
      {
        path: ['children', 0],
        text: '',
        unresolved: false,
        field: 'createdAt',
        label: 'Created',
        kind: 'datetime',
        operator: bound === 'window' ? 'BETWEEN' : 'LTE',
        value: {
          kind: 'relative',
          amount,
          unit,
          direction: 'past',
          bound: bound as 'window' | 'instant',
        },
      } as FilterSummaryItem,
      words,
      {},
    );

  it('in the summary: 「last 1 day」, 「last 30 days」', () => {
    expect(window(1, 'day')).toBe('Created between last 1 day');
    expect(window(30, 'day')).toBe('Created between last 30 days');
    expect(window(1, 'hour', 'instant')).toBe('Created at most 1 hour ago');
  });

  it('in the editor, as the amount beside it counts', () => {
    const value = { type: 'relative', amount: 1, unit: 'day' };
    function Editor({ current }: { current: FilterValue }) {
      return (
        <ViewSurface>
          <FilterValueEditor
            kind="datetime"
            editor={{ input: 'relativeDate' }}
            value={current}
            label="Created"
            onChange={() => undefined}
          />
        </ViewSurface>
      );
    }
    const { rerender } = render(
      <Editor current={value as unknown as FilterValue} />,
    );
    const unit = () => screen.getByLabelText('Created unit').textContent;
    expect(unit()).toContain('day');
    expect(unit()).not.toContain('days');
    rerender(
      <Editor current={{ ...value, amount: 30 } as unknown as FilterValue} />,
    );
    expect(unit()).toContain('days');
  });
});
