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

import { useState } from 'react';
import { filter } from '@ahoo-wang/fetcher-wow';
import { Button, RecordTable } from '@ahoo-wang/fetcher-view-engine/react';
import type { DemoArgs, Story } from './demoTypes.js';
import { definition, makeInstances } from './fixtures.js';
import { orderCells, Scenario } from './Scenario.js';

export function ResponsiveWorkbench(args: DemoArgs) {
  const [narrow, setNarrow] = useState(false);
  return (
    <>
      <Button onClick={() => setNarrow(value => !value)}>
        {narrow ? '展开工作区' : '切换窄容器'}
      </Button>
      <div
        data-testid="responsive-record-host"
        style={{ width: narrow ? 414 : '100%', maxWidth: '100%' }}
      >
        <Scenario {...args} summaries pageSize={15} sidebarCollapsed />
      </div>
    </>
  );
}

export function ThemeSwitchingRecords({ appearance }: DemoArgs) {
  const [theme, setTheme] = useState(appearance);
  return (
    <>
      <Button
        onClick={() =>
          setTheme(value => (value === 'light' ? 'dark' : 'light'))
        }
      >
        切换主题
      </Button>
      <Scenario appearance={theme} />
    </>
  );
}

export const renderLoadingSummaries: Story['render'] = args => {
  const instance = makeInstances('paged', true).instances[0];
  instance.config.presentation.table.columns =
    instance.config.presentation.table.columns.map(column =>
      column.kind === 'field' && column.field === 'state.totalAmount'
        ? { ...column, summary: ['AVG', 'MIN', 'MAX'] }
        : column,
    );
  const loading = { status: 'loading' as const, values: {}, error: null };
  return (
    <div
      className="fve-root"
      data-theme={args.appearance}
      style={{
        padding: 16,
        background: 'var(--fve-background)',
      }}
    >
      <RecordTable
        definition={definition}
        extensions={{ cells: orderCells }}
        instance={instance}
        appliedFilter={filter.gte('state.totalAmount', 0)}
        rows={[]}
        querying
        selectable
        pageSummary={loading}
        allSummary={loading}
        selectedRowKeys={[]}
        onSelectionChange={() => {}}
        onColumnsChange={() => {}}
        onSortChange={() => {}}
        refresh={async () => {}}
      />
    </div>
  );
};
