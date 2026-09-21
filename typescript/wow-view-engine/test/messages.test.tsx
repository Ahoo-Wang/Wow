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

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { cleanup, render, renderHook, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  AggregationFunction,
  AggregationGroupType,
  FilterOperator,
} from '@ahoo-wang/fetcher-wow';
import {
  CHART_TYPES,
  DATE_TIME_PRESETS,
  RELATIVE_DATE_UNITS,
} from '../src/index.js';
import type { FilterValue, ViewInstanceSummary } from '../src/index.js';
import type { RecordViewRuntime } from '../src/runtime/index.js';
import type {
  RecordTableController,
  ViewListState,
} from '../src/react/index.js';
import {
  defaultMessages,
  en,
  FilterValueEditor,
  formatIssue,
  formatIssues,
  formatMessage,
  MessagesProvider,
  ResultToolbar,
  useViewMessages,
  ViewList,
  ViewSurface,
  zhCN,
} from '../src/ui/index.js';

afterEach(cleanup);

const src = join(dirname(fileURLToPath(import.meta.url)), '../src');

/** Every issue code raised anywhere in the package, as written in the source. */
function raisedCodes(): string[] {
  const codes = new Set<string>();
  // Codes carry camelCase segments (chart.splitBy, analysis.distinctCount),
  // so the class must not stop at lowercase.
  const pattern = /issue\(\s*['`]([A-Za-z][A-Za-z0-9.-]*)['`]/g;

  const walk = (directory: string): void => {
    for (const entry of readdirSync(directory)) {
      const path = join(directory, entry);
      if (statSync(path).isDirectory()) {
        walk(path);
        continue;
      }
      if (!path.endsWith('.ts') && !path.endsWith('.tsx')) continue;
      const source = readFileSync(path, 'utf8');
      for (const match of source.matchAll(pattern)) codes.add(match[1]);
    }
  };

  walk(src);
  return [...codes].sort();
}

describe('the message catalogue', () => {
  it('covers every issue code the package can raise', () => {
    // Without this the code itself reaches the screen, which is how
    // `record.summary.unsupported` ended up in a screenshot.
    const missing = raisedCodes().filter(code => !(code in defaultMessages));

    expect(missing).toEqual([]);
  });

  it('is a sentence, not a key', () => {
    expect(defaultMessages['record.summary.unsupported']).toBe(
      '{field} does not offer the {fn} summary.',
    );
  });

  it('is what the default wording is', () => {
    // The split is by prefix family; the English catalogue is only the
    // composition of those files, and nothing else may slip in.
    expect(defaultMessages).toBe(en);
  });
});

/**
 * The second catalogue, which is what makes the first one a catalogue rather
 * than a habit. `zhCN` is typed `Record<MessageKey, string>`, so a missing or
 * misspelt key already fails `tsc`; these two say the same in the suite, and
 * catch the half of it a type cannot see — a sentence that dropped a value.
 */
describe('the Chinese catalogue', () => {
  it('names exactly the keys the English one does', () => {
    expect(Object.keys(zhCN).sort()).toEqual(Object.keys(en).sort());
  });

  it('keeps every placeholder its English sentence carries', () => {
    const placeholders = (sentence: string): string[] =>
      [...sentence.matchAll(/\{(\w+)\}/g)].map(match => match[1]).sort();

    const dropped = Object.entries(en).flatMap(([key, english]) => {
      const mine = new Set(placeholders(zhCN[key] ?? ''));
      const lost = placeholders(english).filter(name => !mine.has(name));
      return lost.length === 0 ? [] : [`${key}: ${lost.join(', ')}`];
    });

    expect(dropped).toEqual([]);
  });

  it('is translated, not copied', () => {
    expect(zhCN['label.filter.apply']).toBe('应用');
    expect(zhCN['label.save.save-as']).toBe('另存为');
    expect(zhCN['record.summary.unsupported']).toBe(
      '{field} 不提供 {fn} 汇总。',
    );
  });

  /**
   * Two words that said something the code does not do.
   *
   * The button under an unknown write calls `abandonWrite`: the intent is
   * given up and nobody can retry or overwrite it afterwards, which is not
   * "deal with it later". And `BEFORE_TODAY` is Wow's own operator, whose
   * word in `packages/wow/src/query/locale/zh_CN.ts` is 今天之前 — the
   * bound is the caller's time of day, so naming the hour was an invention.
   */
  it('says what the button and the operator actually do', () => {
    expect(zhCN['label.unknown.leave']).toBe('放弃');
    expect(en['label.unknown.leave']).toBe('Abandon');
    expect(zhCN['label.operator.BEFORE_TODAY']).toBe('今天之前');
    expect(zhCN['label.operator.RECENT_DAYS']).toBe('最近几天');
    expect(zhCN['label.operator.EARLIER_DAYS']).toBe('几天前');
  });

  it('serves a component through the provider', () => {
    render(
      <MessagesProvider messages={zhCN}>
        <Probe />
      </MessagesProvider>,
    );

    expect(screen.getByText('还没有面板')).toBeTruthy();
  });

  it('is a base a host composes its own wording onto', () => {
    const { result } = renderHook(() => useViewMessages(), {
      wrapper: ({ children }) => (
        <MessagesProvider messages={{ ...zhCN, 'label.filter.apply': '确定' }}>
          {children}
        </MessagesProvider>
      ),
    });

    expect(result.current.label('label.filter.apply')).toBe('确定');
    expect(result.current.label('label.filter.clear')).toBe('清空');
  });
});

/**
 * The enums a control puts in front of a reader.
 *
 * Each of these was named in part or not at all, on the grounds that `EQ`,
 * `bar` and `sum` read acceptably derived from the identifier. They do, in
 * English; `messages={zhCN}` then had no key to hang a Chinese word on and
 * the operator select and the whole analysis editor stayed in English. A
 * closed set is named in full or it is not translatable, and these walk the
 * enums rather than repeating a list that could drift behind them.
 */
describe('the closed enums a control offers', () => {
  const missing = (keys: readonly string[]) => [
    ...keys.filter(key => !(key in en)).map(key => `en: ${key}`),
    ...keys.filter(key => !(key in zhCN)).map(key => `zhCN: ${key}`),
  ];

  it('names every filter operator', () => {
    const keys = Object.values(FilterOperator).map(
      operator => `label.operator.${operator}`,
    );

    expect(missing(keys)).toEqual([]);
  });

  /**
   * Naming a set is not the same as saying it. Half of these were named with
   * the derived spelling they already showed, which for a comparison is the
   * enum in lower case: a condition read `Amount eq Not set`, and the date
   * select offered `between / gte / lte / is empty` — half sentence, half
   * code — where zh-CN had read 「等于」／「大于等于」 all along.
   */
  it('says every comparison operator in words rather than in the enum', () => {
    expect(en['label.operator.EQ']).toBe('is');
    expect(en['label.operator.NE']).toBe('is not');
    expect(en['label.operator.GT']).toBe('more than');
    expect(en['label.operator.GTE']).toBe('at least');
    expect(en['label.operator.LT']).toBe('less than');
    expect(en['label.operator.LTE']).toBe('at most');
    expect(en['label.operator.BETWEEN']).toBe('between');

    const words: Record<string, string> = en;
    const codes = Object.values(FilterOperator).filter(operator =>
      /^(eq|ne|gte?|lte?)$/.test(words[`label.operator.${operator}`]),
    );

    expect(codes).toEqual([]);
  });

  it('names every chart type', () => {
    expect(
      missing(CHART_TYPES.map(type => `label.chart.type.${type}`)),
    ).toEqual([]);
  });

  it('names every grouping and every aggregation function', () => {
    expect(
      missing([
        ...Object.values(AggregationGroupType).map(
          type => `label.group.type.${type}`,
        ),
        ...Object.values(AggregationFunction).map(
          fn => `label.metric.function.${fn}`,
        ),
      ]),
    ).toEqual([]);
  });

  /**
   * The relative-date control's two sets, which had the same gap: it put the
   * identifier itself on screen — `hour`, `thisWeek` — so it stayed in
   * English however the catalogue was replaced.
   */
  it('names every relative unit and every named period', () => {
    expect(
      missing([
        ...RELATIVE_DATE_UNITS.map(unit => `label.relative.unit.${unit}`),
        ...DATE_TIME_PRESETS.map(preset => `label.relative.preset.${preset}`),
      ]),
    ).toEqual([]);
  });

  /**
   * The English half of the same bargain: naming what a component derived
   * must not reword it, or every English test and screenshot moves with it.
   *
   * The operators are the exception, and they paid that price once: the
   * derived spelling of a comparison is the enum in lower case, so a row
   * read `Amount eq Not set` and the date select offered `gte` beside
   * `is empty`. They are words now, and the test that follows walks the
   * whole set for anything still reading as an identifier.
   */
  it('keeps the spelling the editor used to derive', () => {
    expect(en['label.chart.type.bar']).toBe('bar');
    expect(en['label.group.type.DATE_HISTOGRAM']).toBe('date histogram');
    expect(en['label.metric.function.SUM']).toBe('sum');
    // The unit keeps the control's own spelling; the period keeps the one
    // the summary bar already read it out by.
    expect(en['label.relative.unit.day']).toBe('day');
    expect(en['label.relative.preset.nextQuarter']).toBe('next quarter');
  });
});

describe('formatting', () => {
  it('fills placeholders from the issue params', () => {
    expect(
      formatIssue(defaultMessages, {
        code: 'record.summary.unsupported',
        severity: 'error',
        path: [],
        params: { field: 'Amount', fn: 'AVG' },
      }),
    ).toBe('Amount does not offer the AVG summary.');
  });

  it('falls back to the key, so a gap is visible rather than blank', () => {
    expect(formatMessage(defaultMessages, 'not.a.key')).toBe('not.a.key');
  });

  it('falls back along the dots for a composed code', () => {
    // `/react` composes `<command>.<outcome>`; only the commands, and the
    // few outcomes worth naming, need entries of their own.
    expect(formatMessage(defaultMessages, 'view.save.failed.unavailable')).toBe(
      'This view could not be saved.',
    );
    expect(formatMessage(defaultMessages, 'view.open.failed.not_found')).toBe(
      'This view no longer exists.',
    );
  });

  it('does not borrow a sibling entry for an unknown code', () => {
    expect(formatMessage(defaultMessages, 'record.pageSize.bogus')).toBe(
      'record.pageSize.bogus',
    );
  });

  it('leaves a placeholder alone when the issue carries no such param', () => {
    expect(
      formatIssue(defaultMessages, {
        code: 'record.summary.unsupported',
        severity: 'error',
        path: [],
        params: { field: 'Amount' },
      }),
    ).toBe('Amount does not offer the {fn} summary.');
  });

  it('joins several issues into one line', () => {
    expect(
      formatIssues(defaultMessages, [
        { code: 'filter.value.required', severity: 'error', path: [] },
        { code: 'view.title.empty', severity: 'error', path: [] },
      ]),
    ).toBe('This condition needs a value. A view needs a title.');
  });
});

describe('the provider', () => {
  it('serves the defaults with no provider at all', () => {
    const { result } = renderHook(() => useViewMessages());

    expect(result.current.label('label.query.failed')).toBe('The query failed');
  });

  it('merges an override over the defaults', () => {
    const { result } = renderHook(() => useViewMessages(), {
      wrapper: ({ children }) => (
        <MessagesProvider messages={{ 'label.query.failed': '查询失败' }}>
          {children}
        </MessagesProvider>
      ),
    });

    expect(result.current.label('label.query.failed')).toBe('查询失败');
    // Everything not overridden keeps its default wording.
    expect(result.current.label('label.dashboard.empty')).toBe('No panels yet');
  });

  // A surface used to reset to the defaults, so an application that set its
  // wording once around its views lost it inside every one of them.
  it("keeps an application's wording inside a surface, under the surface's own", () => {
    const { result } = renderHook(() => useViewMessages(), {
      wrapper: ({ children }) => (
        <MessagesProvider
          messages={{
            'label.dashboard.empty': '还没有面板',
            'label.query.failed': '查询失败',
          }}
        >
          <ViewSurface messages={{ 'label.query.failed': '查不到' }}>
            {children}
          </ViewSurface>
        </MessagesProvider>
      ),
    });

    expect(result.current.label('label.dashboard.empty')).toBe('还没有面板');
    expect(result.current.label('label.query.failed')).toBe('查不到');
    expect(result.current.label('label.toolbar.refresh')).toBe('Refresh');
  });

  it('merges what a component hands its own surface over the wording in force', () => {
    const { result } = renderHook(
      () => useViewMessages({ 'label.query.failed': '查不到' }),
      {
        wrapper: ({ children }) => (
          <MessagesProvider
            messages={{ 'label.dashboard.empty': '还没有面板' }}
          >
            {children}
          </MessagesProvider>
        ),
      },
    );

    expect(result.current.label('label.query.failed')).toBe('查不到');
    expect(result.current.label('label.dashboard.empty')).toBe('还没有面板');
  });

  it('reaches the components through the surface', () => {
    render(
      <ViewSurface messages={{ 'label.dashboard.empty': '还没有面板' }}>
        <Probe />
      </ViewSurface>,
    );

    expect(screen.getByText('还没有面板')).toBeTruthy();
  });
});

function Probe() {
  const messages = useViewMessages();
  return <p>{messages.label('label.dashboard.empty')}</p>;
}

/**
 * The other half of the catalogue's job.
 *
 * The test above keeps issue codes covered, which is why none of them reach
 * the screen raw. It says nothing about the sentences a component writes
 * itself, and those drifted into JSX exactly where the wording carries the
 * most weight: the conflict dialog and the delete confirmation. A catalogue
 * nothing is obliged to use is a catalogue an application cannot translate.
 *
 * The first version of this scan only saw JSX text of two words or more on a
 * line of its own, so `Clear`, `Apply`, `Refresh` and `Run` sat in the markup
 * for as long as it passed, and so did every `aria-label="Next page"` and
 * every `{ label: 'Only me' }`. It now reads three shapes — JSX text of any
 * length, a string-valued wording prop, and a literal option label — and
 * names the file and line of each, so the next slip is one click away.
 */
describe('components write no copy of their own', () => {
  /** Files whose text is upstream shadcn source, kept verbatim on purpose. */
  const VENDOR = /\/(components|lib)\//;

  /**
   * Props whose value a person reads: a visible placeholder, a tooltip, or
   * the accessible name a screen reader announces in place of one.
   */
  const WORDING_PROP = /\b(aria-label|placeholder|title|alt|label)="([^"]*)"/g;

  /** `{ label: 'Only me' }` — an option's wording, spelt out in a constant. */
  const OPTION_LABEL = /\blabel:\s*'([^']*)'/g;

  /**
   * JSX text: what sits between a closing `>` and the next `<` with no brace
   * between them, since an interpolation would have opened one. Only prose
   * counts — an expression carries brackets, quotes or an operator, and none
   * of those appear in something a person reads.
   */
  const JSX_TEXT = />([^<>{}]*)</g;
  const PROSE = /^[A-Za-z][A-Za-z ,.'’!?%:–—-]*$/;

  /**
   * The escape hatch, written where the exception is: a line carrying
   * `// literal-copy:` and its reason is left alone. It is for the handful of
   * strings that are not wording — a data-only attribute, a code sample, an
   * identifier that only looks like a word.
   */
  const EXEMPT = /\/\/\s*literal-copy:/;

  function compositionFiles(): string[] {
    const found: string[] = [];
    const walk = (directory: string): void => {
      for (const entry of readdirSync(directory)) {
        const path = join(directory, entry);
        if (statSync(path).isDirectory()) {
          walk(path);
          continue;
        }
        if (path.endsWith('.tsx') && !VENDOR.test(path)) found.push(path);
      }
    };
    walk(join(src, 'ui'));
    return found;
  }

  /**
   * Comments carry prose by design; only what renders is at issue. They are
   * blanked rather than removed so every remaining character keeps the line
   * it was on, which is what lets an offender be reported by line.
   */
  function withoutComments(source: string): string {
    const blank = (found: string) => found.replace(/[^\n]/g, ' ');
    return source
      .replace(/\{\s*\/\*[\s\S]*?\*\/\s*\}/g, blank)
      .replace(/\/\*[\s\S]*?\*\//g, blank)
      .replace(/^[ \t]*\/\/.*$/gm, blank);
  }

  function lineOf(source: string, index: number): number {
    return source.slice(0, index).split('\n').length;
  }

  /** Every literal a person would read, as `file:line — text`. */
  function copyIn(name: string, raw: string): string[] {
    const lines = raw.split('\n');
    const source = withoutComments(raw);
    const found: string[] = [];

    const report = (index: number, text: string): void => {
      const line = lineOf(source, index);
      if (EXEMPT.test(lines[line - 1] ?? '')) return;
      found.push(`${name}:${line} — ${text}`);
    };

    for (const match of source.matchAll(JSX_TEXT)) {
      const text = match[1].trim();
      if (text.length === 0 || !PROSE.test(text)) continue;
      report(match.index + 1 + match[1].search(/\S/), text);
    }
    for (const match of source.matchAll(WORDING_PROP)) {
      if (!/[A-Za-z]/.test(match[2])) continue;
      report(match.index, `${match[1]}="${match[2]}"`);
    }
    for (const match of source.matchAll(OPTION_LABEL)) {
      if (!/[A-Za-z]/.test(match[1])) continue;
      report(match.index, `label: '${match[1]}'`);
    }
    return found;
  }

  it.each(compositionFiles())('%s writes no wording of its own', path => {
    expect(
      copyIn(path.slice(src.length + 1), readFileSync(path, 'utf8')),
    ).toEqual([]);
  });

  it('sees a single word, a wording prop and an option label', () => {
    // The scan is the rule, so the rule gets a test of its own: these are the
    // three shapes that walked past the old one, plus the one that may stay.
    const source = [
      'export function Probe() {',
      '  return (',
      '    <div>',
      '      <Button aria-label="Next page">Run</Button>',
      "      <Select items={[{ label: 'Only me', value: 'personal' }]} />",
      '      <span title="Move panel" /> // literal-copy: a worked example',
      '    </div>',
      '  );',
      '}',
    ].join('\n');

    expect(copyIn('ui/Probe.tsx', source)).toEqual([
      'ui/Probe.tsx:4 — Run',
      'ui/Probe.tsx:4 — aria-label="Next page"',
      "ui/Probe.tsx:5 — label: 'Only me'",
    ]);
  });
});

/**
 * What the keys are for. The defaults are what the tests above read, so a
 * label that is keyed but unreachable would look exactly like one that works;
 * these three override the key and check the screen changed.
 */
describe('an application rewords what the components write', () => {
  it('a toolbar button', () => {
    render(
      <MessagesProvider messages={{ 'label.toolbar.columns': '列' }}>
        <ResultToolbar
          table={tableController()}
          fields={[]}
          runtime={{ id: 'r-1' } as unknown as RecordViewRuntime}
        />
      </MessagesProvider>,
    );

    expect(screen.getByRole('button', { name: '列' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Columns/ })).toBeNull();
    // Everything not overridden keeps its default wording.
    expect(screen.getByRole('group', { name: 'Table settings' })).toBeTruthy();
  });

  it('an audience group', () => {
    render(
      <MessagesProvider messages={{ 'label.scope.group.shared': '全员' }}>
        <ViewList
          list={listState([
            {
              id: 'v-1',
              definitionId: 'orders',
              title: 'Pending',
              scope: 'shared',
              kind: 'record',
              revision: '1',
            },
          ])}
          currentId="v-1"
          onOpen={() => {}}
        />
      </MessagesProvider>,
    );

    expect(screen.getByText('全员')).toBeTruthy();
    expect(screen.queryByText('Shared')).toBeNull();
  });

  it('a date shape', () => {
    render(
      <MessagesProvider messages={{ 'label.date.absolute': '某一天' }}>
        <FilterValueEditor
          editor={{ input: 'date' }}
          value={
            { type: 'absolute', from: '2026-01-31' } as unknown as FilterValue
          }
          label="amount"
          onChange={() => {}}
        />
      </MessagesProvider>,
    );

    expect(screen.getByLabelText('amount kind').textContent).toContain(
      '某一天',
    );
  });
});

/** The little a `ResultToolbar` reads off its controller, and nothing more. */
function tableController(): RecordTableController {
  return {
    columns: [],
    rows: [],
    card: { title: '', fields: [] },
    paging: null,
    summaries: null,
    status: 'success',
    error: null,
    loading: false,
    hasResult: true,
    sort: [],
    sortOf: () => null,
    toggleSort: () => {},
    setSort: () => {},
    maxSortFields: 8,
    layout: 'table',
    layouts: ['table'],
    setLayout: () => {},
    columnFields: [],
    setColumns: () => {},
    setColumnOrder: () => {},
    pinnedOf: () => null,
    setPinned: () => {},
    setColumnWidth: () => {},
    summaryOf: () => null,
    setSummary: () => {},
    pageSize: 20,
    pageSizes: [10, 20, 50, 100],
    setPageSize: () => {},
    selection: [],
    selectedRows: [],
    isSelected: () => false,
    toggle: () => {},
    toggleAll: () => {},
    clearSelection: () => {},
    goTo: () => {},
    hasNext: false,
    next: () => {},
    previous: () => {},
    refresh: () => {},
  };
}

function listState(items: ViewInstanceSummary[]): ViewListState {
  return {
    items,
    all: items,
    preferences: null,
    permissions: {
      createPersonal: true,
      createShared: true,
      reorder: true,
      setDefault: true,
      instance: () => ({ save: true, rename: true, delete: true }),
    },
    defaultInstanceId: null,
    loading: false,
    error: null,
    preferencesError: null,
    reload: () => {},
  };
}
