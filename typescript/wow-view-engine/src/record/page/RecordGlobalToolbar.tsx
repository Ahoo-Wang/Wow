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
  ChevronDownIcon,
  ListFilterIcon,
  Maximize2Icon,
  Minimize2Icon,
} from 'lucide-react';
import type { ReactNode, RefObject } from 'react';
import { ButtonGroup } from '../../components/ui/button-group.js';
import { Button } from '../../components/ui/button.js';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '../../components/ui/dropdown-menu.js';
import type { FilterPanelToolbarProps } from '../../filter/filterReactTypes.js';
import type { RecordSession, ViewDefinition } from '../recordModel.js';
import type { ViewExtensions } from '../recordReactTypes.js';
import { RecordRefreshControls } from '../RecordRefreshControls.js';
import type { ViewEngine } from '../ViewEngine.js';
import type { useViewExpansion } from '../viewExpansion.js';
import { RecordActions } from './RecordActions.js';

export function RecordGlobalToolbar({
  engine,
  definition,
  session,
  extensions,
  toolbarStart,
  filterToolbar,
  filtersOpen,
  onFiltersOpenChange,
  rootRef,
  paused,
  expansion,
  refresh,
  onRefresh,
}: {
  engine: ViewEngine;
  definition: ViewDefinition;
  session: RecordSession;
  extensions?: ViewExtensions;
  toolbarStart?: ReactNode;
  filterToolbar: FilterPanelToolbarProps;
  filtersOpen: boolean;
  onFiltersOpenChange(open: boolean): void;
  rootRef: RefObject<HTMLElement | null>;
  paused: boolean;
  expansion: ReturnType<typeof useViewExpansion>;
  refresh(): Promise<void>;
  onRefresh(): void;
}) {
  const { panelId, mode, options, pending, disabled, onModeChange } =
    filterToolbar;
  return (
    <header
      role="group"
      aria-label="全局工具栏"
      className="fve:flex fve:flex-wrap fve:items-center fve:justify-between fve:gap-2 fve:px-3 fve:py-2"
    >
      <div className="fve:flex fve:min-w-0 fve:flex-wrap fve:items-center fve:gap-2">
        {toolbarStart === undefined ? (
          <h1 className="fve:text-lg fve:font-semibold">{definition.title}</h1>
        ) : (
          toolbarStart
        )}
      </div>
      <div className="fve:ml-auto fve:flex fve:flex-wrap fve:items-center fve:gap-2">
        <DropdownMenu>
          <ButtonGroup aria-label="筛选控制">
            <Button
              variant="outline"
              size="sm"
              aria-label={filtersOpen ? '收起筛选' : '展开筛选'}
              aria-expanded={filtersOpen}
              aria-controls={panelId}
              aria-describedby={
                !filtersOpen && pending ? `${panelId}-pending` : undefined
              }
              onClick={() => onFiltersOpenChange(!filtersOpen)}
            >
              <ListFilterIcon data-icon="inline-start" aria-hidden="true" />
              筛选 · {mode === 'simple' ? '简单' : '高级'}
              {!filtersOpen && pending && (
                <span id={`${panelId}-pending`}>· 待查询</span>
              )}
            </Button>
            <DropdownMenuTrigger
              render={
                <Button
                  variant="outline"
                  size="icon-sm"
                  aria-label="筛选模式"
                  disabled={disabled}
                />
              }
            >
              <ChevronDownIcon aria-hidden="true" />
            </DropdownMenuTrigger>
          </ButtonGroup>
          <DropdownMenuContent align="end">
            <DropdownMenuRadioGroup
              value={mode}
              onValueChange={next => {
                if (next !== 'simple' && next !== 'advanced') return;
                if (options.find(option => option.value === next)?.disabled)
                  return;
                onModeChange(next);
                onFiltersOpenChange(true);
              }}
            >
              {options.map(option => (
                <DropdownMenuRadioItem
                  key={option.value}
                  value={option.value}
                  disabled={option.disabled}
                  closeOnClick
                >
                  {option.label}
                </DropdownMenuRadioItem>
              ))}
            </DropdownMenuRadioGroup>
          </DropdownMenuContent>
        </DropdownMenu>
        <RecordRefreshControls
          key={`refresh:${session.instance.id}`}
          engine={engine}
          session={session}
          root={rootRef}
          paused={paused}
          onRefresh={onRefresh}
        />
        <Button
          variant="outline"
          size="icon-sm"
          aria-label={expansion.expanded ? '收起视图' : '展开视图'}
          data-slot="view-expand"
          title={expansion.expanded ? '收起视图（Esc）' : '展开视图'}
          aria-pressed={expansion.expanded}
          onClick={expansion.toggle}
        >
          {expansion.expanded ? (
            <Minimize2Icon aria-hidden="true" />
          ) : (
            <Maximize2Icon aria-hidden="true" />
          )}
        </Button>
        <RecordActions
          kind="global"
          definition={definition}
          session={session}
          extensions={extensions}
          refresh={refresh}
        />
      </div>
    </header>
  );
}
