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

import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { SearchIcon } from 'lucide-react';
import type { FilterPanelProps } from './filterReactTypes.js';
import { useFilterPanelState } from './useFilterPanelState.js';
import { FilterAddControl } from './FilterAddControl.js';
import { FilterNode } from './FilterNode.js';
import { FilterSelect } from './FilterSelect.js';
import { filterLayout } from './filterPanelUtils.js';
import { Button } from '../components/ui/button.js';
import { cn } from '../lib/utils.js';

export function FilterPanel(props: FilterPanelProps) {
  const panel = useFilterPanelState(props);
  const showQueryAction = props.showQueryAction ?? true;
  const {
    panelId,
    mode,
    pending,
    disabled,
    toolbar,
    simple,
    issues,
    draft,
    epoch,
    apply,
    applyError,
  } = panel;
  return (
    <>
      {props.renderToolbar?.(toolbar)}
      <section
        id={panelId}
        hidden={props.collapsed}
        className={cn(
          'fve-root fve:flex fve:min-w-0 fve:flex-col fve:gap-3',
          props.className,
        )}
        aria-label="筛选器"
        onKeyDown={event => {
          if (
            !showQueryAction ||
            event.key !== 'Enter' ||
            event.defaultPrevented ||
            event.repeat ||
            event.nativeEvent.isComposing ||
            event.nativeEvent.keyCode === 229 ||
            event.altKey ||
            event.ctrlKey ||
            event.metaKey
          )
            return;
          const target = event.target;
          if (
            !(target instanceof HTMLInputElement) ||
            !event.currentTarget.contains(target)
          )
            return;
          if (
            ![
              'text',
              'search',
              'number',
              'email',
              'tel',
              'url',
              'date',
              'time',
              'datetime-local',
              'month',
              'week',
            ].includes(target.type)
          )
            return;
          const popup = target.closest(
            '[role="combobox"], [role="listbox"], [role="menu"], [role="dialog"]',
          );
          if (popup && event.currentTarget.contains(popup)) return;
          event.preventDefault();
          event.stopPropagation();
          apply();
        }}
      >
        {!props.renderToolbar && (
          <div className="fve:flex fve:flex-wrap fve:items-center fve:gap-2">
            <strong>筛选条件</strong>
            <FilterSelect
              label="筛选模式"
              value={mode}
              options={toolbar.options}
              onValueChange={toolbar.onModeChange}
              disabled={disabled}
            />
            <span role="status" aria-live="polite">
              {showQueryAction && pending ? '待查询' : ''}
            </span>
          </div>
        )}
        {!simple && (
          <p className="fve:m-0 fve:text-sm fve:text-muted-foreground">
            当前包含同一作用域内的重复字段、逻辑分组或特殊条件，需使用高级模式。
          </p>
        )}
        {mode === 'advanced' && simple && issues.length > 0 && (
          <p className="fve:m-0 fve:text-sm fve:text-muted-foreground">
            当前条件尚未完善，完成后可切换简单模式。
          </p>
        )}
        <div className={filterLayout}>
          {draft.operator === FilterOperator.MATCH_ALL ? null : mode ===
              'simple' && draft.operator === FilterOperator.AND ? (
            draft.operands?.map(child => (
              <FilterNode
                key={`${epoch}:${child.id}`}
                node={child}
                panel={panel}
              />
            ))
          ) : (
            <FilterNode
              key={`${epoch}:${draft.id}`}
              node={draft}
              showAddControl={false}
              panel={panel}
            />
          )}
        </div>
        <div className="fve:flex fve:flex-wrap fve:items-center fve:gap-2">
          <FilterAddControl
            target={draft}
            scopeFields={props.fields}
            scope="root"
            label="添加筛选"
            panel={panel}
          />
          <div className="fve:ml-auto fve:flex fve:flex-wrap fve:items-center fve:justify-end fve:gap-2">
            {showQueryAction &&
              !props.collapsed &&
              props.renderToolbar &&
              pending && (
                <span
                  role="status"
                  className="fve:text-sm fve:text-muted-foreground"
                >
                  筛选未生效
                </span>
              )}
            <Button
              variant="ghost"
              disabled={disabled || !pending}
              onClick={panel.undo}
            >
              撤销筛选修改
            </Button>
            <Button
              variant="ghost"
              disabled={disabled || !!panel.clearReason}
              aria-describedby={
                panel.clearReason ? `${panelId}-clear-help` : undefined
              }
              onClick={panel.clear}
            >
              清空条件
            </Button>
            {showQueryAction && (
              <Button
                disabled={panel.applyDisabled}
                onClick={apply}
                aria-keyshortcuts="Enter"
                title="查询（Enter）"
              >
                <SearchIcon aria-hidden="true" />
                {panel.submitting ? '查询中' : '查询'}
              </Button>
            )}
          </div>
        </div>
        {panel.clearReason && (
          <p
            id={`${panelId}-clear-help`}
            className="fve:m-0 fve:text-sm fve:text-muted-foreground"
          >
            {panel.clearReason}
          </p>
        )}
        {applyError && <div role="alert">{applyError}</div>}
        {props.queryError && <div role="alert">{props.queryError}</div>}
      </section>
    </>
  );
}
