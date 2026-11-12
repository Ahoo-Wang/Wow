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
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { outage, tableSettingsStore } from './fixtures.js';
import { say } from './recordWorkbenchTest.js';

/**
 * 保存写下去的是跑过的视图（D94，走查 F2）。条件改了还没按「应用」时，保存按钮写
 * 「应用并保存」：按下去先应用、等结果回来再写；源不答应，就不保存，草稿留着。
 * 原先保存写的是草稿本身，一个视图于是能带着作者从没见过结果的条件存下去。
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/组件状态/记录工作台/保存先应用/回归',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** 「我盯的大额单」: a personal view, saved on the press, 金额 > 5000. */
const MINE = 'orders-mine';

const button = (canvasElement: HTMLElement, key: keyof typeof zhCN) =>
  within(canvasElement).queryByRole('button', { name: zhCN[key] });

/** Takes the view's one condition off, and leaves it waiting for Apply. */
async function editWithoutApplying(canvasElement: HTMLElement) {
  const canvas = within(canvasElement);
  // The view has opened and answered (no order here is over 5000, so what
  // it answers with is the empty result, not a table).
  await canvas.findByRole('button', {
    name: new RegExp(`^${zhCN['label.filter.panel']}`),
  });
  await waitFor(() =>
    expect(canvasElement.querySelector('[aria-busy="true"]')).toBeNull(),
  );
  // Nothing waits yet: the button says what it has always said.
  await expect(button(canvasElement, 'label.save.apply-and-save')).toBeNull();
  await userEvent.click(
    canvas.getByRole('button', {
      name: new RegExp(`^${zhCN['label.filter.panel']}`),
    }),
  );
  await userEvent.click(
    await canvas.findByRole('button', {
      name: say('label.filter.remove-of', { field: '金额' }),
    }),
  );
  return waitFor(() => {
    const found = button(canvasElement, 'label.save.apply-and-save');
    expect(found).not.toBeNull();
    return found!;
  });
}

const storedFilter = async () =>
  ((await tableSettingsStore.current!.get(MINE)).config as { filter?: unknown })
    .filter;

export const SaveSaysItAppliesAndDoes: Story = {
  ...DisplayWithData,
  args: { ...DisplayWithData.args, instanceId: MINE, keepStore: true },
  play: async ({ canvasElement }) => {
    const before = await storedFilter();
    const save = await editWithoutApplying(canvasElement);
    await userEvent.click(save);
    // Written, and what was written is the draft that ran.
    await waitFor(async () => expect(await storedFilter()).not.toEqual(before));
    // Nothing waits any more, so the button is plain 「保存」 again.
    await waitFor(() =>
      expect(button(canvasElement, 'label.save.apply-and-save')).toBeNull(),
    );
  },
};

export const ARefusedApplyIsNotSaved: Story = {
  ...DisplayWithData,
  args: {
    ...DisplayWithData.args,
    behaviour: 'outage',
    instanceId: MINE,
    keepStore: true,
  },
  play: async ({ canvasElement }) => {
    outage.down = false;
    try {
      const before = await storedFilter();
      const save = await editWithoutApplying(canvasElement);
      outage.down = true;
      await userEvent.click(save);
      // Said beside the button, and nothing was written.
      await within(canvasElement).findByText(zhCN['view.save.not-applied']);
      await expect(await storedFilter()).toEqual(before);
    } finally {
      outage.down = false;
    }
  },
};
