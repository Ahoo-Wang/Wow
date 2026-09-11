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
import { playRestoreFocus } from './persistence.play.js';
import { playManageViews } from './management.play.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';
import displayMeta, {
  ManageViews as DisplayManageViews,
  RestoreFocus as DisplayRestoreFocus,
} from './Management.stories.js';
import type { Story } from './demoTypes.js';

const meta = {
  ...displayMeta,
  id: 'view-engine-专项场景-record-view-视图管理-回归',
  title: 'View Engine/引擎与宿主/视图管理/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

export const RestoreFocus: Story = {
  ...DisplayRestoreFocus,
  tags: ['!dev', '!autodocs', 'test'],
  play: playRestoreFocus,
};

export const ManageViews: Story = {
  ...DisplayManageViews,
  tags: ['!dev', '!autodocs', 'test'],
  play: playManageViews,
};
