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
          '个人、公共两组视图共用定义；系统视图显示“系统”标签，名称和删除受保护。管理入口位于视图导航，改名需先点击编辑图标，排序保存为当前用户偏好。\n\n尝试：修改并查询 → 保存 → 切换再返回 → 管理视图改名/排序/删除。删除失败后应保留该视图并允许重试。保存按钮同时受已应用状态、写入状态和宿主权限约束。\n\n普通场景使用内存宿主；跨浏览器刷新恢复见“开发验证 → 本地视图恢复”。ViewHost.instance 管理视图内容，preference 管理用户顺序，permission 提供权限投影。',
      },
    },
  },
  title: 'View Engine/Record View/视图管理',
};

export default meta;

export const RestoreFocus: Story = {
  name: '仅保存权限 · 还原焦点',
  render: args => <Scenario {...args} saveOnly />,
};

export const ManageViews: Story = {
  name: '管理视图 · 改名、排序与删除',
  render: args => <Scenario {...args} failFirstDelete sidebarCollapsed />,
};
