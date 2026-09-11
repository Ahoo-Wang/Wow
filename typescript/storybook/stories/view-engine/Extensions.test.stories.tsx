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
import {
  playFailureAndScope,
  playRefreshRecovery,
} from './libraryDelivery.play.js';
import { playFilterPersistence } from './filterPersistence.play.js';
import displayMeta, {
  FailureAndScopedRefresh as DisplayFailureAndScopedRefresh,
  FilterPersistence as DisplayFilterPersistence,
  RefreshRecovery as DisplayRefreshRecovery,
} from './Extensions.stories.js';
import type { StoryObj as RegressionStoryObj } from '@storybook/react-vite';

const meta = {
  ...displayMeta,
  id: 'view-engine-专项场景-配置与恢复-回归',
  title: 'View Engine/引擎与宿主/配置与恢复/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

type Story = RegressionStoryObj<typeof displayMeta>;

export const FilterPersistence: Story = {
  ...DisplayFilterPersistence,
  tags: ['!dev', '!autodocs', 'test'],
  play: playFilterPersistence,
};

export const FailureAndScopedRefresh: Story = {
  ...DisplayFailureAndScopedRefresh,
  tags: ['!dev', '!autodocs', 'test'],
  play: playFailureAndScope,
};

export const RefreshRecovery: Story = {
  ...DisplayRefreshRecovery,
  tags: ['!dev', '!autodocs', 'test'],
  play: playRefreshRecovery,
};
