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
  type Issue,
  type RecordActions,
  type RecordRow,
  type ViewInstance,
} from '@ahoo-wang/wow-view-engine';
import { DataWorkbench, zhCN } from '@ahoo-wang/wow-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import {
  createStoryEngine,
  HOST_LANGUAGE,
  recordConfig,
  savedViews,
} from './fixtures.js';
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

/**
 * Held off where the keyboard still finds it: `aria-disabled`, never the
 * native `disabled` that hands the focus to the page (A11Y-1, A11Y-15).
 */
async function held(element: HTMLElement, yes = true) {
  await expect(element.hasAttribute('disabled')).toBe(false);
  if (yes) await expect(element).toHaveAttribute('aria-disabled', 'true');
  else await expect(element).not.toHaveAttribute('aria-disabled', 'true');
}

/** What the surface's live region says last. */
function lastSaid(canvasElement: HTMLElement) {
  return (
    canvasElement.querySelector('[data-slot="record-announcement"]')
      ?.textContent ?? ''
  );
}

/**
 * The outcome is what the live region says last, in the surface's language,
 * and it stays said: nothing that lands after the run (the refresh, its
 * count) talks over it (A11Y-2).
 */
async function saysAndKeeps(canvasElement: HTMLElement, said: RegExp) {
  await waitFor(() => expect(lastSaid(canvasElement)).toMatch(said));
  const region = canvasElement.querySelector(
    '[data-slot="record-announcement"]',
  )!;
  await expect(region.closest('[lang]')?.getAttribute('lang')).toMatch(/^zh/);
  await slowly(1200);
  await expect(lastSaid(canvasElement)).toMatch(said);
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
    await held(ship);
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

    // An able order ships at Enter, and the line under the rows names it;
    // the keyboard stays on the button through the run and the refresh.
    const able = rowOf(canvas, 'SO-1003').getByRole('button', {
      name: '发货',
    });
    able.focus();
    await userEvent.keyboard('{Enter}');
    await expect(await line(canvasElement, 'settled')).toHaveTextContent(
      '发货 · SO-1003 已完成',
    );
    // Said in the host's words, and the refresh's count after it rather
    // than over it (A11Y-2).
    await saysAndKeeps(canvasElement, /^发货 · SO-1003 已完成；/);
    await waitFor(() => expect(document.activeElement).toBe(able));
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
    // How many of the four take it, before the press.
    await userEvent.click(
      await canvas.findByRole('button', { name: '发货 3/4 条' }),
    );
    const dialog = within(
      await body().findByRole('dialog', { name: '发出 4 张订单？' }),
    );
    await seen(dialog.getByText('4 条里 3 条能发货。'));
    await seen(dialog.getByText('金额不足 1000，先走人工审核（1 项）'));
    await seen(dialog.getByText('SO-1006'));
    await userEvent.click(
      dialog.getByRole('button', { name: '只选能做的 3 条' }),
    );
    await body().findByRole('dialog', { name: '发出 3 张订单？' });
    await userEvent.click(
      within(body().getByRole('dialog')).getByRole('button', {
        name: '发货 3 条',
      }),
    );
    const settled = await line(canvasElement, 'settled');
    await expect(settled).toHaveTextContent('发货 · 3 项已完成');
    await saysAndKeeps(canvasElement, /^发货 · 3 项已完成/);
    // The done ones are let go, so the bar the press came from is gone:
    // the keyboard lands on the line that says how it went, not the page.
    await waitFor(() =>
      expect(document.activeElement).toBe(
        within(settled).getByRole('button', {
          name: zhCN['label.bulk.dismiss'],
        }),
      ),
    );
  },
};

/**
 * The command lands before the window has finished closing: the keyboard
 * still ends on the line, not the page.
 *
 * The window hands the keyboard back when its closing animation has run.
 * On a slow frame — Safari on the nightly's runner — the command had
 * landed by then, the selection's bar had gone with the selection, and the
 * keyboard fell to `<body>`. Here the closing is held at a second, which
 * puts any browser in that order.
 */
export const BulkLandsBeforeTheWindowCloses: Story = {
  name: '多选：命令先完成、窗口后关上',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await loaded(canvas);
    await userEvent.click(
      canvas.getByLabelText(zhCN['label.record.select-all']),
    );
    await userEvent.click(
      await canvas.findByRole('button', { name: '发货 3/4 条' }),
    );
    const dialog = within(
      await body().findByRole('dialog', { name: '发出 4 张订单？' }),
    );
    await userEvent.click(
      dialog.getByRole('button', { name: '只选能做的 3 条' }),
    );
    const shipping = await body().findByRole('dialog', {
      name: '发出 3 张订单？',
    });
    const slow = document.createElement('style');
    slow.textContent =
      '[data-slot="action-dialog"][data-closed] { animation-duration: 1s !important; }';
    document.head.append(slow);
    try {
      await userEvent.click(
        within(shipping).getByRole('button', { name: '发货 3 条' }),
      );
      const settled = await line(canvasElement, 'settled');
      // Landed while the window is still on its way out.
      await expect(shipping).toHaveAttribute('data-closed');
      await waitFor(() => expect(shipping.isConnected).toBe(false), {
        timeout: 5_000,
      });
      await waitFor(() =>
        expect(document.activeElement).toBe(
          within(settled).getByRole('button', {
            name: zhCN['label.bulk.dismiss'],
          }),
        ),
      );
    } finally {
      slow.remove();
    }
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
    // A form is a dialog, not an alert, and names the order.
    const dialog = within(
      await body().findByRole('dialog', { name: '备注：SO-1001' }),
    );
    const note = dialog.getByLabelText('备注内容');
    await expect(note).toHaveAttribute('aria-required', 'true');
    await expect(note).toHaveAccessibleDescription('必填');
    // The answer stays pressable: pressed blank, it marks the field and
    // takes the keyboard there (A11Y-9).
    const submit = dialog.getByRole('button', { name: '备注' });
    await expect(submit).toBeEnabled();
    await userEvent.click(submit);
    await waitFor(() => expect(document.activeElement).toBe(note));
    await expect(note).toHaveAttribute('aria-invalid', 'true');
    await userEvent.type(note, '先电话确认地址');
    await userEvent.click(submit);
    await expect(await line(canvasElement, 'settled')).toHaveTextContent(
      '备注 · SO-1001 已完成',
    );
    await saysAndKeeps(canvasElement, /^备注 · SO-1001 已完成/);
    // Answered, the keyboard is back on the menu the form was opened from.
    await waitFor(() =>
      expect(document.activeElement).toBe(
        rowOf(canvas, 'SO-1001').getByRole('button', {
          name: 'SO-1001 的操作',
        }),
      ),
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
    // The host's words, and the one order they are for named beside them.
    await seen(dialog.getByText(/^订单会退款并关闭，不能撤回。/));
    await expect(body().getByRole('alertdialog')).toHaveAccessibleDescription(
      '订单会退款并关闭，不能撤回。 记录 SO-1005',
    );
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
      '取消订单 · SO-1005 已完成',
    );
    await saysAndKeeps(canvasElement, /^取消订单 · SO-1005 已完成/);
    await waitFor(() =>
      expect(document.activeElement).toBe(
        rowOf(canvas, 'SO-1005').getByRole('button', {
          name: 'SO-1005 的操作',
        }),
      ),
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
    await held(ship());
    await expect(ship()).toHaveAccessibleDescription('仓库尚未开门，稍后再发');
    await waitFor(() => held(ship(), false), { timeout: 4000 });
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
        ).getByRole('button', { name: `取消订单 ${count} 条` }),
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
        expect(found?.textContent).toContain(
          `${count - 1} 项已完成 · 1 项失败`,
        );
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
      '发货 · SO-1003 已完成',
    );
  },
};

/**
 * 「待发货」hides the status column, and 「发货」 reads the status: the rows
 * bring only what the view needs (`recordProjection`), so the rule reads
 * nothing there — a cancelled order of 1,000 or more looks shippable. In a
 * development build the engine tells `onIssue` once, naming the field, the
 * action and `record.rowFields`; the list under the workbench shows what it
 * heard. Any other build makes no such check and the list stays empty: the
 * rules read the rows as they are (`runtime/actionReads.ts`).
 */
const TO_SHIP: ViewInstance = {
  id: 'orders-to-ship',
  definitionId: 'orders',
  title: '待发货（不显示状态）',
  scope: 'shared',
  revision: '1',
  config: recordConfig({
    table: {
      columns: [
        { field: 'id', pinned: true },
        { field: 'warehouse' },
        { field: 'amount' },
      ],
    },
    card: { title: 'id', fields: ['warehouse', 'amount'] },
  }),
};

// Replaced by the bundler as the engine's own check is (`inDevelopment`):
// `storybook dev` is a development build, the story tests and a built
// Storybook are not.
declare const process: { env: { NODE_ENV?: string } };

function UnfetchedScene() {
  const [declared] = useState(() => orderActions());
  const [heard, setHeard] = useState<Issue[]>([]);
  return (
    <>
      <StoryEngine
        create={() =>
          createStoryEngine({
            instances: [...savedViews, TO_SHIP],
            onIssue: found => setHeard(list => [...list, found]),
          })
        }
      >
        {engine => (
          <DataWorkbench
            engine={engine}
            definitionId="orders"
            instanceId={TO_SHIP.id}
            {...HOST_LANGUAGE}
            record={{ actions: declared }}
          />
        )}
      </StoryEngine>
      <section aria-label="onIssue" className="story-findings">
        <h2>onIssue（开发构建才有这一条）</h2>
        <ul>
          {heard
            .filter(found => found.code === 'record.action.unfetched')
            .map(found => (
              <li key={`${found.params?.view}-${found.params?.field}`}>
                <code>{found.code}</code>{' '}
                {zhCN['record.action.unfetched']
                  .replace('{action}', String(found.params?.action))
                  .replace('{field}', String(found.params?.field))}
              </li>
            ))}
        </ul>
      </section>
    </>
  );
}

export const ActionReadsUnfetchedField: Story = {
  name: '条件读了没取回的字段：开发环境的提示',
  render: () => <UnfetchedScene />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() =>
      expect(
        rowOf(canvas, 'SO-1006').getByRole('button', { name: '发货' }),
      ).toBeTruthy(),
    );
    const findings = within(canvas.getByRole('region', { name: 'onIssue' }));
    if (process.env.NODE_ENV === 'development') {
      // Once for the view and the field, however many rows and renders.
      await waitFor(() =>
        expect(findings.getAllByRole('listitem')).toHaveLength(1),
      );
      await expect(findings.getByRole('listitem')).toHaveTextContent(
        '操作「ship」的条件读了 status',
      );
      await expect(findings.getByRole('listitem')).toHaveTextContent(
        'record.rowFields',
      );
      await slowly(300);
      await expect(findings.getAllByRole('listitem')).toHaveLength(1);
    } else {
      // A production build: no proxy, nothing told, the rules as they were.
      await slowly(300);
      await expect(findings.queryAllByRole('listitem')).toHaveLength(0);
    }
    // Either way the rule decides on the row it was handed: the status is
    // not on it, so an order is judged by its amount alone.
    await held(rowOf(canvas, 'SO-1006').getByRole('button', { name: '发货' }));
  },
};
