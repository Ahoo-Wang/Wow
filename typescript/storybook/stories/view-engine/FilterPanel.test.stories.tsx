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
import type { StoryObj } from '@storybook/react-vite';
import { expect, fireEvent, userEvent, waitFor, within } from 'storybook/test';
import { formatMessage, zhCN } from '@ahoo-wang/fetcher-view-engine/ui';
import displayMeta, {
  Advanced as DisplayAdvanced,
  NumberList as DisplayNumberList,
  Simple as DisplaySimple,
  WithTime as DisplayWithTime,
} from './FilterPanel.stories.js';
import { amountOf, readColumn, readTotal } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/数据视图/筛选编辑器/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

/**
 * The amount field's declared format, as the bar itself formats it. The
 * field's `numberFormat` names no language, so this is the host's own —
 * the surface's `locale` reaches the dates, not a format that set its own.
 */
const yuan = (value: number) =>
  new Intl.NumberFormat(undefined, {
    style: 'currency',
    currency: 'CNY',
  }).format(value);

/** What the catalogue in force puts between two conditions. */
const JOIN = zhCN['label.filter.join'];

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** Pending and at least 100: every pending order, in the order they came. */
export const Simple: Story = {
  ...DisplaySimple,
  play: async ({ canvasElement }) => {
    const table = await within(canvasElement).findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual([
        'SO-1001',
        'SO-1003',
        'SO-1005',
        'SO-1006',
      ]),
    );
    await expect(amountOf(readTotal(table, '金额'))).toBe(6470);
  },
};

/**
 * The rich tree asks for 华东 and, in its OR group, for 华北 or more than
 * 20,000 at once: no order is both, and the table says so.
 */
export const Advanced: Story = {
  ...DisplayAdvanced,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      await canvas.findByText(zhCN['label.record.empty-hint']),
    ).toBeVisible();

    // The bar over the result is the one place this tree is read back as
    // sentences, and it is built from the parts each kind hands over. This
    // is the only fixture carrying all three of the shapes that get it
    // wrong: a named period, a nested group, and a predicate.
    const applied = canvas.getByRole('region', {
      name: zhCN['label.applied.title'],
    });
    const badges = [...applied.querySelectorAll('[data-slot="badge"]')].map(
      badge => badge.textContent?.trim(),
    );

    // Every word below is read back out of the catalogue rather than typed
    // in: the badge is the one line that has to change with `messages`, so a
    // hard-coded expectation would only ever prove that nothing changed.
    await expect(badges).toEqual([
      `仓库 ${zhCN['label.operator.IN']} 华东`,
      `状态 ${zhCN['label.operator.IN']} 待出库${JOIN}已发运`,
      // The field's own `numberFormat`, from the same Intl call the bar
      // makes — which currency symbol ICU picks is not what this is about.
      `金额 ${zhCN['label.operator.BETWEEN']} ${yuan(100)} ~ ${yuan(5000)}`,
      // A period, not a range: the operator asks for the window it names.
      `创建时间 ${zhCN['label.operator.BETWEEN']} ${zhCN['label.relative.preset.thisMonth']}`,
      // A group says how its conditions combine before it lists them.
      `${zhCN['label.filter.any-of']} 仓库 ${zhCN['label.operator.IN']} 华北` +
        `${JOIN}金额 ${zhCN['label.operator.GT']} ${yuan(20000)}`,
      // And a predicate reads its own conditions out once, under the one
      // operator it holds them by.
      `商品行 ${zhCN['label.operator.ELEMENT_MATCH']} ` +
        `${zhCN['label.filter.all-of']} SKU ${zhCN['label.operator.EQ']} A-1` +
        `${JOIN}数量 ${zhCN['label.operator.GT']} 2`,
    ]);
  },
};

/**
 * A `withTime` field's condition carries a time of day, in the same control
 * as the calendar and with one submission (D17-1). The box starts empty,
 * which is the day itself — read at `00:00:00.000` as a start and at
 * `23:59:59.999` as an end — and the summary says the time only where one
 * was given, so the badge never claims a boundary the query did not run to.
 *
 * Only a real browser can drive a native time input, which is why this lives
 * here and not in jsdom.
 */
export const WithTime: Story = {
  ...DisplayWithTime,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(
      await canvas.findByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );

    // The calendar and its clock are one control, opened from the pill.
    const trigger = await canvas.findByLabelText(
      formatMessage(zhCN, 'label.filter.value-of', { field: '创建时间' }),
    );
    await userEvent.click(trigger);

    const popup = within(document.body);
    const from = await popup.findByLabelText(zhCN['label.date.time-from']);
    // Both boxes are empty to begin with: that is the whole day, not
    // midnight, and it is what the hint under them says.
    await expect(from).toHaveValue('');
    await expect(popup.getByLabelText(zhCN['label.date.time-to'])).toHaveValue(
      '',
    );

    // Reachable and editable from the keyboard: the box takes focus, and
    // the value is then set the way the browser's own spin fields set it.
    // Synthetic keystrokes do not drive a native time input's segments —
    // they are untrusted, so Chromium ignores them — which is why this
    // changes the value rather than typing six digits into it.
    await userEvent.click(from);
    await expect(from).toHaveFocus();
    fireEvent.change(from, { target: { value: '15:30:00' } });
    await waitFor(() => expect(from).toHaveValue('15:30:00'));
    await userEvent.keyboard('{Escape}');

    // The trigger reads the bound back with the time on the start edge and
    // without one on the end, through the surface's own formatter: a
    // wall-clock string names a time on a clock rather than a moment, so it
    // is shown as written whatever zone the browser is in.
    const shown = (utc: number, withTime: boolean) =>
      new Intl.DateTimeFormat('zh-CN', {
        dateStyle: 'medium',
        ...(withTime ? { timeStyle: 'medium' as const } : {}),
        timeZone: 'UTC',
      }).format(utc);
    await waitFor(() =>
      expect(trigger.textContent).toBe(
        `${shown(Date.UTC(2026, 8, 15, 15, 30), true)} – ` +
          shown(Date.UTC(2026, 8, 17), false),
      ),
    );

    await userEvent.click(
      await canvas.findByRole('button', { name: zhCN['label.filter.apply'] }),
    );

    // And the applied badge says the same: a bound with a time of day says
    // it, one without stays a day.
    const applied = canvas.getByRole('region', {
      name: zhCN['label.applied.title'],
    });
    await waitFor(() =>
      expect(
        [...applied.querySelectorAll('[data-slot="badge"]')].map(badge =>
          badge.textContent?.trim(),
        ),
      ).toEqual([
        `创建时间 ${zhCN['label.operator.BETWEEN']} ` +
          `${shown(Date.UTC(2026, 8, 15, 15, 30), true)} ~ ` +
          shown(Date.UTC(2026, 8, 17), false),
      ]),
    );
  },
};

/**
 * A numeric `IN` takes as many values as the kernel compiles. The editor used
 * to borrow the range's pair of boxes, so a third value had nowhere to go —
 * and only a test that types into the editor catches that, because the kernel
 * itself never refused the array.
 */
export const NumberList: Story = {
  ...DisplayNumberList,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    // A saved view opens with its editor folded.
    await userEvent.click(
      await canvas.findByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );

    // Each chip's ✕ names the value it drops, in the wording in force.
    const removeLabel = (value: number) =>
      formatMessage(zhCN, 'label.filter.remove-value', {
        value: String(value),
      });
    const ANY_VALUE = new RegExp(
      `^${zhCN['label.filter.remove-value'].replace('{value}', String.raw`\d+`)}$`,
    );
    const removes = () =>
      canvas
        .queryAllByRole('button', { name: ANY_VALUE })
        .map(button => button.getAttribute('aria-label'));
    const remove = (value: number) =>
      canvas.getByRole('button', { name: removeLabel(value) });

    // Three values, which the two boxes of a range could never have held.
    await waitFor(() =>
      expect(removes()).toEqual([100, 1200, 5000].map(removeLabel)),
    );

    // A fourth, entered by hand: Enter commits it and clears the field, and
    // it does not double as the panel's apply. The entry field is named after
    // the field it adds to, which is two catalogue entries deep.
    const entry = canvas.getByLabelText(
      formatMessage(zhCN, 'label.filter.new-value-of', {
        field: formatMessage(zhCN, 'label.filter.value-of', { field: '金额' }),
      }),
    );
    await userEvent.type(entry, '8888{Enter}');
    await waitFor(() => expect(removes()).toContain(removeLabel(8888)));
    await expect(entry).toHaveValue(null);

    // And one taken back out, by the button that names it.
    await userEvent.click(remove(1200));
    await waitFor(() =>
      expect(removes()).toEqual([100, 5000, 8888].map(removeLabel)),
    );
  },
};
