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
import { metricReferenceText, wordReferences } from '../src/analysis/index.js';

describe('wordReferences', () => {
  const word = (fn: string, label: string) => `[${fn}:${label}]`;

  it('words every marked reference, wherever it stands in the text', () => {
    const text = `(${metricReferenceText('SUM', 'Amount')} − ${metricReferenceText(
      'SUM',
      'Cost',
    )}) ÷ ${metricReferenceText('COUNT', 'orders')}`;
    expect(wordReferences(text, word)).toBe(
      '([SUM:Amount] − [SUM:Cost]) ÷ [COUNT:orders]',
    );
  });

  it('leaves text with nothing marked as it is', () => {
    expect(wordReferences('Margin', word)).toBe('Margin');
    expect(wordReferences('', word)).toBe('');
  });

  it('keeps a label with spaces and signs whole', () => {
    expect(
      wordReferences(
        `${metricReferenceText('AVG', 'Amount − Cost (net)')} × 2`,
        word,
      ),
    ).toBe('[AVG:Amount − Cost (net)] × 2');
  });
});
