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

import type { ComponentType } from 'react';
import type { StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  BuildASearch as DisplayBuild,
} from './DashboardSearchFilter.stories.js';

/**
 * 面板的「⋯」在读板时不占眼（D94，2026-11 审计 F6）：每个数据面板都带着它（「刷新
 * 这个面板」总在），静止时画出来就是每张卡上一个与这张卡无关的记号。能悬停的屏幕
 * 上它静止时不可见，指针到了面板上、键盘到了它身上、它的菜单开着时显出来；它一直
 * 是一个 Tab 停靠点，读屏一直读得到。没有悬停的屏幕上一直显示；搭板时也一直显示，
 * 那时它是动一个面板的入口。
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/能力/面板菜单/回归',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const DESK = (Story: ComponentType) => (
  <div style={{ width: 1280 }}>
    <Story />
  </div>
);

const wrappers = (canvasElement: HTMLElement) => [
  ...canvasElement.querySelectorAll<HTMLElement>(
    '[data-slot="dashboard-panel"] [data-slot="card-title"] > span:has(> button[aria-haspopup])',
  ),
];

/** Where the wrapper's opacity lands, its easing run to the end. */
const shown = (wrapper: HTMLElement) => {
  for (const easing of wrapper.getAnimations()) easing.finish();
  return getComputedStyle(wrapper).opacity;
};

export const TheMenuRestsWhileTheBoardIsRead: Story = {
  ...DisplayBuild,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('heading', { level: 3, name: '按仓库汇总' });
    await waitFor(() =>
      expect(wrappers(canvasElement).length).toBeGreaterThan(1),
    );
    // The rule is for a screen with a pointer that hovers; this one has it.
    await expect(matchMedia('(hover: hover)').matches).toBe(true);
    for (const wrapper of wrappers(canvasElement)) {
      await expect(wrapper).toHaveAttribute('data-rests');
      await expect(shown(wrapper)).toBe('0');
      // Out of sight, and still a Tab stop with its name.
      const button = wrapper.querySelector('button')!;
      await expect(button.tabIndex).toBe(0);
      await expect(button).toHaveAccessibleName();
    }

    // Under the keyboard it shows, and the others stay at rest.
    const [first, second] = wrappers(canvasElement);
    first.querySelector('button')!.focus();
    await expect(shown(first)).toBe('1');
    await expect(shown(second)).toBe('0');

    // Open, it stays shown, wherever focus went.
    await userEvent.click(first.querySelector('button')!);
    await waitFor(() =>
      expect(first.querySelector('button')).toHaveAttribute(
        'aria-expanded',
        'true',
      ),
    );
    await expect(shown(first)).toBe('1');
    await userEvent.keyboard('{Escape}');
  },
};

export const TheMenuIsShownWhileTheBoardIsBuilt: Story = {
  ...DisplayBuild,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('heading', { level: 3, name: '按仓库汇总' });
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.edit'] }),
    );
    await waitFor(() =>
      expect(wrappers(canvasElement).length).toBeGreaterThan(1),
    );
    for (const wrapper of wrappers(canvasElement)) {
      await expect(wrapper).not.toHaveAttribute('data-rests');
      await expect(shown(wrapper)).toBe('1');
    }
  },
};
