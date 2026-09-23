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

import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import type { FilterSummaryItem } from '../src/index.js';
import { useFilterEditor } from '../src/react/index.js';
import type { FilterEditorController } from '../src/react/index.js';
import { AppliedBar } from '../src/ui/AppliedBar.js';
import { MessagesProvider } from '../src/ui/MessagesProvider.js';
import { zhCN } from '../src/ui/messages/zh-CN.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';

/**
 * One condition as the kernel hands it over: the parts, and the English line
 * the kind reads them as. The bar shows the parts — `text` is what a host
 * consuming the summary itself still gets — so every item here carries both.
 */
function condition(over: Partial<FilterSummaryItem> = {}): FilterSummaryItem {
  return {
    path: ['children', 0],
    text: 'Warehouse EQ CN',
    unresolved: false,
    field: 'warehouse',
    label: 'Warehouse',
    kind: 'string',
    operator: 'EQ',
    value: { kind: 'text', value: 'CN' },
    ...over,
  };
}

afterEach(cleanup);

/**
 * A controller with only what the bar reads. The bar describes what came
 * back and offers one action per item; everything else on the controller is
 * the editor's business, and a stub says so.
 */
function stub(
  applied: FilterSummaryItem[],
  actions: Partial<FilterEditorController> = {},
): FilterEditorController {
  return {
    applied,
    scoped: [],
    implied: [],
    clearValue: vi.fn(),
    submit: vi.fn(),
    ...actions,
  } as unknown as FilterEditorController;
}

/** The host's own condition, which the bar shows but cannot take out. */
function customer(): FilterSummaryItem {
  return condition({
    text: 'Customer EQ c-1',
    field: 'customer',
    label: 'Customer',
    value: { kind: 'text', value: 'c-1' },
  });
}

/** The bar over a live runtime, with the editor in reach. */
function overRuntime(definition = ordersDefinition()) {
  const engine = new ViewEngine({
    definitions: [definition],
    store: new MemoryViewStore({ instances: [] }),
    resolveSource: () => testSource(),
  });
  const runtime = engine.create('orders', {
    title: 'Scratch',
    scope: 'personal',
    config: recordConfig(),
  });
  let latest: FilterEditorController | null = null;
  function Probe() {
    const filter = useFilterEditor(runtime);
    latest = filter;
    return (
      <AppliedBar
        filter={filter}
        asked={runtime.getSnapshot().result !== null}
      />
    );
  }
  render(<Probe />);
  return { filter: () => latest as FilterEditorController };
}

describe('AppliedBar', () => {
  it('says nothing before a result exists to describe', () => {
    // An empty `applied` means "no condition" *or* "no answer yet", and the
    // bar cannot tell them apart on its own — so it is told.
    const { container } = render(
      <AppliedBar filter={stub([])} asked={false} />,
    );

    expect(container.innerHTML).toBe('');
  });

  it('says so plainly when the result ran under no condition at all', () => {
    render(<AppliedBar filter={stub([])} asked />);

    const bar = screen.getByRole('region', { name: 'Showing' });
    expect(bar.textContent).toContain('All records');
  });

  it('lists the conditions the rows in front of you were fetched under', () => {
    render(<AppliedBar filter={stub([condition()])} asked />);

    // The badge is built from the parts, so the operator is the catalogue's
    // word for it rather than the enum name the English line carries.
    expect(screen.getByText('Warehouse is CN')).toBeDefined();
    expect(screen.queryByText('All records')).toBeNull();
  });

  it('wears a condition whose field is gone plainly, and marks it', () => {
    render(
      <AppliedBar
        filter={stub([
          condition({
            text: 'legacy EQ 1',
            unresolved: true,
            field: 'legacy',
            label: 'legacy',
            kind: undefined,
            value: { kind: 'blank' },
          }),
        ])}
        asked
      />,
    );

    // It is still in force, so it is named rather than hidden; it is not
    // something to go on building on, so it is not dressed as one. The
    // value cannot be read, but the question it was asked under can.
    const badge = screen.getByText('legacy is');
    expect(badge.hasAttribute('data-unresolved')).toBe(true);
    // A **surviving class assertion**: worn plainly means the registry's
    // `outline` badge rather than a toned one, and a variant is not
    // reflected on the element — the class is the only witness. `ToneBadge`
    // is what says a tone on itself, and this badge is deliberately not one.
    expect(badge.className).toContain('border-border');
  });

  it('draws its remove as the ghost icon button every badge here wears', async () => {
    const { filter } = overRuntime();
    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
      filter().submit();
    });
    const remove = await screen.findByRole('button', {
      name: 'Unset Warehouse is CN',
    });
    // The vendored `Button` rather than a bare one carrying a hand-copied
    // focus recipe: it sizes its own icon (so nothing asks for a size here)
    // and brings the one focus indicator the whole surface shares.
    // **Surviving class assertions**: the icon carries no size of its own
    // (the shadcn rule — the button sizes it), and the focus recipe is the
    // vendored button's rather than a hand-copied one. Neither is a state
    // the element could say, and `:focus-visible` is a pseudo-class jsdom
    // has no rendering for; what they come to is measured in the browser.
    expect(remove.querySelector('svg')!.className.baseVal).not.toContain(
      'size-',
    );
    expect(remove.className).toContain('size-6');
    expect(remove.className).toContain('focus-visible:border-ring');
    expect(remove.className).toContain('focus-visible:opacity-100');
  });

  it('takes a condition out of force from its badge, keeping the field', async () => {
    const { filter } = overRuntime();
    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
      filter().submit();
    });
    await waitFor(() =>
      expect(screen.getByText('Warehouse is CN')).toBeDefined(),
    );

    fireEvent.click(
      screen.getByRole('button', { name: 'Unset Warehouse is CN' }),
    );

    await waitFor(() =>
      expect(screen.queryByText('Warehouse is CN')).toBeNull(),
    );
    // The condition left the query; the row is still in the editor, blank,
    // for the next question.
    expect(filter().applied).toEqual([]);
    expect(filter().tree.children[0]).toMatchObject({ field: 'warehouse' });
  });

  it('clears the value and re-runs in one go', () => {
    const clearValue = vi.fn();
    const submit = vi.fn();
    render(
      <AppliedBar
        filter={stub(
          [
            condition({
              path: ['children', 1, 'children', 0],
              text: 'Amount GT 10',
              field: 'amount',
              label: 'Amount',
              kind: 'number',
              operator: 'GT',
              value: { kind: 'text', value: 10 },
            }),
          ],
          { clearValue, submit },
        )}
        asked
      />,
    );

    fireEvent.click(
      screen.getByRole('button', { name: 'Unset Amount more than 10' }),
    );

    // The editor addresses nodes by index; the `children` keys of a summary
    // path are not part of the address.
    expect(clearValue).toHaveBeenCalledWith([1, 0]);
    expect(submit).toHaveBeenCalledTimes(1);
  });

  it('freezes its removes with the rest of the view', () => {
    render(<AppliedBar filter={stub([condition()])} asked disabled />);

    expect(
      (
        screen.getByRole('button', {
          name: 'Unset Warehouse is CN',
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
  });
  /**
   * The host's own conditions are in force beside the view's own, and the
   * editor has no path that reaches them: they are not in the draft, and
   * `clearValue` cannot address them. So they are named — a reader who
   * cannot see why the list is short learns nothing — and they carry no ✕,
   * because the only honest thing a ✕ could do here is fail.
   */
  it("names the host scope after the view's own, without a way to remove it", () => {
    render(
      <AppliedBar
        filter={stub([condition()], { scoped: [customer()] })}
        asked
      />,
    );

    const badges = [...document.querySelectorAll('[data-slot="badge"]')];
    expect(badges.map(badge => badge.textContent?.trim())).toEqual([
      'Warehouse is CN',
      'Customer is c-1 Set by the page',
    ]);
    // The view's own is removable and dressed as a condition; the page's is
    // worn plainly and offers nothing to press.
    expect(badges[0].hasAttribute('data-scoped')).toBe(false);
    expect(badges[1].hasAttribute('data-scoped')).toBe(true);
    // Worn plainly — the `outline` badge, as above.
    expect(badges[1].className).toContain('border-border');
    expect(
      screen.queryByRole('button', { name: 'Unset Customer is c-1' }),
    ).toBeNull();
    expect(
      screen.getByRole('button', { name: 'Unset Warehouse is CN' }),
    ).toBeDefined();
  });

  /**
   * "All records" answers for everything in force, and a scope is in force.
   * Saying it beside a narrowing the page applied would be the bar telling
   * the reader the opposite of what the rows below it are.
   */
  it('does not call a scoped result all of them', () => {
    render(<AppliedBar filter={stub([], { scoped: [customer()] })} asked />);

    expect(screen.queryByText('All records')).toBeNull();
    expect(screen.getByText(/Customer is c-1/)).toBeDefined();
  });

  /**
   * Read-only is not "disabled": an embedded view shows what its author
   * saved, and a ✕ that is only ever grey still tells the reader there is a
   * condition here they might get to drop.
   */
  /**
   * D17-2: a declared deletion dimension left blank shows the records that
   * are not deleted. That reading is in force, so the bar says it — worn
   * like the scope, with no ✕, since it is not in the config to take out.
   */
  it('says the default reading of a declared deletion dimension', () => {
    const implied: FilterSummaryItem = condition({
      path: [],
      text: 'Deleted ACTIVE',
      field: '@deleted',
      label: 'Deleted',
      kind: 'deletion',
      operator: 'DELETION',
      value: { kind: 'text', value: 'ACTIVE' },
    });
    render(<AppliedBar filter={stub([], { implied: [implied] })} asked />);

    const badge = screen.getByText(/Deleted/).closest('[data-slot="badge"]');
    expect(badge?.hasAttribute('data-implied')).toBe(true);
    expect(badge?.textContent).toContain('Deleted is Not deleted');
    expect(badge?.textContent).toContain('By default');
    expect(screen.queryByRole('button')).toBeNull();
    // Rows narrowed by the default are not all of them.
    expect(screen.queryByText('All records')).toBeNull();
  });

  it('stops implying the reading once the view has written one', async () => {
    const { filter } = overRuntime(
      ordersDefinition({
        fields: [
          ...ordersDefinition().fields,
          { name: '@deleted', label: 'Deleted', kind: 'deletion' },
        ],
      }),
    );
    await waitFor(() =>
      expect(
        screen.getByText(/Deleted/).closest('[data-slot="badge"]'),
      ).toBeTruthy(),
    );
    expect(
      screen
        .getByText(/Deleted/)
        .closest('[data-slot="badge"]')
        ?.hasAttribute('data-implied'),
    ).toBe(true);

    act(() => {
      filter().addLeaf('@deleted');
      filter().updateLeaf([0], { value: 'ALL' });
    });
    act(() => filter().submit());

    // Written, it is a condition like any other: removable, and no longer
    // said to be the default.
    await waitFor(() =>
      expect(screen.getByText(/Deleted included/)).toBeTruthy(),
    );
    expect(screen.queryByText('By default')).toBeNull();
    expect(
      screen.getByRole('button', { name: /Deleted is Deleted included/ }),
    ).toBeTruthy();
  });

  it('renders no remove at all when it is read-only', () => {
    render(<AppliedBar filter={stub([condition()])} asked readOnly />);

    expect(screen.getByText('Warehouse is CN')).toBeDefined();
    expect(screen.queryByRole('button')).toBeNull();
  });
});

/**
 * A condition's value reads in the bar the way it reads in the table: the
 * rule is `cell ?? kind` plus the field's own `numberFormat`, and the bar is
 * beside the rows it describes, so a value shown two ways in one screen is
 * the reader's problem either way.
 */
describe('a value the way its field shows it', () => {
  const YUAN = { style: 'currency', currency: 'CNY' } as const;
  const money = (value: number) =>
    new Intl.NumberFormat(undefined, YUAN).format(value);

  /**
   * A `cell` that overrides the kind used not to travel with the summary, so
   * a millisecond instant stored as a number showed as a date in the table
   * and as thirteen digits in the bar above it.
   */
  it('honours a renderer key that overrides the kind', () => {
    const instant = Date.parse('2026-01-31T12:00:00.000Z');
    render(
      <AppliedBar
        filter={stub([
          condition({
            text: `Shipped GT ${instant}`,
            field: 'shippedAt',
            label: 'Shipped',
            kind: 'number',
            cell: 'date',
            operator: 'GT',
            value: { kind: 'text', value: instant },
          }),
        ])}
        asked
      />,
    );

    const shown = new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
    }).format(instant);
    expect(screen.getByText(`Shipped more than ${shown}`)).toBeDefined();
    expect(screen.queryByText(new RegExp(String(instant)))).toBeNull();
  });

  /**
   * An open-ended array names no candidates, so every entry used to arrive
   * as a "label" that was only the entry stringified — and a label wins over
   * the field's format, which put bare numbers beside a column of currency.
   */
  it('formats the entries of an array the definition named no labels for', () => {
    render(
      <AppliedBar
        filter={stub([
          condition({
            text: 'Charges has any of 100, 5000',
            field: 'charges',
            label: 'Charges',
            kind: 'array',
            numberFormat: YUAN,
            operator: 'IN',
            value: { kind: 'list', values: [100, 5000] },
          }),
        ])}
        asked
      />,
    );

    expect(
      screen.getByText(`Charges is any of ${money(100)}, ${money(5000)}`),
    ).toBeDefined();
  });

  /** A label the definition did give still wins, per entry. */
  it('keeps a named entry named and formats the rest', () => {
    render(
      <AppliedBar
        filter={stub([
          condition({
            text: 'Charges has any of Handling, 5000',
            field: 'charges',
            label: 'Charges',
            kind: 'array',
            numberFormat: YUAN,
            operator: 'IN',
            value: {
              kind: 'list',
              values: [100, 5000],
              labels: ['Handling', undefined],
            },
          }),
        ])}
        asked
      />,
    );

    expect(
      screen.getByText(`Charges is any of Handling, ${money(5000)}`),
    ).toBeDefined();
  });
});

/**
 * The bar is the most visible text of the result area, and it used to be the
 * one line no catalogue could reach: each kind handed over a finished English
 * sentence — "Status IN Pending", "is empty", "on or before" — so a page in
 * Chinese had an English badge sitting on top of its rows. It is built from
 * parts now, and these say so in the language that is in force.
 */
describe('the applied badge in another language', () => {
  const inChinese = (applied: FilterSummaryItem[]) =>
    render(
      <MessagesProvider messages={zhCN}>
        <AppliedBar filter={stub(applied)} asked />
      </MessagesProvider>,
    );

  /** The saved view every Chinese story opens with: 状态 is one of 待出库. */
  const status = (): FilterSummaryItem =>
    condition({
      text: 'Status IN Pending',
      field: 'status',
      label: '状态',
      kind: 'enum',
      operator: 'IN',
      value: { kind: 'list', values: ['PENDING'], labels: ['待出库'] },
    });

  it('says the field, the operator and the option in Chinese', () => {
    inChinese([status()]);

    expect(screen.getByText('状态 属于 待出库')).toBeDefined();
    // And nothing of the English line the kind produced beside it.
    expect(screen.queryByText(/Status IN Pending/)).toBeNull();
  });

  /**
   * An array's `IN` asks whether the array holds any of the candidates, not
   * whether a value is one of them. Reading it with the scalar's word tells
   * the reader the wrong thing about which rows are below.
   */
  it('reads an array condition as containment, not membership', () => {
    inChinese([
      condition({
        text: 'Tags has any of Urgent',
        field: 'tags',
        label: '标签',
        kind: 'array',
        operator: 'IN',
        relation: 'has-any',
        value: { kind: 'list', values: ['URGENT'], labels: ['加急'] },
      }),
    ]);

    expect(screen.getByText('标签 含有其中任一 加急')).toBeDefined();
    expect(screen.queryByText(/属于/)).toBeNull();
  });

  it('names the whole bar in Chinese, remove included', () => {
    inChinese([status()]);

    expect(screen.getByRole('region', { name: '正在显示' })).toBeDefined();
    expect(
      screen.getByRole('button', { name: '清空 状态 属于 待出库' }),
    ).toBeDefined();
  });

  it('reads a presence question as the operator alone', () => {
    // `is empty` was hard-coded English inside the kind; the operator word
    // is the whole condition, so the catalogue is all it needs.
    inChinese([
      condition({
        text: 'Warehouse is empty',
        label: '仓库',
        operator: 'IS_NULL',
        value: { kind: 'none' },
      }),
    ]);

    expect(screen.getByText('仓库 为空')).toBeDefined();
  });

  const when = (over: Partial<FilterSummaryItem>): FilterSummaryItem =>
    condition({
      field: 'createdAt',
      label: '创建时间',
      kind: 'datetime',
      ...over,
    });

  it('reads a named period as words', () => {
    inChinese([
      when({
        text: 'Created on or before next quarter',
        operator: 'LTE',
        value: { kind: 'preset', preset: 'nextQuarter' },
      }),
    ]);

    expect(screen.getByText('创建时间 小于等于 下季度')).toBeDefined();
  });

  /**
   * The same stored distance is two conditions: `BETWEEN` asks for the span
   * between now and seven days ago, `LTE` compares against the moment at the
   * end of it. Reading the second as 最近 7 天 describes a query that never
   * ran, which is the worst thing this bar can do.
   */
  it('tells a relative window from the moment at the end of it', () => {
    inChinese([
      when({
        text: 'Created last 7 day',
        path: ['children', 0],
        operator: 'BETWEEN',
        value: {
          kind: 'relative',
          amount: 7,
          unit: 'day',
          direction: 'past',
          bound: 'window',
        },
      }),
      when({
        text: 'Created on or before 7 day ago',
        path: ['children', 1],
        operator: 'LTE',
        value: {
          kind: 'relative',
          amount: 7,
          unit: 'day',
          direction: 'past',
          bound: 'instant',
        },
      }),
      when({
        text: 'Created on or after 7 day ahead',
        path: ['children', 2],
        operator: 'GTE',
        value: {
          kind: 'relative',
          amount: 7,
          unit: 'day',
          direction: 'future',
          bound: 'instant',
        },
      }),
    ]);

    expect(screen.getByText('创建时间 介于 最近 7 天')).toBeDefined();
    expect(screen.getByText('创建时间 小于等于 7 天前')).toBeDefined();
    expect(screen.getByText('创建时间 大于等于 7 天后')).toBeDefined();
  });

  /** A window with no upper edge is the `GTE` it compiles to, not a range. */
  it('words an open-ended window as the bound it compiles to', () => {
    inChinese([
      when({
        text: 'Created from 2026-01-01',
        operator: 'GTE',
        kind: 'date',
        value: { kind: 'text', value: '2026-01-01' },
      }),
    ]);

    expect(screen.getByText(/^创建时间 大于等于 /)).toBeDefined();
  });

  /**
   * `describeFilter` folds a predicate root that is not "all of" into one
   * group item carrying that operator; the kind states the operator beside
   * it anyway, so passing the fold on said it twice — and turned a
   * one-condition 都不满足 into a double negative.
   */
  it('reads a predicate once, under the operator it holds its conditions by', () => {
    const sku = (value: string, index: number): FilterSummaryItem =>
      condition({
        path: ['children', 0, 'children', index],
        text: `SKU EQ ${value}`,
        field: 'items.sku',
        label: 'SKU',
        value: { kind: 'text', value },
      });
    const predicate = (
      group: FilterSummaryItem['group'],
      items: FilterSummaryItem[],
    ): FilterSummaryItem =>
      condition({
        text: 'Items has an entry where',
        field: 'items',
        label: '明细',
        kind: 'elementMatch',
        operator: 'ELEMENT_MATCH',
        value: { kind: 'none' },
        group,
        items,
      });

    const { rerender } = render(
      <MessagesProvider messages={zhCN}>
        <AppliedBar
          filter={stub([predicate('or', [sku('A', 0), sku('B', 1)])])}
          asked
        />
      </MessagesProvider>,
    );
    expect(
      screen.getByText('明细 任一条目满足 满足任一 SKU 等于 A、SKU 等于 B'),
    ).toBeDefined();

    // One condition under `nor` is its negation — the pill's own switch
    // (D18-7) — and is said as that, not as a group of one under 都不满足.
    rerender(
      <MessagesProvider messages={zhCN}>
        <AppliedBar filter={stub([predicate('nor', [sku('A', 0)])])} asked />
      </MessagesProvider>,
    );
    expect(screen.getByText('明细 任一条目满足 排除 SKU 等于 A')).toBeDefined();
  });

  it("reads a group out under its own operator's word", () => {
    inChinese([
      {
        path: [],
        text: 'Status IN Pending or Amount 1 ~ 9',
        unresolved: false,
        group: 'or',
        items: [
          status(),
          condition({
            text: 'Amount 1 ~ 9',
            path: ['children', 1],
            field: 'amount',
            label: '金额',
            kind: 'number',
            operator: 'BETWEEN',
            value: { kind: 'range', from: 1, to: 9 },
          }),
        ],
      },
    ]);

    expect(
      screen.getByText('满足任一 状态 属于 待出库、金额 介于 1 ~ 9'),
    ).toBeDefined();
  });
});
