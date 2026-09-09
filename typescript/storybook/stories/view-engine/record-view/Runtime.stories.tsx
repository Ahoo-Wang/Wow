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
import type { Story } from './demoTypes.js';
import { Scenario } from './Scenario.js';
import { recordViewMeta } from './meta.js';

const meta = {
  ...recordViewMeta,
  parameters: {
    ...recordViewMeta.parameters,
    docs: {
      ...recordViewMeta.parameters.docs,
      description: {
        component:
          '手动刷新、自动刷新和页面展开放在顶部全局工具栏。自动刷新可选 30 秒、1 分钟、5 分钟，显示倒计时；刷新使用已应用条件，不自动提交筛选草稿。\n\n后台刷新保留最近的成功记录。页面不可见、已有工作或 autoRefreshPaused 时暂停自动读取；业务操作应负责设置自己的暂停状态。\n\n展开使用页面内布局，Escape 退出并恢复焦点。尝试设置自动刷新、编辑未查询条件、展开/退出，再观察记录与草稿是否保持独立。',
      },
    },
  },
  title: 'View Engine/Record View/运行时工具',
};

export default meta;

export const RuntimeTools: Story = {
  name: '自动刷新与页面展开',
  render: args => (
    <div style={{ maxWidth: 900 }}>
      <Scenario {...args} />
    </div>
  ),
};
