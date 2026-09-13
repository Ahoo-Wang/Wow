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
import { keyboardOrder } from './listOrder.play.js';
import { expect, userEvent, within, waitFor } from 'storybook/test';
import displayMeta, {
  Cards,
  Configuration,
  CustomContent as DisplayCustomContent,
  Persisted as DisplayPersisted,
  NarrowDark as DisplayNarrowDark,
} from './RecordCardList.stories.js';
const meta = {
  ...displayMeta,
  id: 'view-engine-专项场景-卡片模式-回归',
  title: 'View Engine/数据视图/卡片模式/回归',
  tags: ['!dev', '!autodocs', 'test'],
};
export default meta;
type Story = StoryObj<typeof displayMeta>;

async function changeLayout(
  canvasElement: HTMLElement,
  current: string,
  target: string,
) {
  const canvas = within(canvasElement);
  const page = within(canvasElement.ownerDocument.body);
  const trigger = within(
    canvas.getByRole('group', { name: '全局工具栏' }),
  ).getByRole('button', { name: `展示方式：${current}` });
  await userEvent.click(trigger);
  await userEvent.click(
    await page.findByRole('menuitemradio', { name: target, exact: true }),
  );
  // The modal menu releases its inert scope and restores focus asynchronously.
  // A new layout in the DOM is not enough to begin the next interaction.
  await waitFor(() => {
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(trigger).toHaveAttribute('aria-label', `展示方式：${target}`);
    expect(page.queryByRole('menu')).not.toBeInTheDocument();
    expect(canvasElement.closest('[data-base-ui-inert]')).toBeNull();
    expect(trigger).toHaveFocus();
  });
}

export const RoundTrip: Story = {
  ...Configuration,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      await canvas.findByRole('list', { name: '记录卡片' }),
    ).toBeVisible();
    await userEvent.click(
      await canvas.findByRole('button', { name: '选择记录 SO-202609-1001' }),
    );
    await changeLayout(canvasElement, '卡片', '表格');
    await expect(canvas.getByRole('table')).toBeVisible();
    await expect(canvas.getByText('已选本页 1 条')).toBeVisible();
    await changeLayout(canvasElement, '表格', '卡片');
    await expect(
      await canvas.findByRole('button', { name: '选择记录 SO-202609-1001' }),
    ).toHaveAttribute('aria-pressed', 'true');
    await userEvent.click(canvas.getByRole('button', { name: '卡片设置' }));
    const page = within(canvasElement.ownerDocument.body);
    await userEvent.click(
      await page.findByRole('button', { name: '移除摘要 2' }),
    );
    await userEvent.click(page.getByRole('button', { name: '应用设置' }));
    await expect(
      within(canvas.getByRole('list', { name: '记录卡片' })).queryByText(
        '状态',
      ),
    ).not.toBeInTheDocument();
    await changeLayout(canvasElement, '卡片', '表格');
    await expect(
      within(canvas.getByRole('table')).getByText('状态', { exact: true }),
    ).toBeVisible();
    await changeLayout(canvasElement, '表格', '卡片');
    await expect(
      within(canvas.getByRole('list', { name: '记录卡片' })).queryByText(
        '状态',
      ),
    ).not.toBeInTheDocument();
  },
};
export const NarrowDark: Story = {
  ...DisplayNarrowDark,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('list', { name: '记录卡片' });
    const page = within(canvasElement.ownerDocument.body);
    await changeLayout(canvasElement, '卡片', '表格');
    await canvas.findByRole('table');
    await changeLayout(canvasElement, '表格', '卡片');
    const returnedList = await canvas.findByRole('list', { name: '记录卡片' });
    await expect(returnedList).toBeVisible();
    await expect(returnedList.scrollWidth).toBeLessThanOrEqual(
      returnedList.clientWidth + 1,
    );
    await userEvent.click(canvas.getByRole('button', { name: '卡片设置' }));
    await page.findByRole('button', { name: '应用设置' });
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(canvas.getByRole('button', { name: '卡片设置' })).toHaveFocus(),
    );
  },
};

export const CustomContent: Story = {
  ...DisplayCustomContent,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('list', { name: '记录卡片' });

    await userEvent.click(
      await canvas.findByRole('button', { name: '选择记录 SKU-001' }),
    );
    await expect(canvas.getByText(/已选商品/)).toBeVisible();
    await expect(
      canvas.queryByRole('button', { name: '卡片设置' }),
    ).not.toBeInTheDocument();
    const global = within(canvas.getByRole('group', { name: '全局工具栏' }));
    await expect(
      global.getByRole('button', { name: '展示方式：卡片' }),
    ).toBeVisible();
  },
};
export const Persisted: Story = {
  ...DisplayPersisted,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('list', { name: '记录卡片' });
    await userEvent.click(canvas.getByRole('button', { name: '卡片设置' }));
    const page = within(canvasElement.ownerDocument.body);
    await userEvent.click(
      await page.findByRole('button', { name: '移除摘要 2' }),
    );
    await userEvent.click(page.getByRole('button', { name: '应用设置' }));
    await userEvent.click(
      canvas.getByRole('button', { name: '保存', exact: true }),
    );
    await expect(
      await canvas.findByRole('status', { name: '保存状态' }),
    ).toHaveTextContent('视图已保存');
  },
};

export const BusinessActions: Story = {
  ...Cards,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const cover = (await canvas.findByRole('img', {
      name: '苔绿通勤托特包',
      exact: true,
    })) as HTMLImageElement;
    await waitFor(() => expect(cover.naturalWidth).toBeGreaterThan(0));
    await userEvent.click(
      await canvas.findByRole('button', { name: '查看商品 SKU-001' }),
    );
    const page = within(canvasElement.ownerDocument.body);
    await expect(
      await page.findByRole('dialog', { name: '苔绿通勤托特包' }),
    ).toBeVisible();
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(
        canvas.getByRole('button', { name: '查看商品 SKU-001' }),
      ).toHaveFocus(),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: '收藏商品 SKU-001' }),
    );
    await waitFor(() =>
      expect(
        canvas.getByRole('button', { name: '收藏商品 SKU-001' }),
      ).toHaveAttribute('aria-pressed', 'true'),
    );
    await expect(
      canvas.getByRole('button', { name: '收藏商品 SKU-001' }),
    ).toHaveFocus();
    await userEvent.click(
      canvas.getByRole('button', { name: '下架商品 SKU-001' }),
    );
    await expect(
      await canvas.findByRole('button', { name: '上架商品 SKU-001' }),
    ).toBeVisible();
    await userEvent.click(
      await canvas.findByRole('button', { name: '选择记录 SKU-001' }),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: '批量上架', exact: true }),
    );
    await expect(
      await canvas.findByRole('button', { name: '下架商品 SKU-001' }),
    ).toBeVisible();
    await changeLayout(canvasElement, '卡片', '表格');
    await expect(canvas.getByRole('table')).toBeVisible();
  },
};

export const SortRules: Story = {
  ...Cards,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(
      await canvas.findByRole('button', { name: '排序：默认' }),
    );
    const page = within(canvasElement.ownerDocument.body);
    await expect(await page.findByText('尚未设置排序')).toBeVisible();
    for (const field of ['售价', '库存']) {
      await userEvent.click(page.getByRole('combobox', { name: '添加排序' }));
      await userEvent.click(await page.findByRole('option', { name: field }));
      await page.findByRole('button', { name: `${field}排序：升序` });
    }
    const handle = page.getByRole('button', { name: '拖动调整售价排序优先级' });
    handle.focus();
    await keyboardOrder(handle, 'ArrowDown');
    await expect(
      canvas.getByRole('button', { name: '排序：库存 ↑、售价 ↑' }),
    ).toBeVisible();
    await expect(handle).toHaveFocus();
    const direction = page.getByRole('button', { name: '售价排序：升序' });
    direction.focus();
    await userEvent.keyboard('{Enter}');
    await expect(
      page.getByRole('button', { name: '售价排序：降序' }),
    ).toHaveFocus();
    await expect(
      canvas.getByRole('button', { name: '排序：库存 ↑、售价 ↓' }),
    ).toBeVisible();
    await userEvent.click(page.getByRole('button', { name: '移除库存排序' }));
    await expect(
      canvas.getByRole('button', { name: '排序：售价 ↓' }),
    ).toBeVisible();
    await userEvent.click(page.getByRole('button', { name: '清除全部' }));
    await expect(page.getByText('尚未设置排序')).toBeVisible();
  },
};
