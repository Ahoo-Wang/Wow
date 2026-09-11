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

import type { Meta, StoryObj } from '@storybook/react-vite';
import { CompensationExample } from '../../packages/view-engine/examples/react/compensation/CompensationExample.js';
const meta = {
  id: 'view-engine-补偿-api-数据',
  title: 'View Engine/真实 API 接入/补偿数据',
  component: CompensationExample,
  args: { kind: 'record' },
  parameters: {
    layout: 'fullscreen',
    docs: {
      description: {
        component:
          '真实 dev 快照 API → SnapshotQueryClient → IndexedDBViewHost → ViewEngine → ViewPage / RecordView。手动连接后验证筛选、排序、分页；业务数据来自服务，个人视图配置仅保存在本机。',
      },
    },
  },
} satisfies Meta<typeof CompensationExample>;
export default meta;
export const Dev: StoryObj<typeof meta> = { name: 'dev 快照查询联调' };
