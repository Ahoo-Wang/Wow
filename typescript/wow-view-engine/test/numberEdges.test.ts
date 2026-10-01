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
 * A number at the edges of its format (the second review, R2-18, R2-21): a
 * share that is not zero never reads 「0.0%」 and one that is not the whole
 * never 「100.0%」, a change too small for its decimals never 「+0%」 under
 * an arrow, nothing reads 「-0」, a tick is written to the digits it takes,
 * and a whole number past 2^53 keeps every digit.
 */

import { describe, expect, it } from 'vitest';
import type { MessageFormatters } from '../src/ui/index.js';
import {
  formatChange,
  formatShare,
  formatTick,
  specTick,
} from '../src/ui/charts/axis.js';
import { valueText } from '../src/ui/kit/display.js';
import {
  compactFormat,
  formatNumber,
  formatPoints,
} from '../src/ui/kit/numbers.js';

const ONE_DECIMAL = {
  style: 'percent',
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
} as const;

describe('a percentage at its edges', () => {
  it.each(['en', 'zh-CN'])(
    'is never 0.0%% unless nothing, nor 100.0%% unless everything (%s)',
    locale => {
      const percent = (value: number) =>
        formatNumber(value, ONE_DECIMAL, locale);
      // The dev console's 重试成功率: 14 retries out of 180,970.
      expect(percent(14 / 180_970)).toBe('<0.1%');
      expect(percent(0.0004)).toBe('<0.1%');
      expect(percent(1e-9)).toBe('<0.1%');
      expect(percent(0.9996)).toBe('>99.9%');
      expect(percent(179_999 / 180_000)).toBe('>99.9%');
      expect(percent(0)).toBe('0.0%');
      expect(percent(1)).toBe('100.0%');
      expect(percent(0.0005)).toBe('0.1%');
      expect(percent(0.9994)).toBe('99.9%');
    },
  );

  it('scales the floor to the decimals the format writes', () => {
    expect(
      formatNumber(
        0.00004,
        { style: 'percent', maximumFractionDigits: 2 },
        'en',
      ),
    ).toBe('<0.01%');
    expect(formatNumber(0.004, { style: 'percent' }, 'en')).toBe('<1%');
    expect(formatNumber(0.996, { style: 'percent' }, 'en')).toBe('>99%');
  });

  it('says a small fall as above its negative step', () => {
    expect(formatNumber(-0.0001, ONE_DECIMAL, 'en')).toBe('>-0.1%');
  });

  it('writes a share and a change the same way', () => {
    expect(formatShare(14 / 180_970, 'en')).toBe('<0.1%');
    expect(formatShare(0.9996, 'en')).toBe('>99.9%');
    expect(formatChange(0.0001, 'en')).toBe('<+0.1%');
    expect(formatChange(-0.0001, 'en')).toBe('>-0.1%');
    expect(formatChange(0, 'en')).toBe('0.0%');
    expect(formatChange(-0.15, 'en')).toBe('-15.0%');
  });

  it('leaves a short or significant-digit format as it writes', () => {
    // An axis's compact figure keeps its three digits: it never rounds a
    // small share away.
    expect(
      formatNumber(14 / 180_970, compactFormat({ style: 'percent' }), 'en'),
    ).toBe('0.00774%');
  });
});

describe('a measure in a unit at its edges', () => {
  it('reads a recovery of 0.04 minutes as under a tenth', () => {
    const minutes = {
      style: 'unit',
      unit: 'minute',
      maximumFractionDigits: 1,
    } as const;
    expect(formatNumber(0.04, minutes, 'en')).toBe(
      `<${formatNumber(0.1, minutes, 'en')}`,
    );
    expect(formatNumber(0.04, minutes, 'zh-CN')).toBe(
      `<${formatNumber(0.1, minutes, 'zh-CN')}`,
    );
    expect(formatNumber(0, minutes, 'en')).toBe(
      new Intl.NumberFormat('en', minutes).format(0),
    );
  });
});

describe('a number with no sign to show', () => {
  it('is never -0', () => {
    expect(formatNumber(-0, undefined, 'en')).toBe('0');
    expect(formatNumber(-0, ONE_DECIMAL, 'en')).toBe('0.0%');
    expect(
      formatNumber(-0.001, { style: 'currency', currency: 'CNY' }, 'zh-CN'),
    ).toBe('¥0.00');
    expect(formatNumber(-1, undefined, 'en')).toBe('-1');
  });

  it('keeps a sign the format asks for', () => {
    expect(formatNumber(2, { signDisplay: 'always' }, 'en')).toBe('+2');
  });

  it('writes no amount for NaN or an infinity', () => {
    expect(formatNumber(Number.NaN, ONE_DECIMAL, 'en')).toBe('—');
    expect(formatNumber(Number.POSITIVE_INFINITY, undefined, 'en')).toBe('—');
    expect(formatPoints(Number.NaN, ONE_DECIMAL, 'en')).toBe('—');
  });
});

describe('a change of a percentage, in points', () => {
  it('is the points between them, to the percentage’s decimals', () => {
    expect(formatPoints(-0.144, ONE_DECIMAL, 'en')).toBe('-14.4');
    expect(formatPoints(0.061, ONE_DECIMAL, 'zh-CN')).toBe('+6.1');
    expect(
      formatPoints(
        0.0614,
        { style: 'percent', maximumFractionDigits: 2 },
        undefined,
      ),
    ).toBe('+6.14');
    // A percentage written whole still moves by tenths of a point.
    expect(formatPoints(0.061, { style: 'percent' }, 'en')).toBe('+6.1');
    expect(formatPoints(0.000_01, ONE_DECIMAL, 'en')).toBe('<+0.1');
    expect(formatPoints(0, ONE_DECIMAL, 'en')).toBe('0.0');
  });
});

describe('a tick', () => {
  it('is written to the digits it takes to be itself', () => {
    // A trend between 99.95% and 100%: every tick read 「100%」.
    expect(specTick(0.9995, 'percent', 'en')).toBe('99.95%');
    expect(specTick(0.999, 'percent', 'en')).toBe('99.9%');
    expect(specTick(1, 'percent', 'en')).toBe('100%');
    expect(formatTick(0.9995, compactFormat({ style: 'percent' }), 'en')).toBe(
      '99.95%',
    );
    expect(formatTick(12_550_000, compactFormat(undefined), 'zh-CN')).toBe(
      '1,255万',
    );
    expect(formatTick(1_200_000, compactFormat(undefined), 'en')).toBe('1.2M');
  });
});

describe('a whole number past 2^53', () => {
  const words = {} as MessageFormatters;

  it('keeps every digit of a bigint, grouped as a number is', () => {
    expect(valueText(9_007_199_254_740_993n, words, undefined, 'en')).toBe(
      '9,007,199,254,740,993',
    );
  });

  it('keeps every digit a source sent as text, in a column of numbers', () => {
    expect(valueText('9007199254740993', words, {}, 'en')).toBe(
      '9,007,199,254,740,993',
    );
    // Without a number format it is somebody's text: an order number.
    expect(valueText('9007199254740993', words, undefined, 'en')).toBe(
      '9007199254740993',
    );
    expect(valueText('007', words, {}, 'en')).toBe('007');
  });
});
