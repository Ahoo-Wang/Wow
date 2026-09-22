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

import { describe, expect, it } from 'vitest';
import type { AnalysisMetric } from '../src/index.js';
import type { AnalysisFieldOption } from '../src/react/index.js';
import type { MessageFormatters, ViewMessages } from '../src/ui/index.js';
import {
  metricName,
  metricReference,
  type MetricNaming,
} from '../src/ui/analysis/editing.js';
import { en } from '../src/ui/messages/en.js';
import { zhCN } from '../src/ui/messages/zh-CN.js';
import { formatMessage } from '../src/ui/messages.js';

function words(catalogue: ViewMessages): MessageFormatters {
  return {
    label: (key, params, fallback) => {
      const found = formatMessage(catalogue, key, params);
      return found === key && fallback !== undefined ? fallback : found;
    },
    issue: () => '',
    issues: () => '',
  };
}

function field(name: string, label: string): AnalysisFieldOption {
  return {
    field: name,
    label,
    groups: [],
    functions: ['SUM', 'AVG'],
    dateUnits: [],
    distinctCount: false,
    percentile: true,
    any: false,
    missingKey: false,
  };
}

const FIELDS = [field('amount', 'Amount'), field('cost', 'Cost')];

const sum: AnalysisMetric = {
  alias: 'amount',
  type: 'NUMERIC',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
};
const average: AnalysisMetric = {
  alias: 'amount_1',
  type: 'NUMERIC',
  function: 'AVG',
  expression: { type: 'FIELD', field: 'amount' },
};
const count: AnalysisMetric = { alias: 'orders', type: 'COUNT' };

function naming(...metrics: AnalysisMetric[]): MetricNaming {
  return { fields: FIELDS, metrics };
}

/**
 * How a metric is named *away from its own card* (C4): 「只保留」, the sort,
 * a derived metric's operands and every label that names a card. There is
 * no summary control beside those, so they say the sentence the result
 * column is headed with, while the card's own title stays the bare field.
 */
describe('metricReference', () => {
  const en_ = words(en);

  it('tells two summaries of one field apart, where the card title cannot', () => {
    const analysis = naming(sum, average);
    expect(metricName(analysis, sum, en_)).toBe('Amount');
    expect(metricName(analysis, average, en_)).toBe('Amount');
    expect(metricReference(analysis, sum, en_)).toBe('Sum of Amount');
    expect(metricReference(analysis, average, en_)).toBe('Average of Amount');
  });

  it('composes in each language’s own order', () => {
    const analysis = naming(sum);
    expect(metricReference(analysis, sum, words(zhCN))).toBe('Amount 的 合计');
  });

  it('says the record count in its one word, with no summary appended', () => {
    expect(metricReference(naming(count), count, en_)).toBe('Record count');
  });

  it('says a name the analyst gave, and nothing more', () => {
    const named: AnalysisMetric = { ...sum, label: 'Revenue' };
    expect(metricReference(naming(named), named, en_)).toBe('Revenue');
  });

  it('marks a percentile as approximate, as its column header does', () => {
    const p95: AnalysisMetric = {
      alias: 'p95',
      type: 'PERCENTILE',
      percentile: 95,
      expression: { type: 'FIELD', field: 'amount' },
    };
    expect(metricReference(naming(p95), p95, en_)).toBe(
      `≈ ${formatMessage(en, 'label.summary.of', {
        fn: en['label.summary.fn.PERCENTILE'],
        field: 'Amount',
      })}`,
    );
  });

  it('appends the summary to a formula, which is a summarised number', () => {
    const margin: AnalysisMetric = {
      alias: 'margin',
      type: 'NUMERIC',
      function: 'SUM',
      expression: {
        type: 'BINARY',
        operator: 'SUBTRACT',
        left: { type: 'FIELD', field: 'amount' },
        right: { type: 'FIELD', field: 'cost' },
      },
    };
    expect(metricReference(naming(margin), margin, en_)).toBe(
      'Sum of Amount − Cost',
    );
  });

  it('says a derived metric by its own words, with nothing appended', () => {
    const perOrder: AnalysisMetric = {
      alias: 'per_order',
      type: 'DERIVED',
      expression: {
        type: 'BINARY',
        operator: 'DIVIDE',
        left: { type: 'METRIC_REF', metric: 'amount' },
        right: { type: 'METRIC_REF', metric: 'orders' },
      },
    };
    // Its operands are references to other metrics, and say their summary,
    // as the derived column's header says them.
    expect(metricReference(naming(sum, count, perOrder), perOrder, en_)).toBe(
      'Sum of Amount ÷ Record count',
    );
  });
});
