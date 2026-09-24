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
import type { MessageFormatters } from '../src/ui/index.js';
import {
  metricReferenceText,
  type FilterSummaryItem,
  type NumberFormat,
} from '../src/index.js';
import { bandText } from '../src/ui/band.js';
import {
  badgeEntries,
  cellText,
  csvCellText,
  displayValue,
  formatNumber,
  heldReading,
  isoDay,
  valueText,
  type DisplayField,
  columnTitle,
} from '../src/ui/display.js';
import { summaryText } from '../src/ui/summary.js';
import { en } from '../src/ui/messages/en.js';
import { zhCN } from '../src/ui/messages/zh-CN.js';
import { formatMessage, type ViewMessages } from '../src/ui/messages.js';

/**
 * Expected text comes from the same Intl call, not a literal: the ICU data a
 * Node release ships changes spacing and punctuation, and the test is about
 * which zone, language and fields are asked for.
 */
function formatted(
  value: number,
  locale: string | undefined,
  options: Intl.DateTimeFormatOptions,
): string {
  return new Intl.DateTimeFormat(locale, options).format(value);
}

/** 17:21:55 on 18 Sep 2026 in Shanghai; 02:21:55 the same day in Los Angeles. */
const INSTANT = 1789723315014;
const DATE_TIME = { dateStyle: 'medium', timeStyle: 'medium' } as const;

describe('displayValue', () => {
  it('shows an epoch-millisecond datetime in the zone and language asked', () => {
    const context = { locale: 'zh-CN', timeZone: 'Asia/Shanghai' };
    const shown = displayValue(INSTANT, { kind: 'datetime' }, context);

    expect(shown).toBe(
      formatted(INSTANT, 'zh-CN', { ...DATE_TIME, ...context }),
    );
    expect(shown).toContain('17:21:55');
  });

  it('reads the same instant by the clock of the zone it is shown in', () => {
    const shanghai = displayValue(
      INSTANT,
      { kind: 'datetime' },
      { locale: 'en-GB', timeZone: 'Asia/Shanghai' },
    );
    const angeles = displayValue(
      INSTANT,
      { kind: 'datetime' },
      { locale: 'en-GB', timeZone: 'America/Los_Angeles' },
    );

    expect(shanghai).toContain('17:21:55');
    expect(angeles).toContain('02:21:55');
  });

  // Java writes a LocalDateTime with no offset. It names 02:10 on some clock,
  // and the filter kernel reads it on the engine's; read on the browser's
  // and shown on the engine's, it moved by the hours between the two.
  it('shows a wall-clock time as written, whatever clock is in force', () => {
    const context = { locale: 'en-GB', timeZone: 'Asia/Shanghai' };
    const expected = formatted(Date.UTC(2026, 8, 15, 2, 10), 'en-GB', {
      ...DATE_TIME,
      timeZone: 'UTC',
    });

    expect(
      displayValue('2026-09-15T02:10:00', { kind: 'datetime' }, context),
    ).toBe(expected);
    expect(
      displayValue(
        '2026-09-15 02:10:00.123456789',
        { kind: 'datetime' },
        context,
      ),
    ).toBe(expected);
    expect(expected).toContain('02:10:00');
  });

  // `Date` rolls a day that does not exist into the next month, and 24:00
  // into the next day, so a cell would have shown a date the source never held.
  it('leaves a wall-clock time the calendar does not have to the caller', () => {
    const context = { locale: 'en-GB', timeZone: 'UTC' };

    expect(
      displayValue('2025-02-29', { kind: 'date' }, context),
    ).toBeUndefined();
    expect(
      displayValue('2026-04-31T10:00:00', { kind: 'datetime' }, context),
    ).toBeUndefined();
    expect(
      displayValue('2026-01-01T24:00:00', { kind: 'datetime' }, context),
    ).toBeUndefined();
  });

  /**
   * A wall-clock string with no time of day names the whole day — it is how
   * a `withTime` date condition says "the whole of the 31st" (D17-1), and it
   * is what a `LocalDate` column holds. Showing 00:00:00 beside it states a
   * moment nobody wrote, and on a filter badge it said the opposite of the
   * query that ran: the day's end, not its start.
   */
  it('shows a day with no time of day as a day, on a datetime field', () => {
    const context = { locale: 'en-GB', timeZone: 'Asia/Shanghai' };

    expect(displayValue('2026-01-31', { kind: 'datetime' }, context)).toBe(
      formatted(Date.UTC(2026, 0, 31), 'en-GB', {
        dateStyle: 'medium',
        timeZone: 'UTC',
      }),
    );
    // A time of day is still shown where the value carries one.
    expect(
      displayValue('2026-01-31T15:30:00', { kind: 'datetime' }, context),
    ).toContain('15:30:00');
  });

  // A time on the wrong clock is wrong; one in the runtime's language is
  // only foreign. `zh_CN` is not BCP 47, so Intl refuses it.
  it('keeps the zone when the language is one Intl cannot read', () => {
    const shown = displayValue(
      INSTANT,
      { kind: 'datetime' },
      { locale: 'zh_CN', timeZone: 'Asia/Shanghai' },
    );

    expect(shown).toBe(
      formatted(INSTANT, undefined, {
        ...DATE_TIME,
        timeZone: 'Asia/Shanghai',
      }),
    );
    expect(shown).not.toBe(
      formatted(INSTANT, undefined, {
        ...DATE_TIME,
        timeZone: 'America/Los_Angeles',
      }),
    );
  });

  // The type admits what Intl refuses, and the throw used to take the whole
  // table down mid-render.
  it('gives way on a number format Intl will not build instead of throwing', () => {
    const words = {} as MessageFormatters;
    const currency = { style: 'currency', currency: 'CNY' } as const;

    // The language goes first and the currency stays.
    expect(formatNumber(1000, { ...currency, locale: 'zh_CN' })).toBe(
      new Intl.NumberFormat(undefined, currency).format(1000),
    );
    // A currency style with no currency cannot be built at all, and gives
    // way to the plain grouping every other number gets.
    expect(formatNumber(1000, { style: 'currency' })).toBe(
      (1000).toLocaleString(),
    );
    expect(valueText(1000, words, { style: 'currency' })).toBe(
      (1000).toLocaleString(),
    );
    expect(valueText(1000, words, currency)).toBe(
      new Intl.NumberFormat(undefined, currency).format(1000),
    );
  });

  it('reads a Date as the instant it holds', () => {
    const context = { locale: 'en-GB', timeZone: 'UTC' };

    expect(displayValue(new Date(INSTANT), { kind: 'datetime' }, context)).toBe(
      formatted(INSTANT, 'en-GB', { ...DATE_TIME, timeZone: 'UTC' }),
    );
    expect(displayValue('', { kind: 'date' }, context)).toBeUndefined();
  });

  it('reads an ISO string and an epoch written as a string alike', () => {
    const context = { locale: 'en-GB', timeZone: 'UTC' };
    const expected = formatted(INSTANT, 'en-GB', {
      ...DATE_TIME,
      timeZone: 'UTC',
    });

    expect(
      displayValue(
        new Date(INSTANT).toISOString(),
        { kind: 'datetime' },
        context,
      ),
    ).toBe(expected);
    expect(displayValue(String(INSTANT), { kind: 'datetime' }, context)).toBe(
      expected,
    );
  });

  // Read as an instant at UTC midnight and shown in Los Angeles, the 18th
  // would come out as the 17th.
  it('keeps a calendar date on its own day, whatever the zone', () => {
    const shown = displayValue(
      '2026-09-18',
      { kind: 'date' },
      { locale: 'en-GB', timeZone: 'America/Los_Angeles' },
    );

    expect(shown).toBe(
      formatted(Date.UTC(2026, 8, 18), 'en-GB', {
        dateStyle: 'medium',
        timeZone: 'UTC',
      }),
    );
    expect(shown).toContain('18');
  });

  it('follows the renderer key before the kind', () => {
    const context = { locale: 'en-GB', timeZone: 'UTC' };

    expect(
      displayValue(INSTANT, { kind: 'number', cell: 'datetime' }, context),
    ).toBe(formatted(INSTANT, 'en-GB', { ...DATE_TIME, timeZone: 'UTC' }));
    expect(
      displayValue(INSTANT, { kind: 'datetime', cell: 'custom' }, context),
    ).toBeUndefined();
  });

  it('names an enum value, and each value of an array, by its option', () => {
    const options = [
      { value: 'FAILED', label: '失败' },
      { value: 'SUCCEEDED', label: '已成功' },
    ];
    const field = { kind: 'enum', options };

    expect(displayValue('FAILED', field, {})).toBe('失败');
    expect(displayValue(['FAILED', 'SUCCEEDED'], field, {})).toBe(
      '失败, 已成功',
    );
    // A code the definition does not name is left to the caller, as is.
    expect(displayValue('UNKNOWN', field, {})).toBeUndefined();
    expect(displayValue(['UNKNOWN'], field, {})).toBeUndefined();
    expect(displayValue(['FAILED', 'UNKNOWN'], field, {})).toBe(
      '失败, UNKNOWN',
    );
  });

  it('shows a date histogram key as the bucket it starts', () => {
    const day = Date.UTC(2026, 8, 18);
    const utc = { locale: 'en-GB', timeZone: 'UTC' };

    expect(displayValue(day, { dateUnit: 'DAY' }, utc)).toBe(
      formatted(day, 'en-GB', { dateStyle: 'medium', timeZone: 'UTC' }),
    );
    expect(displayValue(day, { dateUnit: 'MONTH' }, utc)).toBe(
      formatted(day, 'en-GB', {
        year: 'numeric',
        month: 'long',
        timeZone: 'UTC',
      }),
    );
    expect(displayValue(day, { dateUnit: 'QUARTER' }, utc)).toBe('2026 Q3');
    expect(displayValue(day, { dateUnit: 'YEAR' }, utc)).toBe('2026');
    expect(displayValue(day, { dateUnit: 'HOUR' }, utc)).toBe(
      formatted(day, 'en-GB', {
        dateStyle: 'medium',
        timeStyle: 'short',
        timeZone: 'UTC',
      }),
    );
    expect(displayValue(day, { dateUnit: 'SECOND' }, utc)).toBe(
      formatted(day, 'en-GB', { ...DATE_TIME, timeZone: 'UTC' }),
    );
    expect(displayValue('soon', { dateUnit: 'DAY' }, utc)).toBeUndefined();
  });

  // Persian writes its own numerals, which `Number` reads as NaN, and dates by
  // the Solar Hijri calendar, whose months are not the ones Wow cut by:
  // September's bucket would have been named Shahrivar, which ends on the 22nd.
  it('counts quarters in digits and names periods on the Gregorian calendar', () => {
    const day = Date.UTC(2026, 8, 18);
    const month = { year: 'numeric', month: 'long', timeZone: 'UTC' } as const;
    const persian = { locale: 'fa-IR', timeZone: 'UTC' };

    expect(displayValue(day, { dateUnit: 'QUARTER' }, persian)).toBe(
      `${formatted(day, 'fa-IR', {
        year: 'numeric',
        calendar: 'gregory',
        timeZone: 'UTC',
      })} Q3`,
    );
    expect(displayValue(day, { dateUnit: 'MONTH' }, persian)).toBe(
      formatted(day, 'fa-IR', { ...month, calendar: 'gregory' }),
    );
    expect(displayValue(day, { dateUnit: 'MONTH' }, persian)).not.toBe(
      formatted(day, 'fa-IR', month),
    );
  });

  // Buckets cut at UTC midnight and read in Los Angeles would each carry the
  // date before; the group's own zone is the one its keys are in.
  it('reads bucket keys in the zone the group cut them in', () => {
    const day = Date.UTC(2026, 8, 18);
    const shown = displayValue(
      day,
      { dateUnit: 'DAY', timeZone: 'UTC' },
      { locale: 'en-GB', timeZone: 'America/Los_Angeles' },
    );

    expect(shown).toBe(
      formatted(day, 'en-GB', { dateStyle: 'medium', timeZone: 'UTC' }),
    );
  });

  it('leaves to the caller what it has nothing to add to', () => {
    expect(displayValue(null, { kind: 'datetime' }, {})).toBeUndefined();
    expect(displayValue('soon', { kind: 'datetime' }, {})).toBeUndefined();
    expect(displayValue(42, { kind: 'number' }, {})).toBeUndefined();
    expect(displayValue(true, { kind: 'boolean' }, {})).toBeUndefined();
  });

  it('shows a time in the runtime zone rather than not at all when the zone is unknown', () => {
    expect(
      displayValue(
        INSTANT,
        { kind: 'datetime' },
        { locale: 'en-GB', timeZone: 'Mars/Olympus_Mons' },
      ),
    ).toBe(formatted(INSTANT, 'en-GB', DATE_TIME));
  });
});

/**
 * The same reading a cell has, as a line of text: what a CSV, a title or a
 * copied selection gets. What a node carries and a line cannot — the pill
 * around a status, the anchor around a URL — is all that is dropped.
 */
/**
 * One number, one reading (review B2). A record cell went through
 * `formatNumber`, which printed `String(value)` without a format — the record
 * summary said 「534897」 — while the analysis view grouped the same number
 * into 「534,897」. Both now come from the one formatter.
 */
describe('formatNumber', () => {
  const words = {} as MessageFormatters;

  it('groups a number with no format, in the language it is handed', () => {
    expect(formatNumber(534897, undefined, 'en')).toBe('534,897');
    expect(formatNumber(534897, undefined, 'zh-CN')).toBe('534,897');
    expect(formatNumber(534897, undefined, 'de-DE')).toBe('534.897');
    expect(formatNumber(1234.5, undefined, 'en')).toBe('1,234.5');
  });

  it('reads the same in the record view and the analysis view', () => {
    const field = { kind: 'number' };
    for (const locale of ['en', 'zh-CN', 'de-DE']) {
      const context = { locale };
      expect(cellText(534897, field, words, context)).toBe(
        valueText(534897, words, undefined, locale),
      );
    }
  });

  it('leaves a number that names something ungrouped when its field says so', () => {
    // A year or an employee number is a name, not an amount: the definition
    // says so, and the grouping is its to turn off.
    expect(formatNumber(2026, { useGrouping: false }, 'en')).toBe('2026');
  });
});

/**
 * A number histogram's key is the lower bound of its band, and 「¥0.00」
 * 「¥500.00」 does not say which band a row is (2026-09-23, real backend).
 * The band reads from its key to the key plus the interval, short in the
 * surface's language, with the catalogue's dash.
 */
describe('bandText', () => {
  const catalogue = (messages: ViewMessages): MessageFormatters => ({
    label: (key, params) => formatMessage(messages, key, params),
    issue: () => '',
    issues: () => '',
  });
  const zh = catalogue(zhCN);
  const english = catalogue(en);
  const yuan = { style: 'currency', currency: 'CNY' } as const;
  const band = (
    key: unknown,
    interval: number | undefined,
    numberFormat?: NumberFormat,
    words = zh,
    locale = 'zh-CN',
  ) => bandText(key, { interval, numberFormat }, words, { locale });

  it('reads a key as its band, short, in the surface language', () => {
    expect(band(0, 500, yuan)).toBe('¥0～500');
    expect(band(500, 500, yuan)).toBe('¥500～1,000');
    expect(band(5000, 5000, yuan)).toBe('¥5,000～1万');
    expect(band(10000, 10000, yuan)).toBe('¥1～2万');
    expect(band(100000000, 100000000, yuan)).toBe('¥1～2亿');
    // English: an en dash, and K and M.
    expect(band(0, 500, yuan, english, 'en-US')).toBe('CN¥0–500');
    expect(band(1000, 1000, undefined, english, 'en-US')).toBe('1–2K');
    expect(band(0, 500, yuan, english, 'zh-CN')).toBe('¥0–500');
  });

  it('says a unit, a percent or a currency once, on its own side', () => {
    const words = english;
    expect(band(0.1, 0.1, { style: 'percent' }, words, 'en-US')).toBe('10–20%');
    expect(
      band(0, 5, { style: 'unit', unit: 'kilogram' }, words, 'en-US'),
    ).toBe('0–5 kg');
    expect(band(-500, 500, undefined)).toBe('-500～0');
  });

  /**
   * Short is only worth having when it is exact: 1,250 compact is 「1.3K」,
   * a band edge nobody set. Then both bounds are written in full, with the
   * decimals the band needs and no currency zeros it does not.
   */
  it('writes the band in full where short would round it', () => {
    expect(band(1250, 1250, yuan, english, 'en-US')).toBe('CN¥1,250–2,500');
    expect(band(0.25, 0.25, undefined, english, 'en-US')).toBe('0.25–0.5');
    // 0.2 + 0.1 is 0.30000000000000004 in binary; the band ends at 0.3.
    expect(band(0.2, 0.1, undefined, english, 'en-US')).toBe('0.2–0.3');
    // A number that names something is not compacted: 2K is not a year.
    expect(band(2000, 1000, { useGrouping: false }, english, 'en-US')).toBe(
      '2000–3000',
    );
  });

  it('leaves a key it cannot read, and any other column, to the caller', () => {
    // A bucket with no key reads as it always did.
    expect(band(null, 500, yuan)).toBeUndefined();
    expect(band('(none)', 500, yuan)).toBeUndefined();
    expect(band(Number.NaN, 500, yuan)).toBeUndefined();
    // Not a number histogram, or one with no width to add.
    expect(band(0, undefined, yuan)).toBeUndefined();
    expect(band(0, 0, yuan)).toBeUndefined();
    expect(band(0, Number.POSITIVE_INFINITY, yuan)).toBeUndefined();
  });
});

describe('cellText', () => {
  const words: MessageFormatters = {
    label: key => formatMessage(en, key),
    issue: () => '',
    issues: () => '',
  };
  const text = (value: unknown, field: DisplayField = {}) =>
    cellText(value, field, words, { locale: 'en-GB', timeZone: 'UTC' });

  it('says nothing at all about a value the row does not hold', () => {
    expect(text(null)).toBe('');
    expect(text(undefined)).toBe('');
  });

  it('reads a badge as its label, and several as a list', () => {
    const field = {
      kind: 'enum',
      options: [
        { value: 'PENDING', label: 'Pending' },
        { value: 'SHIPPED', label: 'Shipped' },
      ],
    };

    expect(text('PENDING', field)).toBe('Pending');
    expect(text(['PENDING', 'SHIPPED'], field)).toBe('Pending, Shipped');
  });

  it('reads a time in the zone the surface shows it in', () => {
    expect(text(INSTANT, { kind: 'datetime' })).toBe(
      formatted(INSTANT, 'en-GB', { ...DATE_TIME, timeZone: 'UTC' }),
    );
  });

  it('keeps a number in its format and a boolean in the catalogue words', () => {
    // No format is not "as written": it is grouped in the surface's
    // language, the same reading an analysis cell gives the same number.
    expect(text(1234.5, { kind: 'number' })).toBe('1,234.5');
    expect(
      text(1000, {
        kind: 'number',
        numberFormat: { style: 'currency', currency: 'CNY', locale: 'en-GB' },
      }),
    ).toBe(
      new Intl.NumberFormat('en-GB', {
        style: 'currency',
        currency: 'CNY',
      }).format(1000),
    );
    expect(text(true, { kind: 'boolean' })).toBe(en['label.value.yes']);
  });

  it('gives a link and a paragraph whole, and never writes JSON', () => {
    expect(text('https://example.com/a', { cell: 'link' })).toBe(
      'https://example.com/a',
    );
    expect(text('two\nlines', { cell: 'text' })).toBe('two\nlines');
    expect(text(['a', 'b'])).toBe('a, b');
    expect(text(9007199254740993n)).toBe('9007199254740993');
  });
});

/**
 * An array of objects in a line of text — a CSV, a title — reads as the cell
 * does: its elements' titles, or how many it holds. Joined by the
 * catalogue's separator, so a Chinese file lists 「准备重试、重试失败」.
 */
describe('cellText of an array of objects', () => {
  const words = (catalogue: ViewMessages): MessageFormatters => ({
    label: (key, params) => formatMessage(catalogue, key, params),
    issue: () => '',
    issues: () => '',
  });
  const context = { locale: 'zh-CN', timeZone: 'UTC' };
  const body: DisplayField = {
    kind: 'elementMatch',
    elementTitle: {
      name: 'name',
      kind: 'enum',
      options: [
        { value: 'RETRY_PREPARED', label: '准备重试', tone: 'warning' },
        { value: 'RETRY_FAILED', label: '重试失败', tone: 'danger' },
      ],
    },
  };
  const events = [
    { name: 'RETRY_PREPARED', body: { stackTrace: 'at Retry.kt:42' } },
    { name: 'RETRY_FAILED', body: { stackTrace: 'at Retry.kt:42' } },
    { name: 'UNLISTED' },
  ];

  it('lists every title, joined the catalogue way, in the CSV too', () => {
    expect(cellText(events, body, words(zhCN), context)).toBe(
      '准备重试、重试失败、UNLISTED',
    );
    expect(csvCellText(events, body, words(en), context)).toBe(
      '准备重试, 重试失败, UNLISTED',
    );
  });

  it('reads a title by a path within the element, and an object as one element', () => {
    const lines: DisplayField = {
      elementTitle: { name: 'sku.code', kind: 'string' },
    };
    expect(
      cellText([{ sku: { code: 'A-1' } }], lines, words(en), context),
    ).toBe('A-1');
    expect(cellText({ sku: { code: 'B-2' } }, lines, words(en), context)).toBe(
      'B-2',
    );
  });

  it('counts what it holds without a title, never writing its JSON', () => {
    expect(cellText(events, {}, words(zhCN), context)).toBe('3 项');
    expect(cellText([events[0]], {}, words(en), context)).toBe('1 item');
    expect(cellText({ a: 1, b: 2 }, {}, words(zhCN), context)).toBe('2 个字段');
    expect(cellText({ a: 1 }, {}, words(en), context)).toBe('1 field');
    expect(cellText([], body, words(en), context)).toBe('');
    expect(cellText({}, {}, words(en), context)).toBe('');
  });

  it('hands each element over with its title field’s tone', () => {
    expect(heldReading(events, body, words(en), context)).toEqual({
      elements: [
        { value: 'RETRY_PREPARED', label: '准备重试', tone: 'warning' },
        { value: 'RETRY_FAILED', label: '重试失败', tone: 'danger' },
        { value: 'UNLISTED', label: 'UNLISTED' },
      ],
    });
    expect(heldReading(['a'], {}, words(en), context)).toBeUndefined();
    expect(heldReading('a', body, words(en), context)).toBeUndefined();
  });
});

describe('csvCellText', () => {
  const context = { locale: 'en-GB', timeZone: 'UTC' };
  const words: MessageFormatters = {
    label: key => formatMessage(en, key),
    issue: () => '',
    issues: () => '',
  };

  it('writes a plain number as the number it is, for a spreadsheet to sum', () => {
    expect(csvCellText(534897, { kind: 'number' }, words, context)).toBe(
      '534897',
    );
    expect(csvCellText(Number.NaN, { kind: 'number' }, words, context)).toBe(
      '',
    );
  });

  it('keeps a format the field declares, and reads everything else as the screen does', () => {
    expect(
      csvCellText(
        1234.5,
        {
          kind: 'number',
          numberFormat: { style: 'currency', currency: 'CNY' },
        },
        words,
        context,
      ),
    ).toBe(
      cellText(
        1234.5,
        {
          kind: 'number',
          numberFormat: { style: 'currency', currency: 'CNY' },
        },
        words,
        context,
      ),
    );
    expect(csvCellText(true, { kind: 'boolean' }, words, context)).toBe(
      cellText(true, { kind: 'boolean' }, words, context),
    );
  });
});

describe('isoDay', () => {
  it('writes the day in the surface zone, whatever the language', () => {
    // 17:21 in Shanghai on the 18th is still the 18th; in Los Angeles the
    // same instant is 02:21 on the 18th — and a zone a day behind shows it.
    expect(isoDay(new Date(INSTANT), { timeZone: 'Asia/Shanghai' })).toBe(
      '2026-09-18',
    );
    expect(
      isoDay(new Date(INSTANT), {
        locale: 'ar-EG-u-nu-arab',
        timeZone: 'Pacific/Honolulu',
      }),
    ).toBe('2026-09-17');
  });
});

/**
 * Which values wear a badge. A status is one of a set the definition names,
 * and a pill says exactly that; a string that merely happens to be short is
 * not a status, and neither is a code nobody listed.
 */
describe('badgeEntries', () => {
  /** What each badge says, which is all most of these cases are about. */
  const labelsOf = (entries: { label: string }[] | undefined) =>
    entries?.map(entry => entry.label);

  const STATUS = {
    kind: 'enum',
    options: [
      { value: 'PENDING', label: 'Pending' },
      { value: 'SHIPPED', label: 'Shipped' },
    ],
  };

  it('answers with the option label of an enum the definition named', () => {
    // The raw value rides along: labels are free text a definition may
    // repeat, so a caller needs something better to tell two badges apart.
    expect(badgeEntries('PENDING', STATUS)).toEqual([
      { value: 'PENDING', label: 'Pending' },
    ]);
  });

  it('answers with one label per value of an array', () => {
    expect(badgeEntries(['PENDING', 'SHIPPED'], STATUS)).toEqual([
      { value: 'PENDING', label: 'Pending' },
      { value: 'SHIPPED', label: 'Shipped' },
    ]);
    // A code the definition dropped is shown as it came, beside the ones it
    // still names — the row holds it either way.
    expect(badgeEntries(['PENDING', 'LOST'], STATUS)).toEqual([
      { value: 'PENDING', label: 'Pending' },
      { value: 'LOST', label: 'LOST' },
    ]);
  });

  it('follows the renderer key before the kind', () => {
    expect(
      badgeEntries('PENDING', { ...STATUS, cell: 'string' }),
    ).toBeUndefined();
    expect(
      labelsOf(
        badgeEntries('PENDING', {
          kind: 'string',
          cell: 'enum',
          options: STATUS.options,
        }),
      ),
    ).toEqual(['Pending']);
  });

  it('leaves everything else to the caller', () => {
    // No choices declared, so nothing says this string is one of a set.
    expect(badgeEntries('PENDING', { kind: 'enum' })).toBeUndefined();
    expect(
      badgeEntries('PENDING', { kind: 'enum', options: [] }),
    ).toBeUndefined();
    // A value none of the choices name is a code, not a status.
    expect(badgeEntries('LOST', STATUS)).toBeUndefined();
    expect(badgeEntries(null, STATUS)).toBeUndefined();
    expect(badgeEntries(undefined, STATUS)).toBeUndefined();
  });

  /**
   * The tone travels with the entry because the caller no longer has the
   * option it came from: it holds a label, and a label is not a key back.
   */
  it('hands over the tone the matching option declares', () => {
    const toned = {
      kind: 'enum',
      options: [
        { value: 'PENDING', label: 'Pending', tone: 'warning' as const },
        { value: 'SHIPPED', label: 'Shipped' },
      ],
    };

    expect(badgeEntries(['PENDING', 'SHIPPED'], toned)).toEqual([
      { value: 'PENDING', label: 'Pending', tone: 'warning' },
      // No tone is not a tone of "none": the key is absent, and the caller
      // reads that as neutral.
      { value: 'SHIPPED', label: 'Shipped' },
    ]);
  });

  /**
   * `status` and `tags` were asked for by name, so the question the `enum`
   * rule answers — is this really one of a set? — has already been answered
   * by the definition, and a code it stopped naming keeps its pill.
   */
  it('badges a declared status or tag list whatever the options say', () => {
    expect(badgeEntries('LOST', { ...STATUS, cell: 'status' })).toEqual([
      { value: 'LOST', label: 'LOST' },
    ]);
    expect(badgeEntries('LOST', { kind: 'string', cell: 'status' })).toEqual([
      { value: 'LOST', label: 'LOST' },
    ]);
    expect(
      labelsOf(badgeEntries(['PENDING', 'LOST'], { ...STATUS, cell: 'tags' })),
    ).toEqual(['Pending', 'LOST']);
    // An empty list is a list of no badges, not a value to fall back on.
    expect(badgeEntries([], { kind: 'array', cell: 'tags' })).toEqual([]);
    // Nothing held is still nothing to show.
    expect(
      badgeEntries(null, { kind: 'string', cell: 'status' }),
    ).toBeUndefined();
  });
});

/**
 * The applied badge, built from parts rather than from the English line the
 * kind reads them as. The line was where every catalogue stopped: a kind
 * concatenated the operator name and its own hard-coded English, so the most
 * visible text of the result area could not be translated at all.
 *
 * The bar itself is covered in `test/appliedBar.test.tsx`, in Chinese among
 * other things; these are the shapes that reach it from a predicate, a group
 * and a field that declared a format.
 */
describe('summaryText', () => {
  const words: MessageFormatters = {
    label: (key, params, fallback) => {
      const found = formatMessage(en, key, params);
      return found === key && fallback !== undefined ? fallback : found;
    },
    issue: () => '',
    issues: () => '',
  };
  const say = (item: FilterSummaryItem) => summaryText(item, words, {});
  const sku = (value: string, index: number): FilterSummaryItem => ({
    path: ['children', index],
    text: `SKU EQ ${value}`,
    unresolved: false,
    field: 'items.sku',
    label: 'SKU',
    kind: 'string',
    operator: 'EQ',
    value: { kind: 'text', value },
  });
  const predicate = (
    group: FilterSummaryItem['group'],
    items: FilterSummaryItem[],
  ): FilterSummaryItem => ({
    path: ['children', 0],
    text: 'Items has an entry where',
    unresolved: false,
    field: 'items',
    label: 'Items',
    kind: 'elementMatch',
    operator: 'ELEMENT_MATCH',
    value: { kind: 'none' },
    group,
    items,
  });

  it('reads a predicate out after the operator that holds it', () => {
    expect(say(predicate('and', [sku('A', 0), sku('B', 1)]))).toBe(
      'Items has an entry where All of SKU is A, SKU is B',
    );
  });

  it('says nothing more than the operator for an empty predicate', () => {
    // "Has any entry" is a condition of its own: there is an entry, and
    // nothing is asked of it.
    expect(say(predicate('and', []))).toBe('Items has any entry');
  });

  it("drops the group's word for a lone condition, but not under none-of", () => {
    // "All of X" and "Any of X" say no more than "X"; a lone condition
    // under `nor` is that condition negated — the pill's own switch (D18-7)
    // — and is said as such rather than as a group of one.
    expect(say(predicate('and', [sku('A', 0)]))).toBe(
      'Items has an entry where SKU is A',
    );
    expect(say(predicate('or', [sku('A', 0)]))).toBe(
      'Items has an entry where SKU is A',
    );
    expect(say(predicate('nor', [sku('A', 0)]))).toBe(
      'Items has an entry where not SKU is A',
    );
  });

  it('parenthesises a group inside a group, as the English line does', () => {
    const inner: FilterSummaryItem = {
      path: ['children', 1],
      text: 'SKU EQ A nor SKU EQ B',
      unresolved: false,
      group: 'nor',
      items: [sku('A', 0), sku('B', 1)],
    };

    expect(
      say({
        path: [],
        text: '',
        unresolved: false,
        group: 'or',
        items: [sku('C', 0), inner],
      }),
    ).toBe('Any of SKU is C, (None of SKU is A, SKU is B)');
  });

  /**
   * The badge says the time where the condition gave one, and says only the
   * day where it did not: an empty time box is the whole day (D17-1), so a
   * badge reading "through 31 Jan 2026, 00:00:00" would describe an end the
   * query never ran to.
   */
  it('says a date condition with its time of day, and without one', () => {
    const range = (from: string, to: string): FilterSummaryItem => ({
      path: ['children', 0],
      text: `${from} ~ ${to}`,
      unresolved: false,
      field: 'createdAt',
      label: 'Created',
      kind: 'datetime',
      operator: 'BETWEEN',
      value: { kind: 'range', from, to },
    });

    // Written out of Intl rather than typed: the badge reads in the
    // surface's own language, and a typed string would only ever prove
    // which machine the suite ran on.
    const shown = (utc: number, withTime: boolean) =>
      formatted(utc, undefined, {
        dateStyle: 'medium',
        ...(withTime ? { timeStyle: 'medium' as const } : {}),
        timeZone: 'UTC',
      });

    expect(say(range('2026-01-01', '2026-01-31'))).toBe(
      `Created between ${shown(Date.UTC(2026, 0, 1), false)} ~ ` +
        shown(Date.UTC(2026, 0, 31), false),
    );
    expect(say(range('2026-01-01T09:00:00', '2026-01-31T15:30:00'))).toBe(
      `Created between ${shown(Date.UTC(2026, 0, 1, 9), true)} ~ ` +
        shown(Date.UTC(2026, 0, 31, 15, 30), true),
    );
  });

  it('falls back to the derived spelling for an operator nothing names', () => {
    expect(
      say({
        path: ['children', 0],
        text: 'Qty CUSTOM_OP 1',
        unresolved: false,
        field: 'qty',
        label: 'Qty',
        kind: 'number',
        operator: 'CUSTOM_OP' as never,
        value: { kind: 'text', value: 1 },
      }),
    ).toBe('Qty custom op 1');
  });

  it('shows a number in the format its field declared', () => {
    const yuan = {
      style: 'currency',
      currency: 'CNY',
      locale: 'zh-CN',
    } as const;

    expect(
      say({
        path: ['children', 0],
        text: 'Amount 1000 ~ 2000',
        unresolved: false,
        field: 'amount',
        label: 'Amount',
        kind: 'number',
        numberFormat: yuan,
        operator: 'BETWEEN',
        value: { kind: 'range', from: 1000, to: 2000 },
      }),
    ).toBe(
      `Amount between ${formatNumber(1000, yuan)} ~ ${formatNumber(2000, yuan)}`,
    );
  });

  /**
   * A window with no upper edge compiles to `GTE`, and the kind says so:
   * the badge used to read "between <date>", which is neither the range it
   * claimed nor the condition that ran.
   */
  it('words an open-ended window as the bound it compiles to', () => {
    expect(
      say({
        path: ['children', 0],
        text: 'Created from 2026-01-01',
        unresolved: false,
        field: 'createdAt',
        label: 'Created',
        kind: 'date',
        operator: 'GTE',
        value: { kind: 'text', value: '2026-01-01' },
      }),
    ).toBe(
      `Created at least ${formatted(Date.parse('2026-01-01T00:00:00.000Z'), undefined, { dateStyle: 'medium', timeZone: 'UTC' })}`,
    );
  });

  /**
   * The same stored distance, two conditions. `text` has always got this
   * right — "last 7 day" for the span, "7 day ago" for the moment at the end
   * of it — so it is the oracle for what the badge must say.
   */
  it('tells a relative window from the moment at the end of it', () => {
    const relative = (
      bound: 'window' | 'instant',
      direction: 'past' | 'future',
    ) =>
      say({
        path: ['children', 0],
        text: '',
        unresolved: false,
        field: 'createdAt',
        label: 'Created',
        kind: 'datetime',
        operator: bound === 'window' ? 'BETWEEN' : 'LTE',
        value: { kind: 'relative', amount: 7, unit: 'day', direction, bound },
      });

    expect(relative('window', 'past')).toBe('Created between last 7 day');
    expect(relative('window', 'future')).toBe('Created between next 7 day');
    expect(relative('instant', 'past')).toBe('Created at most 7 day ago');
    expect(relative('instant', 'future')).toBe('Created at most 7 day ahead');
  });

  it('uses the label a kind resolved rather than resolving it again', () => {
    expect(
      say({
        path: ['children', 0],
        text: 'Owner ACME',
        unresolved: false,
        field: '@ownerId',
        label: 'Owner',
        kind: 'ownerId',
        operator: 'OWNER_ID',
        value: { kind: 'text', value: 'u-7', label: 'ACME' },
      }),
    ).toBe('Owner is ACME');
  });

  it('says only the field for a value its kind cannot read', () => {
    expect(
      say({
        path: ['children', 0],
        text: 'Status',
        unresolved: false,
        field: 'status',
        label: 'Status',
        kind: 'enum',
        operator: 'EQ',
        value: { kind: 'blank' },
      }),
    ).toBe('Status');
  });

  it('says nothing at all for an item carrying no parts', () => {
    // A host may hand the bar a summary of its own; a value it left out is
    // not a reason to print `undefined` next to the field name.
    expect(
      say({ path: [], text: 'legacy', unresolved: false, label: 'legacy' }),
    ).toBe('legacy');
  });
});

describe('columnTitle', () => {
  const words: MessageFormatters = {
    label: (key, params, fallback) => {
      const found = formatMessage(en, key, params);
      return found === key && fallback !== undefined ? fallback : found;
    },
    issue: () => '',
    issues: () => '',
  };

  // A derived metric's text marks each metric it refers to, since the
  // kernel holds no catalogue; the title words each as that metric's own
  // column is worded, wherever it stands. A formula keeps its summary
  // appended.
  it('words a derived metric’s references as their own columns are worded', () => {
    expect(
      columnTitle(
        {
          label: `${metricReferenceText('SUM', 'Amount')} ÷ ${metricReferenceText('COUNT', 'orders')}`,
          fn: 'DERIVED',
        },
        words,
      ),
    ).toBe('Sum of Amount ÷ Record count');
    expect(
      columnTitle(
        {
          label: `${metricReferenceText('PERCENTILE', 'Amount')} − 1`,
          fn: 'DERIVED',
        },
        words,
      ),
    ).toBe(
      `≈ ${words.label('label.summary.of', { field: 'Amount', fn: words.label('label.summary.fn.PERCENTILE') })} − 1`,
    );
    expect(columnTitle({ label: 'Amount − Cost', fn: 'SUM' }, words)).toBe(
      'Sum of Amount − Cost',
    );
  });

  const zh: MessageFormatters = {
    label: (key, params) => formatMessage(zhCN, key, params),
    issue: () => '',
    issues: () => '',
  };

  /**
   * A header is one phrase, so the Chinese composes without the spaces a
   * sentence puts around a name (2026-09-23 audit: 「金额 的 总和」 read as
   * three words). The English keeps its own order.
   */
  /**
   * 「合计」 is the totals row — every record in the range — and only that;
   * the SUM function is 「总和」 wherever it is named (2026-09-23 audit: the
   * same word stood for the row and for a column's summary, so 「金额的合计」
   * over a totals row read as the one thing twice). One word per concept.
   */
  it('keeps 「合计」 for the totals row and names SUM 「总和」', () => {
    expect(zhCN['label.summary.fn.SUM']).toBe('总和');
    expect(zhCN['label.summary.total']).toBe('合计');
    const totalsKeys = new Set([
      'label.summary.total',
      'label.analysis.totals',
      'label.analysis.totals-whole',
      'label.chart.total',
      'runtime.summary.page-only',
      // The exported file's last row is that totals row (D25 Q28).
      'label.export.and-totals',
    ]);
    const others = Object.entries(zhCN)
      .filter(([key, text]) => text.includes('合计') && !totalsKeys.has(key))
      .map(([key]) => key);
    expect(others).toEqual([]);
  });

  it('composes a metric’s header as one phrase, in either language', () => {
    expect(columnTitle({ label: '金额', fn: 'SUM' }, zh)).toBe('金额的总和');
    expect(
      columnTitle({ label: '创建时间', fn: 'MAX', cell: 'datetime' }, zh),
    ).toBe('创建时间的最晚');
    expect(columnTitle({ label: '金额', fn: 'PERCENTILE' }, zh)).toBe(
      '≈ 金额的百分位',
    );
    expect(columnTitle({ label: 'Amount', fn: 'SUM' }, words)).toBe(
      'Sum of Amount',
    );
  });

  /**
   * A time dimension's rows are buckets, and a bucket key reads as one
   * moment: 「9月1日」 does not say whether the row is that day or that
   * month. So its header says the granularity; a dimension the analyst
   * named is titled by the name alone, and any other dimension by its field.
   */
  it('says what one row of a time dimension spans', () => {
    expect(columnTitle({ label: '创建时间', dateUnit: 'DAY' }, zh)).toBe(
      '创建时间（按日）',
    );
    expect(columnTitle({ label: 'Created', dateUnit: 'MONTH' }, words)).toBe(
      'Created (by month)',
    );
    expect(
      columnTitle({ label: '下单日', dateUnit: 'DAY', named: true }, zh),
    ).toBe('下单日');
    expect(columnTitle({ label: 'Warehouse' }, words)).toBe('Warehouse');
  });
});
