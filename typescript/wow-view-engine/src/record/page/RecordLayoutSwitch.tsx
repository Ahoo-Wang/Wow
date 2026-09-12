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

import { ChevronDownIcon, Table2Icon, LayoutGridIcon } from 'lucide-react';
import {
  Tooltip,
  TooltipProvider,
  TooltipTrigger,
  TooltipContent,
} from '../../components/ui/tooltip.js';
import { Button } from '../../components/ui/button.js';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '../../components/ui/dropdown-menu.js';
import type { RecordPresentation } from '../../contracts/viewModel.js';

const layouts = [
  { value: 'table', label: '表格' },
  { value: 'card', label: '卡片' },
] as const;
export function RecordLayoutSwitch({
  layout,
  onChange,
  allowedLayouts,
}: {
  layout: RecordPresentation['layout'];
  allowedLayouts: readonly RecordPresentation['layout'][];
  onChange(layout: RecordPresentation['layout']): void;
}) {
  if (allowedLayouts.length < 2) return null;
  const label = layouts.find(item => item.value === layout)!.label;
  return (
    <div role="group" aria-label="展示方式">
      <TooltipProvider>
        <Tooltip>
          <DropdownMenu>
            <DropdownMenuTrigger
              render={
                <TooltipTrigger
                  render={
                    <Button
                      size="sm"
                      variant="outline"
                      aria-label={`展示方式：${label}`}
                    />
                  }
                />
              }
            >
              {layout === 'table' ? (
                <Table2Icon aria-hidden="true" />
              ) : (
                <LayoutGridIcon aria-hidden="true" />
              )}
              <ChevronDownIcon aria-hidden="true" />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuRadioGroup
                value={layout}
                onValueChange={value => {
                  // 字面量比较已过滤出合法布局；包装组件回调参数为 any，收窄仅为类型表达。
                  if (value === 'table' || value === 'card')
                    onChange(value as 'table' | 'card');
                }}
              >
                {layouts
                  .filter(item => allowedLayouts.includes(item.value))
                  .map(item => (
                    <DropdownMenuRadioItem
                      key={item.value}
                      value={item.value}
                      closeOnClick
                    >
                      {item.label}
                    </DropdownMenuRadioItem>
                  ))}
              </DropdownMenuRadioGroup>
            </DropdownMenuContent>
          </DropdownMenu>
          <TooltipContent>{`展示方式：${label}`}</TooltipContent>
        </Tooltip>
      </TooltipProvider>
    </div>
  );
}
