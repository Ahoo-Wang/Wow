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

import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import {
  actions,
  systemInstanceId,
  type RecordActions,
  type RecordRow,
} from '@ahoo-wang/wow-view-engine';
import { DataWorkbench, zhCN } from '@ahoo-wang/wow-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import { createStoryEngine, HOST_LANGUAGE } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * Declared actions (host-integration.md 5): the host says what a command is
 * and when an order takes it; the engine places it — the row's button and
 * its 「⋯」 menu, the selection's bar, the detail — asks first, splits a
 * selection by what each order takes, runs it a few at a time, and says
 * how it went. One story per placement and per flow.
 */

const amountOf = (row: RecordRow) => Number(row.data.amount ?? 0);
const statusOf = (row: RecordRow) => String(row.data.status ?? '');

/** Waits long enough for a run to be seen, and stops. */
const slowly = (ms: number) =>
  new Promise<void>(resolve => setTimeout(resolve, ms));

/**
 * The warehouse's commands on an order. 「发货」 is the everyday one; an
 * order under 1,000 goes to a manual review first, and a cancelled one never
 * ships. 「取消订单」 costs the customer their order, so it asks. 「设优先级」
 * is a choice; 「备注」 a form.
 */
function orderActions(delay = 300, opensAt?: number): RecordActions {
  return actions([
    {
      id: 'ship',
      label: '发货',
      primary: true,
      available: (row, { now }) => {
        if (statusOf(row) === 'CANCELLED') return '已取消的订单不能发货';
        if (opensAt !== undefined && now <= opensAt)
          return '仓库尚未开门，稍后再发';
        return amountOf(row) >= 1000 ? true : '金额不足 1000，先走人工审核';
      },
      changesAt: (_row, { now }) =>
        opensAt !== undefined && now <= opensAt ? opensAt + 1 : null,
      confirm: { title: '发出 {count} 张订单？', ask: 'bulk' },
      run: () => slowly(delay),
    },
    {
      id: 'cancel',
      label: '取消订单',
      tone: 'danger',
      confirm: {
        title: '取消 {count} 张订单？',
        body: '订单会退款并关闭，不能撤回。',
      },
      run: async row => {
        await slowly(delay);
        if (statusOf(row) === 'SHIPPED')
          throw new Error('已出库的订单不能取消');
      },
    },
    {
      id: 'priority',
      label: '设优先级',
      form: {
        priority: {
          label: '优先级',
          options: [
            { value: 'HIGH', label: '加急' },
            { value: 'NORMAL', label: '普通' },
          ],
        },
      },
      available: (_row, { input }) =>
        input?.priority === 'NORMAL' ? '已经是普通' : true,
      run: () => slowly(delay),
    },
    {
      id: 'note',
      label: '备注',
      on: ['row', 'detail'],
      form: {
        text: { label: '备注内容' },
        minutes: { label: '提醒（分钟后）', input: 'number', initial: 30 },
      },
      run: () => slowly(delay),
    },
  ]);
}

function Scene({
  instanceId = 'orders-pending',
  delay,
  opensIn,
}: {
  instanceId?: string;
  delay?: number;
  /** 「发货」 opens this many milliseconds after the story mounts. */
  opensIn?: number;
}) {
  const [declared] = useState(() =>
    orderActions(
      delay,
      opensIn === undefined ? undefined : Date.now() + opensIn,
    ),
  );
  return (
    <StoryEngine create={() => createStoryEngine()}>
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId="orders"
          instanceId={instanceId}
          {...HOST_LANGUAGE}
          record={{ actions: declared }}
        />
      )}
    </StoryEngine>
  );
}

const meta = {
  title: 'View Engine/组件状态/声明式操作',
  component: Scene,
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { layout: 'fullscreen' },
  decorators: [
    Story => (
      <AppShell service={{ fixture: '内存 ViewStore · 订单' }}>
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof Scene>;

export default meta;

type Story = StoryObj<typeof meta>;

const body = () => within(document.body);

/** A popup fades in: visible once its animation has started. */
const seen = (element: HTMLElement) =>
  waitFor(() => expect(element).toBeVisible());

/** The row of an order, by its checkbox. */
function rowOf(canvas: ReturnType<typeof within>, id: string) {
  return within(
    canvas
      .getByLabelText(zhCN['label.record.select'].replace('{key}', id))
      .closest('tr')!,
  );
}

async function loaded(canvas: ReturnType<typeof within>) {
  await waitFor(() =>
    expect(canvas.getAllByRole('button', { name: '发货' }).length).toBe(4),
  );
}

async function openMenu(canvas: ReturnType<typeof within>, id: string) {
  await userEvent.click(
    rowOf(canvas, id).getByRole('button', { name: `${id} 的操作` }),
  );
  const menu = await body().findByRole('menu');
  await waitFor(() => expect(menu).toBeVisible());
  return within(menu);
}

function line(canvasElement: HTMLElement, state: 'running' | 'settled') {
  return waitFor(() => {
    const found = canvasElement.querySelector<HTMLElement>(
      `[data-slot="bulk-status"][data-state="${state}"]`,
    );
    expect(found).not.toBeNull();
    return found!;
  });
}

/**
 * A row: the primary action in the row, the rest behind the order's menu.
 * An order that does not take it has it disabled, with why on the button
 * and atop the menu; a choice is its options, the one the order has off.
 */
export const RowPrimaryAndOverflow: Story = {
  name: '行：主操作与溢出菜单',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await loaded(canvas);
    const ship = rowOf(canvas, 'SO-1006').getByRole('button', {
      name: '发货',
    });
    await expect(ship).toBeDisabled();
    await expect(ship).toHaveAccessibleDescription(
      '金额不足 1000，先走人工审核',
    );
    const menu = await openMenu(canvas, 'SO-1006');
    await seen(menu.getByText('金额不足 1000，先走人工审核'));
    await seen(menu.getByRole('menuitem', { name: '取消订单' }));
    await seen(menu.getByText('优先级'));
    await expect(menu.getByRole('menuitem', { name: '普通' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(body().queryByRole('menu')).toBeNull());

    // An able order ships at a press, and the line above the rows says so.
    await userEvent.click(
      rowOf(canvas, 'SO-1003').getByRole('button', { name: '发货' }),
    );
    await expect(await line(canvasElement, 'settled')).toHaveTextContent(
      '发货 · 1 项已完成',
    );
  },
};

/**
 * A selection partly able: 「4 条里 3 条能发货」, the refused one named with
 * its reason, and one press to pick only the ones that can.
 */
export const BulkPartialAvailability: Story = {
  name: '多选：部分可用与只选能做的',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await loaded(canvas);
    await userEvent.click(
      canvas.getByLabelText(zhCN['label.record.select-all']),
    );
    await userEvent.click(
      await canvas.findByRole('button', { name: '发货 4 条' }),
    );
    const dialog = within(
      await body().findByRole('alertdialog', { name: '发出 4 张订单？' }),
    );
    await seen(dialog.getByText('4 条里 3 条能发货。'));
    await seen(dialog.getByText('金额不足 1000，先走人工审核（1 项）'));
    await seen(dialog.getByText('SO-1006'));
    await userEvent.click(
      dialog.getByRole('button', { name: '只选能做的 3 条' }),
    );
    await body().findByRole('alertdialog', { name: '发出 3 张订单？' });
    await userEvent.click(
      within(body().getByRole('alertdialog')).getByRole('button', {
        name: '发货',
      }),
    );
    await expect(await line(canvasElement, 'settled')).toHaveTextContent(
      '发货 · 3 项已完成',
    );
  },
};

/** A form: the fields the command needs, drawn by the condition editor's controls. */
export const FormInput: Story = {
  name: '表单输入',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await loaded(canvas);
    const menu = await openMenu(canvas, 'SO-1001');
    await userEvent.click(menu.getByRole('menuitem', { name: '备注' }));
    const dialog = within(
      await body().findByRole('alertdialog', { name: '备注' }),
    );
    const submit = dialog.getByRole('button', { name: '备注' });
    await expect(submit).toBeDisabled();
    await userEvent.type(dialog.getByLabelText('备注内容'), '先电话确认地址');
    await waitFor(() => expect(submit).toBeEnabled());
    await userEvent.click(submit);
    await expect(await line(canvasElement, 'settled')).toHaveTextContent(
      '备注 · 1 项已完成',
    );
  },
};

/** A dangerous action asks first, in the host's words, its answer red. */
export const DangerConfirm: Story = {
  name: '危险操作先确认',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await loaded(canvas);
    const menu = await openMenu(canvas, 'SO-1005');
    await userEvent.click(menu.getByRole('menuitem', { name: '取消订单' }));
    const dialog = within(
      await body().findByRole('alertdialog', { name: '取消 1 张订单？' }),
    );
    await seen(dialog.getByText('订单会退款并关闭，不能撤回。'));
    const answer = dialog.getByRole('button', { name: '取消订单' });
    await expect(answer).toHaveAttribute('data-tone', 'danger');
    // The keyboard stays in the question until it is answered.
    await waitFor(() =>
      expect(
        body().getByRole('alertdialog').contains(document.activeElement),
      ).toBe(true),
    );
    await userEvent.click(answer);
    await expect(await line(canvasElement, 'settled')).toHaveTextContent(
      '取消订单 · 1 项已完成',
    );
  },
};

/** An order's availability flips on its own when its rule says; the host keeps no timer. */
export const ChangesAtFlip: Story = {
  name: '到点自己翻转',
  args: { opensIn: 1500 },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await loaded(canvas);
    const ship = () =>
      rowOf(canvas, 'SO-1003').getByRole('button', { name: '发货' });
    await expect(ship()).toBeDisabled();
    await expect(ship()).toHaveAccessibleDescription('仓库尚未开门，稍后再发');
    await waitFor(() => expect(ship()).toBeEnabled(), { timeout: 4000 });
  },
};

/**
 * A run over many: how far it has come, a stop that starts nothing more,
 * and what it came to — the shipped order the carrier refused and the ones
 * never started left selected, with the reason.
 */
export const ProgressStopPartialFailure: Story = {
  name: '进度、停止与部分失败',
  args: { instanceId: systemInstanceId('orders', 'all'), delay: 900 },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const boxes = () =>
      canvas.getAllByLabelText(
        new RegExp(`^${zhCN['label.record.select'].replace('{key}', 'SO-')}`),
      );
    // Every order on the page, more than the four a run keeps in flight.
    await waitFor(() => expect(boxes().length).toBeGreaterThan(4));
    const count = boxes().length;
    const cancelAll = async () => {
      await userEvent.click(
        canvas.getByLabelText(zhCN['label.record.select-all']),
      );
      await userEvent.click(
        await canvas.findByRole('button', { name: '取消订单' }),
      );
      await userEvent.click(
        within(
          await body().findByRole('alertdialog', {
            name: `取消 ${count} 张订单？`,
          }),
        ).getByRole('button', { name: '取消订单' }),
      );
    };
    await cancelAll();
    const running = await line(canvasElement, 'running');
    await expect(running).toHaveTextContent(`取消订单 · 正在执行 0/${count}`);
    await userEvent.click(
      within(running).getByRole('button', { name: zhCN['label.bulk.stop'] }),
    );
    // What was under way lands; nothing more starts, and the rest stay picked.
    const settled = await line(canvasElement, 'settled');
    await expect(settled).toHaveTextContent(`${count - 4} 项未执行`);
    await expect(settled).toHaveTextContent(zhCN['label.bulk.left']);

    // Run whole, it fails where the service refuses — the shipped order —
    // with the service's reason, and leaves that order selected.
    await userEvent.click(
      within(settled).getByRole('button', { name: zhCN['label.bulk.dismiss'] }),
    );
    await cancelAll();
    const partial = await waitFor(
      () => {
        const found = canvasElement.querySelector<HTMLElement>(
          '[data-slot="bulk-status"][data-state="settled"]',
        );
        expect(found?.textContent).toContain(`${count - 1} 项完成，1 项失败`);
        return found!;
      },
      { timeout: 5000 },
    );
    await expect(partial).toHaveTextContent('已出库的订单不能取消（1 项）');
    await expect(partial).toHaveAttribute('data-tone', 'warning');
    await expect(
      canvas.getByLabelText(
        zhCN['label.record.select'].replace('{key}', 'SO-1004'),
      ),
    ).toBeChecked();
  },
};

/** The detail carries the order's actions — the form ones offered only there and in rows. */
export const DetailDrawer: Story = {
  name: '详情抽屉',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await loaded(canvas);
    const row = rowOf(canvas, 'SO-1003')
      .getByRole('button', { name: 'SO-1003 的操作' })
      .closest('tr')!;
    row.focus();
    await userEvent.keyboard('{Enter}');
    const detail = within(await body().findByRole('dialog'));
    await expect(
      await detail.findByRole('button', { name: '发货' }),
    ).toBeEnabled();
    await userEvent.click(
      detail.getByRole('button', { name: 'SO-1003 的操作' }),
    );
    const menu = within(await body().findByRole('menu'));
    await seen(menu.getByRole('menuitem', { name: '备注' }));
    await userEvent.keyboard('{Escape}');
    await userEvent.click(detail.getByRole('button', { name: '发货' }));
    await expect(await line(canvasElement, 'settled')).toHaveTextContent(
      '发货 · 1 项已完成',
    );
  },
};
