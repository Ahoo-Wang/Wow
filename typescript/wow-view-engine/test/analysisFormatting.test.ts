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

import { expect, it, vi } from 'vitest';
import { formatAnalysisValue } from '../src/analysis/analysisFormatting.js';
import type { AnalysisResultColumn } from '../src/analysis/analysisModel.js';
const column: AnalysisResultColumn = {
  id: 'n',
  alias: 'n',
  title: 'Number',
  role: 'metric',
  valueType: 'number',
  nullable: true,
};
it('formats exact grouped counts, readable fractions and explicit Intl options without changing null or zero', () => {
  expect(formatAnalysisValue(581234, { ...column, aggregation: 'COUNT' })).toBe(
    '581,234',
  );
  expect(
    formatAnalysisValue(9007199254740991, { ...column, aggregation: 'COUNT' }),
  ).toBe('9,007,199,254,740,991');
  expect(formatAnalysisValue(1.234567890123456, column)).toBe('1.235');
  expect(
    formatAnalysisValue(1.234567, {
      ...column,
      numberFormat: { maximumFractionDigits: 2 },
    }),
  ).toBe('1.23');
  expect(formatAnalysisValue(null, column)).toBe('无值');
  expect(formatAnalysisValue(0, column)).toBe('0');
  expect(formatAnalysisValue(0.00000012345, column)).not.toBe('0');
});
it('applies typed labels and contains malformed Intl configuration errors', () => {
  const label = { ...column, options: [{ value: 1, label: '一' }] };
  expect(formatAnalysisValue(1, label)).toBe('一');
  expect(formatAnalysisValue('1', label)).toBe('1');
  expect(
    formatAnalysisValue(1.23, {
      ...column,
      numberFormat: { locale: 'invalid_locale', maximumFractionDigits: -1 },
    }),
  ).toBe('1.23');
  expect(
    formatAnalysisValue(0, { ...column, valueType: 'datetime' }, '+08:00'),
  ).toContain('08:00:00');
});

it('reuses identical numeric formats and respects changed host options', () => {
  const create = vi.spyOn(Intl, 'NumberFormat');
  try {
    const options = {
      locale: 'de-DE',
      minimumFractionDigits: 17,
      maximumFractionDigits: 17,
    };
    const configured = { ...column, numberFormat: options };
    formatAnalysisValue(1, configured);
    formatAnalysisValue(2, configured);
    expect(create).toHaveBeenCalledTimes(1);
    options.maximumFractionDigits = 18;
    formatAnalysisValue(3, configured);
    expect(create).toHaveBeenCalledTimes(2);
  } finally {
    create.mockRestore();
  }
});

it('uses UTC for datetime values when the definition omits its timezone', () => {
  const formatter = vi.spyOn(Intl, 'DateTimeFormat');
  try {
    const dateColumn = { ...column, valueType: 'datetime' as const };
    const value = Date.UTC(2026, 8, 11, 12);
    const result = formatAnalysisValue(value, dateColumn);
    expect(formatter).toHaveBeenLastCalledWith(
      'zh-CN',
      expect.objectContaining({ timeZone: 'UTC' }),
    );
    expect(result).toBe(formatAnalysisValue(value, dateColumn, 'UTC'));
  } finally {
    formatter.mockRestore();
  }
});
