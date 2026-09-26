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
 * Every analysis finding says what the reader sees — a column as it is
 * headed, a field by its label, a summary, a unit and a dimension type in
 * the tray's words — and never the key the kernel addresses it by. Each case
 * is raised by the kernel itself, so a param that moves shows up here, and
 * is said in both catalogues. Chart findings have their own suite
 * (`chartIssueNames.test.tsx`).
 */

import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  validateAnalysis,
  type AnalysisViewConfig,
  type Issue,
} from '../src/index.js';
import { zhCN } from '../src/ui/index.js';
import {
  analysisIssueNamer,
  type AnalysisNaming,
} from '../src/ui/analysis/issueNames.js';
import {
  defaultMessages,
  formatIssue,
  formatMessage,
  type ViewMessages,
} from '../src/ui/messages.js';
import type { MessageFormatters } from '../src/ui/MessagesProvider.js';
import {
  analysisCapability,
  analysisDefinition,
  analysisKernelConfig,
} from './fixtures/analysis.js';

const { TERMS, DATE_HISTOGRAM } = AggregationGroupType;
const { MAX } = AggregationFunction;

/** The fixture, with a time that has a latest: a moment a metric can be. */
const definition = analysisDefinition({
  analysis: {
    ...analysisCapability,
    fields: analysisCapability.fields.map(entry =>
      entry.field === 'createdAt'
        ? {
            ...entry,
            groups: [DATE_HISTOGRAM, TERMS],
            functions: [MAX],
            dateUnits: [AggregationDateUnit.MONTH],
          }
        : entry,
    ),
  },
});

/** Keys no word on screen contains, so a leak is a substring match. */
const KEYS = [
  'g_wh',
  'm_n',
  'm_last',
  'm_d',
  'warehouse',
  'createdAt',
  'MIN',
  'HISTOGRAM',
  'DAY',
];

/** A table over these parts, sorted by nothing unless asked. */
function config(overrides: Partial<AnalysisViewConfig>): AnalysisViewConfig {
  return analysisKernelConfig({
    groups: [{ type: 'TERMS', field: 'warehouse', alias: 'g_wh' }],
    metrics: [{ type: 'COUNT', alias: 'm_n' }],
    sort: [],
    layout: 'table',
    ...overrides,
  });
}

const latest = {
  type: 'NUMERIC',
  alias: 'm_last',
  function: 'MAX',
  expression: { type: 'FIELD', field: 'createdAt' },
} as const;

const CASES: {
  code: string;
  config: AnalysisViewConfig;
  en: string;
  zh: string;
}[] = [
  {
    code: 'analysis.sort.duplicate',
    config: config({
      sort: [
        { alias: 'm_n', direction: 'DESC' },
        { alias: 'm_n', direction: 'ASC' },
      ],
    }),
    en: 'The sort already orders by Record count.',
    zh: '排序已经按「记录数」排过了。',
  },
  {
    code: 'analysis.group.unsupported',
    config: config({
      groups: [
        { type: 'HISTOGRAM', field: 'warehouse', alias: 'g_wh', interval: 1 },
      ],
    }),
    en: 'Warehouse cannot be grouped this way: By number range.',
    zh: '「Warehouse」不能按数值区间分组。',
  },
  {
    code: 'analysis.group.unit-unsupported',
    config: config({
      groups: [
        {
          type: 'DATE_HISTOGRAM',
          field: 'createdAt',
          alias: 'g_wh',
          unit: 'DAY',
        },
      ],
    }),
    en: 'This dimension cannot group this way: By day.',
    zh: '这个维度不能按日分组。',
  },
  {
    // The earliest of a moment, as the summary select words it.
    code: 'analysis.function.unsupported',
    config: config({
      metrics: [{ ...latest, function: 'MIN' }],
    }),
    en: 'Created cannot be summarised as Earliest.',
    zh: '「Created」不能求最早。',
  },
  {
    code: 'analysis.derived.moment-operand',
    config: config({
      metrics: [
        latest,
        {
          type: 'DERIVED',
          alias: 'm_d',
          expression: { type: 'METRIC_REF', metric: 'm_last' },
        },
      ],
    }),
    en: 'Latest of Created is a point in time',
    zh: '「Created的最晚」是时间点',
  },
  {
    code: 'analysis.derived.unknown-metric',
    config: config({
      metrics: [
        {
          type: 'DERIVED',
          alias: 'm_d',
          expression: { type: 'METRIC_REF', metric: 'm_n' },
        },
        { type: 'COUNT', alias: 'm_n' },
      ],
    }),
    en: 'The derived metric refers to Record count',
    zh: '派生指标引用了「记录数」',
  },
];

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

/** What the tray would name the config's parts by. */
function naming(of: AnalysisViewConfig): AnalysisNaming {
  return {
    fields: definition.fields.map(field => ({
      field: field.name,
      label: field.label,
      groups: [],
      functions: [],
      dateUnits: [],
      dateParts: [],
      distinctCount: false,
      percentile: false,
      any: false,
      firstLast: false,
      missingKey: false,
      cell: field.kind,
    })),
    groups: of.groups,
    metrics: of.metrics,
  };
}

function said(found: readonly Issue[], of: AnalysisViewConfig) {
  return [defaultMessages, zhCN].map(catalogue => {
    const messages = formatters(catalogue);
    const name = analysisIssueNamer(naming(of), messages);
    return found.map(each => messages.issue(name(each))).join(' ');
  });
}

describe('an analysis finding says what the screen shows', () => {
  it.each(CASES)('$code', ({ code, config: shape, en, zh }) => {
    const raised = validateAnalysis(definition, shape, builtinFieldKinds);
    expect(raised.map(found => found.code)).toContain(code);
    const [english, chinese] = said(
      raised.filter(found => found.code === code),
      shape,
    );
    expect(english).toContain(en);
    expect(chinese).toContain(zh);
    for (const sentence of [english, chinese])
      for (const key of KEYS) expect(sentence).not.toContain(key);
  });

  it('leaves what the draft does not have as the kernel said it', () => {
    const shape = config({
      sort: [{ alias: 'm_gone', direction: 'DESC' }],
    });
    const raised = validateAnalysis(definition, shape, builtinFieldKinds);
    expect(raised.map(found => found.code)).toContain(
      'analysis.sort.unknown-alias',
    );
    const [, chinese] = said(
      raised.filter(found => found.code === 'analysis.sort.unknown-alias'),
      shape,
    );
    expect(chinese).toBe('排序依据的「m_gone」不在这个结果里。');
  });

  it('keeps the alias where the alias itself is what is wrong', () => {
    const found: Issue = {
      code: 'analysis.alias.duplicate',
      severity: 'error',
      path: ['metrics', 1, 'alias'],
      params: { alias: 'm_n' },
    };
    const shape = config({});
    const namer = analysisIssueNamer(
      naming(shape),
      formatters(defaultMessages),
    );
    expect(namer(found)).toBe(found);
  });

  it('names a condition’s field in the analysis scope', () => {
    const found: Issue = {
      code: 'filter.operator.unsupported',
      severity: 'error',
      path: ['filter', 'children', 0],
      params: { field: 'createdAt', operator: 'contains' },
    };
    const namer = analysisIssueNamer(
      naming(config({})),
      formatters(defaultMessages),
    );
    expect(namer(found).params).toEqual({
      field: 'Created',
      operator: 'contains',
    });
  });
});
