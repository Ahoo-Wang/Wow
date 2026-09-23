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
import { useState } from 'react';
import { DataWorkbench } from '@ahoo-wang/fetcher-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import {
  CUSTOMER,
  DEFAULT_CRM_HOST,
  createCustomerEngine,
  crmFetcher,
} from './customer.js';
import { HOST_LANGUAGE } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

/**
 * The customer console — one workbench for the customers a real CRM service
 * holds and the analysis over them: who they are, who owns them, which sit
 * in the public pool, and how they distribute over owners, industries,
 * tenants and days. The record views and the analysis views sit in one list.
 *
 * The console only reads: it sends the service queries and no command.
 * Nothing here is faked, so nothing here is repeatable — this story is for
 * using, not for CI (its regression twin runs over a recorded service).
 */

/**
 * One engine per host, keyed by the host so pointing the Controls panel
 * elsewhere starts over rather than mixing two services' views.
 */
function Console({ host }: { host: string }) {
  return <HostConsole key={host} host={host} />;
}

function HostConsole({ host }: { host: string }) {
  const [fetcher] = useState(() => crmFetcher(host));
  return (
    <StoryEngine create={() => createCustomerEngine(fetcher)}>
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={CUSTOMER}
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
}

/**
 * What the scene is, on the docs page rather than above the console: the
 * console is the product an operator uses, so it has the screen to itself.
 */
const description = `**真实后端 · 客户**

CRM 服务里的客户：真实的数据、真实的数据量；同一个工作台里查客户明细、看公海与负责人、按负责人／行业／租户／日期做分析。

- **数据源**：CRM 服务的 \`customer\` 聚合快照，地址是 \`host\` 参数，可在 Controls 面板切换；改动后按新地址重建引擎。默认 \`http://localhost:8085\`（dev 集群 \`crm-service\` 的本地端口转发）；集群内地址是 \`http://crm-service.dev.svc.cluster.local\`。
- **准备**：引擎与视图存储随故事新建；数据直连 \`host\` 指向的服务，只读。
- **操作**：打开「快照控制台」场景，它放在宿主应用里——顶部导航与左侧应用导航是宿主的，中间那一块是视图引擎——像操作员使用时一样。
- **观察**：筛选、排序、分页、汇总与聚合都由服务端执行；联系人按姓名列出，按联系人筛选是对 \`state.contacts\` 的元素匹配，按联系人分析会展开数组、以联系人为计数单位。

> 只读：场景只向服务发查询，不发命令。`;

const meta = {
  title: 'View Engine/真实后端/客户/快照控制台',
  component: Console,
  // A live service answers differently every time, so this is never a
  // regression test, and its docs page does not mount it: opening the
  // catalog must not call the service.
  tags: ['!test'],
  parameters: {
    // The console is the product: it fills the canvas inside the host's
    // own bar and navigation (`AppShell`), as it would a screen.
    layout: 'fullscreen',
    docs: { autoMount: false, description: { component: description } },
  },
  args: { host: DEFAULT_CRM_HOST },
  argTypes: {
    host: {
      control: 'text',
      description: 'CRM 服务地址，改动后按新地址重建引擎。',
    },
  },
  decorators: [
    (Story, context) => (
      <AppShell
        current="customer-snapshots"
        service={{ host: context.args.host }}
      >
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof Console>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Customers, who owns them, and the analysis over them. */
export const DataConsole: Story = { name: '快照控制台' };
