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
import type { FilterSummaryItem } from '../src/index.js';
import {
  badgeEntries,
  cellText,
  displayValue,
  formatNumber,
  isoDay,
  summaryText,
  valueText,
  type DisplayField,
} from '../src/ui/display.js';
import { en } from '../src/ui/messages/en.js';
import { formatMessage } from '../src/ui/messages.js';

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
    // A currency style with no currency cannot be built at all.
    expect(formatNumber(1000, { style: 'currency' })).toBe('1000');
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
    expect(text(1234.5, { kind: 'number' })).toBe('1234.5');
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

  it('gives a link and a paragraph whole, and anything else as JSON', () => {
    expect(text('https://example.com/a', { cell: 'link' })).toBe(
      'https://example.com/a',
    );
    expect(text('two\nlines', { cell: 'text' })).toBe('two\nlines');
    expect(text({ a: 1 })).toBe('{"a":1}');
    expect(text(9007199254740993n)).toBe('9007199254740993');
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
    // "All of X" and "Any of X" say no more than "X"; "None of X" says the
    // opposite of it, so that one is said whatever it holds.
    expect(say(predicate('and', [sku('A', 0)]))).toBe(
      'Items has an entry where SKU is A',
    );
    expect(say(predicate('or', [sku('A', 0)]))).toBe(
      'Items has an entry where SKU is A',
    );
    expect(say(predicate('nor', [sku('A', 0)]))).toBe(
      'Items has an entry where None of SKU is A',
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
