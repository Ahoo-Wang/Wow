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
 * The reading line says each dimension as its column is headed: a time
 * dimension with its granularity, since the axis and the header under the
 * line say 「创建时间（按日）」 (the 2026-09-23 audit, P2-1).
 */

import { describe, expect, it } from 'vitest';
import type { AnalysisColumnView } from '../src/index.js';
import { zhCN } from '../src/ui/index.js';
import { analysisReading } from '../src/ui/analysis/AnalysisToolbar.js';
import {
  defaultMessages,
  formatIssue,
  formatMessage,
  type ViewMessages,
} from '../src/ui/messages.js';
import type { MessageFormatters } from '../src/ui/MessagesProvider.js';

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

const byDay: AnalysisColumnView = {
  alias: 'day',
  label: '创建时间',
  role: 'group',
  dateUnit: 'DAY',
};
const byWarehouse: AnalysisColumnView = {
  alias: 'wh',
  label: '仓库',
  role: 'group',
};
const count: AnalysisColumnView = {
  alias: 'n',
  label: '',
  role: 'metric',
  fn: 'COUNT',
};

describe('the reading line', () => {
  it('says a time dimension with its granularity, as its header does', () => {
    expect(analysisReading([byDay, count], formatters(zhCN))).toBe(
      '按创建时间（按日） · 记录数',
    );
    expect(
      analysisReading([byDay, byWarehouse, count], formatters(defaultMessages)),
    ).toContain('创建时间 (by day)');
  });

  it('says a dimension the analyst named by that name alone', () => {
    expect(
      analysisReading(
        [{ ...byDay, label: '下单日', named: true }, count],
        formatters(zhCN),
      ),
    ).toBe('按下单日 · 记录数');
  });
});
