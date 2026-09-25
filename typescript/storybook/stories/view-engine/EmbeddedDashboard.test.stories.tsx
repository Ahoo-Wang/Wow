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
import { expect, screen, userEvent, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  CustomerDetail as DisplayCustomerDetail,
  DashboardWithAPanelOut as DisplayDashboardWithAPanelOut,
  WallScreenStatic as DisplayWallScreen,
} from './EmbeddedDashboard.stories.js';
import { findDataTable, readColumn } from './readTable.js';
import { chartsDrawn, drawnMarks } from './chartDom.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/组件状态/EmbeddedDashboard/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the host application around the page.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const label = (key: keyof typeof zhCN, params: Record<string, string> = {}) =>
  Object.entries(params).reduce<string>(
    (text, [name, value]) => text.replace(`{${name}}`, value),
    zhCN[key],
  );

async function filterBar(canvasElement: HTMLElement): Promise<HTMLElement> {
  return within(canvasElement).findByRole('region', {
    name: zhCN['label.filters.bar'],
  });
}

/** One panel's body, by the name the board calls the panel. */
function panelBody(canvasElement: HTMLElement, name: string) {
  return within(canvasElement).findByRole('group', { name });
}

/** The data table inside one panel, and its order numbers. */
async function orderNumbers(canvasElement: HTMLElement): Promise<string[]> {
  const body = await panelBody(canvasElement, '这个客户的订单');
  const table = await findDataTable(body);
  return readColumn(table, '订单号');
}

/**
 * The customer page, the interactive tier end to end (D22): the customer is
 * locked — read on the bar as who it is, with no control and no way to
 * clear it, beside the one button a phone's bar is (D26 Q38) — the placing
 * time is the reader's, set in the sheet that button opens, and moves the
 * panels, both
 * reach the host's address, and a group's follow-up and 在工作台中打开 go
 * through the host's route carrying the customer.
 */
export const CustomerDetail: Story = {
  ...DisplayCustomerDetail,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const bar = await filterBar(canvasElement);
    const customer = within(bar).getByRole('group', {
      name: label('label.embed.locked-name', { filter: '客户' }),
    });
    await expect(customer).toHaveTextContent('晨光食品');
    await expect(within(customer).queryByRole('combobox')).toBeNull();
    await expect(
      within(customer).getByRole('button', {
        name: zhCN['label.embed.locked'],
      }),
    ).toBeVisible();

    // This month, this customer: two orders, the larger first.
    await waitFor(async () =>
      expect(await orderNumbers(canvasElement)).toEqual(['SO-1003', 'SO-1001']),
    );

    // On a phone the bar is one button (D26 Q38): the customer is read
    // beside it, the reader's own filters are in the sheet it opens.
    const sheet = async () => {
      await userEvent.click(within(bar).getByRole('button', { name: /^筛选/ }));
      return screen.findByRole('dialog', { name: zhCN['label.filters.bar'] });
    };
    // Closed again, the panels behind it are the page's once more.
    const closeSheet = async () => {
      await userEvent.keyboard('{Escape}');
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    };
    // The placing time is the reader's: last month holds none of theirs.
    await userEvent.click(
      within(await sheet()).getByRole('combobox', {
        name: label('label.date.period-of', { field: '下单时间' }),
      }),
    );
    await userEvent.click(
      await screen.findByRole('option', {
        name: zhCN['label.relative.preset.lastMonth'],
      }),
    );
    await closeSheet();
    const orders = await panelBody(canvasElement, '这个客户的订单');
    await waitFor(() =>
      expect(orders).toHaveTextContent(zhCN['label.record.empty']),
    );
    // The host's address follows the reader's filter — and never holds the
    // locked customer, which would come back from it as the reader's.
    const address = canvasElement.querySelector('[data-host-address]')!;
    await waitFor(() =>
      expect(decodeURIComponent(address.textContent ?? '')).toContain(
        'lastMonth',
      ),
    );
    await expect(decodeURIComponent(address.textContent ?? '')).not.toContain(
      'c-03',
    );
    // However long the address, the page does not scroll sideways.
    const area = canvasElement.querySelector<HTMLElement>('.story-app-page')!;
    await expect(area.scrollWidth).toBeLessThanOrEqual(area.clientWidth);
    await expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(
      document.documentElement.clientWidth,
    );

    // 「清空」 clears what the reader holds and leaves the customer: every
    // order of theirs, of any time.
    await userEvent.click(
      within(await sheet()).getByRole('button', {
        name: zhCN['label.filters.clear'],
      }),
    );
    await closeSheet();
    await waitFor(async () =>
      expect(await orderNumbers(canvasElement)).toEqual(['SO-1003', 'SO-1001']),
    );
    await expect(customer).toHaveTextContent('晨光食品');

    // A group's follow-up opens in the workbench, through the host's route,
    // with the customer among its conditions.
    const byWarehouse = await panelBody(canvasElement, '按仓库金额');
    const row = await within(byWarehouse).findByRole('row', { name: /华北/ });
    await expect(row).toHaveAttribute('aria-haspopup', 'menu');
    await userEvent.click(row);
    await userEvent.click(
      await screen.findByRole('menuitem', {
        name: new RegExp(zhCN['label.drill.records']),
      }),
    );
    const route = canvasElement.querySelector('[data-host-route]')!;
    await waitFor(() => expect(route).toHaveTextContent('c-03'));
    await expect(route).toHaveTextContent('CN-NORTH');

    // And the view behind a panel, under the board's filters in its names.
    await userEvent.click(
      canvas.getByRole('button', {
        name: label('label.panel.menu', { title: '这个客户的订单' }),
      }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', { name: zhCN['label.panel.open'] }),
    );
    await waitFor(() => expect(route).toHaveTextContent('customer-orders'));
    await expect(route).toHaveTextContent('c-03');
    // A long route wraps too.
    await expect(area.scrollWidth).toBeLessThanOrEqual(area.clientWidth);
  },
};

/**
 * The customer page switched exports on (`withExport`): the order list's
 * 「⋯」 offers 「导出数据…」, whose window is the workbench's — under the
 * customer the page locks and the time the reader picked, named after the
 * panel — and the keyboard is back on the 「⋯」 as it closes. The chart
 * panel offers the same item over its groups (D25 Q28), the same window
 * under the same customer. Nothing is exported (test/embeddedDashboard.test.tsx
 * and test/dashboardPanelMenu.test.tsx hold the file).
 */
export const CustomerOrdersExport: Story = {
  ...DisplayCustomerDetail,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await findDataTable(await panelBody(canvasElement, '这个客户的订单'));
    const trigger = canvas.getByRole('button', {
      name: label('label.panel.menu', { title: '这个客户的订单' }),
    });
    await userEvent.click(trigger);
    await userEvent.click(
      await screen.findByRole('menuitem', { name: zhCN['label.panel.export'] }),
    );
    const dialog = await screen.findByRole('dialog', {
      name: zhCN['label.export.title'],
    });
    await expect(dialog.textContent).toContain('晨光食品');
    await expect(dialog.textContent).toMatch(
      /文件：这个客户的订单-\d{4}-\d{2}-\d{2}\.csv/,
    );
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(trigger).toHaveFocus());

    await userEvent.click(
      canvas.getByRole('button', {
        name: label('label.panel.menu', { title: '按仓库金额' }),
      }),
    );
    await userEvent.click(
      within(await screen.findByRole('menu')).getByRole('menuitem', {
        name: zhCN['label.panel.export'],
      }),
    );
    const groups = await screen.findByRole('dialog', {
      name: zhCN['label.export.title'],
    });
    await expect(within(groups).queryByRole('radio')).toBeNull();
    await expect(groups.textContent).toContain('晨光食品');
    await expect(groups.textContent).toMatch(/\d+ 组/);
    await expect(groups.textContent).toMatch(
      /文件：按仓库金额-\d{4}-\d{2}-\d{2}\.csv/,
    );
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  },
};

/**
 * The wall screen, the static tier end to end (D22, D36): the board fills
 * the page it is on, titled, under the warehouse the page locks it to;
 * nothing on it answers a press, offers a menu, builds, clears or fills the
 * screen, and every filter reads as what it holds.
 */
export const WallScreenStatic: Story = {
  ...DisplayWallScreen,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      await canvas.findByRole('heading', { level: 2, name: '出库概览' }),
    ).toBeVisible();
    const bar = await filterBar(canvasElement);
    await expect(
      within(bar).getByRole('group', {
        name: label('label.embed.locked-name', { filter: '仓库' }),
      }),
    ).toHaveTextContent('华东');
    await expect(
      within(bar).queryByRole('button', { name: zhCN['label.filters.clear'] }),
    ).toBeNull();

    // Only the east warehouse's pending order, and one bar.
    const pending = await panelBody(canvasElement, '待出库明细');
    const table = await findDataTable(pending);
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(['SO-1001']),
    );
    const chart = await panelBody(canvasElement, '按仓库汇总');
    await chartsDrawn(chart);
    await waitFor(() => expect(drawnMarks(chart)).toHaveLength(1));

    // Read, and nothing else.
    await expect(
      canvasElement.querySelector('[data-slot="panel-menu"]'),
    ).toBeNull();
    await expect(
      canvasElement.querySelector('[data-pickable], [aria-haspopup="menu"]'),
    ).toBeNull();
    await expect(
      canvas.queryByRole('button', { name: zhCN['label.dashboard.edit'] }),
    ).toBeNull();

    // It fills the wall it was given, to the pixel.
    const wall = canvasElement.querySelector<HTMLElement>('[data-wall]')!;
    const surface = wall.querySelector<HTMLElement>('.host-embed')!;
    await expect(surface).toHaveAttribute('data-embed-size', 'fill');
    await expect(
      Math.abs(
        surface.getBoundingClientRect().height -
          wall.getBoundingClientRect().height,
      ),
    ).toBeLessThan(1);
  },
};

/**
 * One panel of an embedded board is out: the grid still draws, the other
 * panels run, and the one that is out says why in its own frame (R3).
 */
export const DashboardWithAPanelOut: Story = {
  ...DisplayDashboardWithAPanelOut,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await findDataTable(await panelBody(canvasElement, '待出库明细'));
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(4));
    await expect(
      canvas.getByRole('link', { name: /出库异常处理/ }),
    ).toBeVisible();
    const out = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="panel-unavailable"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(out).toHaveTextContent(zhCN['label.panel.out.missing']);
    await expect(out).toHaveTextContent(zhCN['label.panel.way-out.share']);
    await expect(
      canvasElement.querySelector(
        '[data-slot="status-strip"][data-tone="error"]',
      ),
    ).toBeNull();
  },
};
