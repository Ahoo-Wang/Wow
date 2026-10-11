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
import displayMeta, {
  KanbanByStage as DisplayByStage,
  RequirementStates as DisplayRequirements,
} from './RecordKanban.stories.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/布局/看板视图/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out: a file's own description would otherwise replace the
  // display meta's parameters, and the host application with them.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

function cards(lane: HTMLElement): string[] {
  return [...lane.querySelectorAll('[data-slot="card-title-text"]')].map(
    node => node.textContent?.trim() ?? '',
  );
}

/** Each stage a lane, counted whole; more cards, one lane at a time. */
export const LanesLoadMore: Story = {
  ...DisplayByStage,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const lead = await canvas.findByRole('region', { name: '线索，5 条' });
    // Largest first, three at a time.
    await expect(cards(lead)).toEqual([
      '财务共享中心',
      '会员中台',
      '华东区 ERP 升级',
    ]);
    await expect(
      await canvas.findByRole('region', { name: '赢单，2 条' }),
    ).toBeInTheDocument();
    await userEvent.click(
      within(lead).getByRole('button', { name: '加载更多（还有 2 条）' }),
    );
    await waitFor(() => expect(cards(lead)).toHaveLength(5));
    await expect(
      within(lead).queryByRole('button', { name: /^加载更多/ }),
    ).toBeNull();
    // A lane's cards stand one under another, and the lanes side by side.
    const board = canvasElement.querySelector<HTMLElement>(
      '[data-slot="record-kanban"]',
    )!;
    const lanes = [
      ...board.querySelectorAll<HTMLElement>('[data-slot="kanban-lane"]'),
    ];
    await expect(lanes[1]!.getBoundingClientRect().left).toBeGreaterThan(
      lanes[0]!.getBoundingClientRect().right - 1,
    );
    // The board takes what is left of the result, and every lane its height:
    // a lane scrolls its own cards rather than the board growing to the
    // tallest one.
    const heights = new Set(
      lanes.map(lane => Math.round(lane.getBoundingClientRect().height)),
    );
    await expect(heights.size).toBe(1);
    const port = lanes[0]!.querySelector<HTMLElement>(
      '[data-slot="kanban-lane-cards"]',
    )!;
    await expect(getComputedStyle(port).overflowY).toBe('auto');
  },
};

/** A card's own action moves it on a stage, and the lanes follow. */
export const RecordAction: Story = {
  ...DisplayByStage,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const proposal = await canvas.findByRole('region', {
      name: '方案报价，1 条',
    });
    await userEvent.click(
      within(proposal).getByRole('button', { name: '推进阶段' }),
    );
    const negotiation = await canvas.findByRole('region', {
      name: '商务谈判，2 条',
    });
    await expect(cards(negotiation)).toContain('供应链协同');
    await expect(
      await canvas.findByRole('region', { name: '方案报价，0 条' }),
    ).toBeInTheDocument();
  },
};

/** The record in a state the options no longer list sits in「其他」. */
export const OtherLane: Story = {
  ...DisplayRequirements,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const other = await canvas.findByRole('region', {
      name: '其他，已显示 1 条',
    });
    await expect(cards(other)).toEqual(['数据迁移脚本']);
    // Folding a lane away asks again without it.
    await userEvent.click(canvas.getByRole('button', { name: '看板视图设置' }));
    await userEvent.click(
      await within(document.body).findByRole('checkbox', { name: '已拒绝' }),
    );
    await waitFor(() =>
      expect(canvas.queryByRole('region', { name: /^已拒绝/ })).toBeNull(),
    );
  },
};
