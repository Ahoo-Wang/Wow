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
import { formatMessage, zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  ManageViews as DisplayManageViews,
} from './RecordWorkbench.stories.js';
import { tableSettingsStore } from './fixtures.js';
import { holdWrite, nextFrame, openManager } from './recordWorkbenchTest.js';

/**
 * A row action of the view manager that leaves the row where it is keeps the
 * keyboard on the button it was pressed with, for as long as the store takes
 * to answer (#5820).
 *
 * While a write runs, every action of the row is out of reach. Drawn as the
 * native `disabled`, the pressed button lost the focus to `<body>` as soon
 * as a frame was drawn before the answer, and nothing gave it back: the row
 * does not move, so no hand-off follows the write as one follows a change of
 * audience. The in-memory store answered within the frame and hid it. So
 * each play here holds the store's write open across two drawn frames
 * before it lets it land.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/组件状态/记录工作台/管理器焦点/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: see `RecordWorkbenchViews`.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const say = (key: keyof typeof zhCN, params: Record<string, string>) =>
  formatMessage(zhCN, key, params);

/** Held, and still where the keyboard is, over two drawn frames. */
async function heldWithTheKeyboard(button: HTMLElement): Promise<void> {
  await nextFrame();
  await nextFrame();
  await expect(document.activeElement).toBe(button);
  await expect(button).toHaveAttribute('aria-disabled', 'true');
}

/** Publishing copies the view, so the row stays — and so does the keyboard. */
export const PublishKeepsTheKeyboard: Story = {
  ...DisplayManageViews,
  args: { ...DisplayManageViews.args, systemViews: 'admin' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const row = await openManager(canvas, '我盯的大额单');
    const publish = within(row).getByRole('button', {
      name: zhCN['label.manage.publish'],
    });
    const write = holdWrite(tableSettingsStore.current!, 'create');
    await userEvent.click(publish);
    await heldWithTheKeyboard(publish);
    write.release();
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="view-manager-announcement"]')
          ?.textContent,
      ).toBe(say('label.manage.published', { title: '我盯的大额单' })),
    );
    await waitFor(() =>
      expect(publish).not.toHaveAttribute('aria-disabled', 'true'),
    );
    await expect(document.activeElement).toBe(publish);
  },
};

/** The star presses in and out where it is, and the keyboard stays on it. */
export const DefaultStarKeepsTheKeyboard: Story = {
  ...DisplayManageViews,
  args: { ...DisplayManageViews.args, systemViews: 'admin' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const row = await openManager(canvas, '我盯的大额单');
    const star = within(row).getByRole('button', {
      name: zhCN['label.manage.set-default'],
    });
    const write = holdWrite(tableSettingsStore.current!, 'setPreferences');
    await userEvent.click(star);
    await heldWithTheKeyboard(star);
    write.release();
    await waitFor(() => expect(star).toHaveAttribute('aria-pressed', 'true'));
    await waitFor(() =>
      expect(star).not.toHaveAttribute('aria-disabled', 'true'),
    );
    await expect(document.activeElement).toBe(star);
  },
};
