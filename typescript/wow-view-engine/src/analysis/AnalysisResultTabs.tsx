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

import { useState, type ReactNode } from 'react';
import { Tabs } from '@base-ui/react/tabs';
import { ChartColumnIcon, Table2Icon } from 'lucide-react';
import { Button } from '../components/ui/button.js';

/** Local result inspection; the view owns query, presentation and table state. */
export function AnalysisResultTabs({
  children,
  table,
  issues = [],
  value,
  onValueChange,
  onConfigure,
}: {
  children: ReactNode;
  table: ReactNode;
  issues?: readonly string[];
  value: 'analysis' | 'table';
  onValueChange(value: 'analysis' | 'table'): void;
  onConfigure?(): void;
}) {
  const [tableVisited, setTableVisited] = useState(false);
  const available = issues.length === 0;
  if (value === 'table' && !tableVisited) setTableVisited(true);
  return (
    <Tabs.Root
      value={value}
      onValueChange={value => {
        if (value === 'analysis' || value === 'table') onValueChange(value);
        if (value === 'table') setTableVisited(true);
      }}
      className="fve-root fve:flex fve:min-w-0 fve:flex-col fve:gap-3"
    >
      <Tabs.Panel value="analysis" className="fve:min-w-0 fve:outline-none">
        {available ? (
          children
        ) : (
          <div className="fve:flex fve:min-h-64 fve:flex-col fve:items-center fve:justify-center fve:gap-3">
            <p role="status">
              {onConfigure ? '当前映射无法绘图' : issues.join('；')}
            </p>
            {onConfigure && (
              <details className="fve:text-sm">
                <summary className="fve:cursor-pointer">查看原因</summary>
                <p>{issues.join('；')}</p>
              </details>
            )}
            {onConfigure && (
              <Button variant="outline" onClick={onConfigure}>
                修复配置
              </Button>
            )}
            <Button variant="outline" onClick={() => onValueChange('table')}>
              查看数据表
            </Button>
          </div>
        )}
      </Tabs.Panel>
      <Tabs.Panel
        value="table"
        keepMounted={tableVisited}
        className="fve:min-w-0 fve:outline-none"
      >
        {table}
      </Tabs.Panel>
      <div className="fve:sticky fve:bottom-0 fve:z-10 fve:flex fve:justify-center fve:border-t fve:bg-background fve:pt-3 fve:pb-1">
        <Tabs.List
          aria-label="分析结果展示方式"
          activateOnFocus
          className="fve:inline-flex fve:items-center fve:gap-1 fve:rounded-lg fve:bg-muted fve:p-1"
        >
          <Tabs.Tab
            value="analysis"
            render={
              <Button
                variant="ghost"
                size="sm"
                className="fve:aria-selected:bg-background fve:aria-selected:shadow-sm"
              />
            }
          >
            <ChartColumnIcon aria-hidden="true" data-icon="inline-start" />
            分析
          </Tabs.Tab>
          <Tabs.Tab
            value="table"
            render={
              <Button
                variant="ghost"
                size="sm"
                className="fve:aria-selected:bg-background fve:aria-selected:shadow-sm"
              />
            }
          >
            <Table2Icon aria-hidden="true" data-icon="inline-start" />
            数据表
          </Tabs.Tab>
        </Tabs.List>
      </div>
    </Tabs.Root>
  );
}
