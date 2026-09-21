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
import { expect, userEvent, waitFor, within } from 'storybook/test';
import {
  defaultMessages,
  formatMessage,
} from '@ahoo-wang/fetcher-view-engine/ui';
import displayMeta, {
  Advanced as DisplayAdvanced,
  NumberList as DisplayNumberList,
  Simple as DisplaySimple,
} from './FilterPanel.stories.js';
import { amountOf, readColumn, readTotal } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/数据视图/筛选编辑器/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

/** The amount field's declared format, as the bar itself formats it. */
const yuan = (value: number) =>
  new Intl.NumberFormat(undefined, {
    style: 'currency',
    currency: 'CNY',
  }).format(value);

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
      await canvas.findByText(defaultMessages['label.record.empty-hint']),
    ).toBeVisible();

    // The bar over the result is the one place this tree is read back as
    // sentences, and it is built from the parts each kind hands over. This
    // is the only fixture carrying all three of the shapes that get it
    // wrong: a named period, a nested group, and a predicate.
    const applied = canvas.getByRole('region', {
      name: defaultMessages['label.applied.title'],
    });
    const badges = [...applied.querySelectorAll('[data-slot="badge"]')].map(
      badge => badge.textContent?.trim(),
    );

    await expect(badges).toEqual([
      '仓库 is any of 华东',
      '状态 is any of 待出库, 已发运',
      // The field's own `numberFormat`, from the same Intl call the bar
      // makes — which currency symbol ICU picks is not what this is about.
      `金额 between ${yuan(100)} ~ ${yuan(5000)}`,
      // A period, not a range: the operator asks for the window it names.
      '创建时间 between this month',
      // A group says how its conditions combine before it lists them.
      `Any of 仓库 is any of 华北, 金额 more than ${yuan(20000)}`,
      // And a predicate reads its own conditions out once, under the one
      // operator it holds them by.
      '商品行 has an entry where All of SKU is A-1, 数量 more than 2',
    ]);
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
        name: new RegExp(`^${defaultMessages['label.filter.panel']}`),
      }),
    );

    const removes = () =>
      canvas
        .queryAllByRole('button', { name: /^Remove \d/ })
        .map(button => button.getAttribute('aria-label'));
    const remove = (value: number) =>
      canvas.getByRole('button', {
        name: formatMessage(defaultMessages, 'label.filter.remove-value', {
          value: String(value),
        }),
      });

    // Three values, which the two boxes of a range could never have held.
    await waitFor(() =>
      expect(removes()).toEqual(['Remove 100', 'Remove 1200', 'Remove 5000']),
    );

    // A fourth, entered by hand: Enter commits it and clears the field, and
    // it does not double as the panel's apply.
    const entry = canvas.getByLabelText('New 金额 value');
    await userEvent.type(entry, '8888{Enter}');
    await waitFor(() => expect(removes()).toContain('Remove 8888'));
    await expect(entry).toHaveValue(null);

    // And one taken back out, by the button that names it.
    await userEvent.click(remove(1200));
    await waitFor(() =>
      expect(removes()).toEqual(['Remove 100', 'Remove 5000', 'Remove 8888']),
    );
  },
};
