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
import { expect, userEvent, within } from 'storybook/test';

async function queryAndExpect(canvasElement: HTMLElement, text: string) {
  const canvas = within(canvasElement);
  await userEvent.click(canvas.getByRole('button', { name: 'Run query' }));
  await expect(await canvas.findByText(text)).toBeVisible();
}
import displayMeta, {
  Count as DisplayCount,
  List as DisplayList,
  Paged as DisplayPaged,
  Single as DisplaySingle,
  Streaming as DisplayStreaming,
} from './WowQueryHooks.stories.js';
import type { StoryObj as RegressionStoryObj } from '@storybook/react-vite';

const meta = {
  ...displayMeta,
  title: 'React Hooks/Wow Queries/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

type Story = RegressionStoryObj<typeof displayMeta>;

export const Single: Story = {
  ...DisplaySingle,
  tags: ['!dev', '!autodocs', 'test'],
  play: ({ canvasElement }) => queryAndExpect(canvasElement, 'Single · Ada'),
};

export const List: Story = {
  ...DisplayList,
  tags: ['!dev', '!autodocs', 'test'],
  play: ({ canvasElement }) => queryAndExpect(canvasElement, 'List · Ada, Lin'),
};

export const Paged: Story = {
  ...DisplayPaged,
  tags: ['!dev', '!autodocs', 'test'],
  play: ({ canvasElement }) => queryAndExpect(canvasElement, 'Paged · 2 of 2'),
};

export const Count: Story = {
  ...DisplayCount,
  tags: ['!dev', '!autodocs', 'test'],
  play: ({ canvasElement }) => queryAndExpect(canvasElement, 'Count · 2'),
};

export const Streaming: Story = {
  ...DisplayStreaming,
  tags: ['!dev', '!autodocs', 'test'],
  play: ({ canvasElement }) =>
    queryAndExpect(canvasElement, 'Stream · Ada, Lin'),
};
