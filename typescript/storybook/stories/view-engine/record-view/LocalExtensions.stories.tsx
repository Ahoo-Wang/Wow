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
          '本页使用简化订单快照验证单项引擎契约；完整销售业务见“全链路体验”。\n\n通过定义的 recordActions 引用全局/表格/行操作，通过字段或列 renderer 引用单元格。这里使用本地定义与应用提供的运行时注册，不要求远端视图服务。\n\n操作获得已应用 filter、sort、选中键或当前记录，以及绑定实例的 refresh。业务应用负责执行写入、错误反馈和防重；读写结果不会通过修改只读 record/instance 对象回写。完整五类扩展源码见“扩展接入”。',
      },
    },
  },
  id: 'view-engine-专项场景-本地操作宿主',
  title: 'View Engine/专项场景/视图与运行时/本地操作宿主',
};

export default meta;

export const LocalDefinitions: Story = {
  name: '本地定义 · 自定义订单操作',
  render: args => <Scenario {...args} local />,
};
