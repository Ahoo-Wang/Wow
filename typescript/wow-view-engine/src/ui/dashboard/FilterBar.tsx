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

import type { ReactNode } from 'react';
import { InfoIcon, RotateCcwIcon, XIcon } from 'lucide-react';
import {
  filterControlValue,
  filterEditor,
  filterStoredValue,
  filtersOnTab,
} from '../../dashboard/index.js';
import type {
  AnalysisDateUnit,
  DashboardField,
  DashboardFilters,
  FilterValue,
} from '../../model/index.js';
import type { DashboardController } from '../../react/index.js';
import { Badge } from '../components/badge.js';
import { Button } from '../components/button.js';
import { ToggleGroup, ToggleGroupItem } from '../components/toggle-group.js';
import { FilterValueEditor } from '../FilterValueEditor.js';
import { IconButton, IconTooltip } from '../IconButton.js';
import { panelNames } from '../DashboardPanel.js';
import { useViewMessages } from '../MessagesProvider.js';

export interface FilterBarProps {
  dashboard: DashboardController;
  /**
   * While the board is built: each filter's settings, and the grouping's
   * removal, beside it (`FilterSettings`); left out, the bar is read.
   */
  settings?(field: DashboardField): ReactNode;
  /** While the board is built: taking the time grouping off. */
  onRemoveGrouping?(): void;
  /** Where 「筛选 ＋」 stands while the board is built. */
  add?: ReactNode;
}

/**
 * The filter bar (D22 F): one chip a filter, its value edited with the
 * condition editor's own controls and the values the data holds
 * (`filterEditor`, `FilterValueEditor`), the time grouping as one
 * 「按日｜周｜月」, and 「清空」. What a filter holds is the reader's and runs
 * on its own a moment later (`DashboardController.setFilterValue`); a
 * required filter is starred and never empty — its ✕ goes back to the
 * default. A filter that reaches no panel on the tab on screen is drawn a
 * step quieter, and says why on hover and focus.
 */
export function FilterBar({
  dashboard,
  settings,
  onRemoveGrouping,
  add,
}: FilterBarProps) {
  const messages = useViewMessages();
  const fields = dashboard.filterFields;
  const grouping = dashboard.timeGrouping;
  if (fields.length === 0 && grouping === null && !add) return null;

  const onTab = dashboard.panels.filter(panel => panel.tab === dashboard.tab);
  const reaching = filtersOnTab(onTab, dashboard.tab);
  const grouped = onTab.some(panel => panel.grouping === 'taken');
  const { filters } = dashboard;
  const cleared = same(filters, startOf(dashboard));
  // The panel a value was pressed on, by the name the board calls it
  // (D22 I, 「来自「北区订单」」).
  const names = panelNames(dashboard.panels, messages);

  return (
    <div
      data-slot="dashboard-filter-bar"
      role="region"
      aria-label={messages.label('label.filters.bar')}
      // A row that scrolls sideways when the screen is narrow (D22 J), and
      // wraps where there is room.
      className="flex flex-wrap items-center gap-2 max-md:flex-nowrap max-md:overflow-x-auto"
    >
      {fields.map(field => (
        <FilterChip
          key={field.name}
          field={field}
          dashboard={dashboard}
          idle={!reaching.has(field.name)}
          pressedOn={names.get(filters.from?.[field.name] ?? '')}
          settings={settings?.(field)}
        />
      ))}
      {grouping && (
        <GroupingControl
          units={grouping.units}
          unit={filters.unit ?? grouping.default}
          idle={!grouped}
          onChange={unit => dashboard.setGroupingUnit(unit)}
          onRemove={onRemoveGrouping}
        />
      )}
      {add}
      {(fields.length > 0 || grouping) && (
        <Button
          data-slot="dashboard-filters-clear"
          variant="ghost"
          size="sm"
          className="ml-auto shrink-0"
          disabled={cleared}
          onClick={() => dashboard.clearFilters()}
        >
          {messages.label('label.filters.clear')}
        </Button>
      )}
    </div>
  );
}

/**
 * What 「清空」 comes back to (`DashboardRuntime.clearFilters`): every
 * required filter at its default, the time grouping at its own.
 */
function startOf(dashboard: DashboardController): DashboardFilters {
  const values: Record<string, FilterValue> = {};
  for (const field of dashboard.filterFields)
    if (field.required && field.default !== undefined)
      values[field.name] = field.default;
  return dashboard.timeGrouping
    ? { values, unit: dashboard.timeGrouping.default }
    : { values };
}

/** Whether two plain JSON values say the same. */
function same(a: unknown, b: unknown): boolean {
  return JSON.stringify(sorted(a)) === JSON.stringify(sorted(b));
}

/** A JSON value with its object keys in one order, for `same`. */
function sorted(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sorted);
  if (value === null || typeof value !== 'object') return value;
  return Object.fromEntries(
    Object.entries(value)
      .sort(([a], [b]) => (a < b ? -1 : 1))
      .map(([key, entry]) => [key, sorted(entry)]),
  );
}

/** One filter: its name, its value, and the way back to nothing. */
function FilterChip({
  field,
  dashboard,
  idle,
  pressedOn,
  settings,
}: {
  field: DashboardField;
  dashboard: DashboardController;
  idle: boolean;
  /** The panel whose press set the value, by its name on the board. */
  pressedOn?: string;
  settings?: ReactNode;
}) {
  const messages = useViewMessages();
  const kinds = dashboard.kinds;
  const value = dashboard.filters.values[field.name];
  const set = value !== undefined;
  const atDefault = field.required === true && same(value, field.default);
  return (
    <div
      data-slot="dashboard-filter"
      data-filter={field.name}
      data-idle={idle || undefined}
      data-required={field.required || undefined}
      role="group"
      // The star is drawn, and said as a word: 「创建时间（必填）」.
      aria-label={
        field.required
          ? `${field.label} ${messages.label('label.filters.required')}`
          : field.label
      }
      // Quieter, not fainter: a dashed edge on the muted ground, with the
      // words at their own contrast — a faded chip is text axe cannot read.
      className="bg-muted/40 data-[idle]:bg-background flex min-w-0 shrink-0 items-center gap-1 rounded-md border py-0.5 pr-0.5 pl-2 text-sm data-[idle]:border-dashed"
    >
      <span
        aria-hidden="true"
        className="text-muted-foreground shrink-0 whitespace-nowrap"
      >
        {field.label}
        {field.required && <span className="text-destructive">*</span>}
      </span>
      {kinds && (
        <div data-slot="filter-value" className="min-w-28">
          <FilterValueEditor
            editor={filterEditor(
              field,
              value,
              kinds,
              dashboard.filterChoices(field.name),
            )}
            kind={field.kind}
            label={field.label}
            value={filterControlValue(field, value)}
            onChange={next =>
              dashboard.setFilterValue(
                field.name,
                filterStoredValue(field, next, kinds),
              )
            }
            options={field.options}
            source={field.remote ? dashboard.filterOptions(field.remote) : null}
            candidates={dashboard.filterCandidates(field.name)}
          />
        </div>
      )}
      {pressedOn !== undefined && (
        <Badge
          data-slot="dashboard-filter-from"
          variant="secondary"
          className="shrink-0"
        >
          {messages.label('label.click.from', { panel: pressedOn })}
        </Badge>
      )}
      {idle && (
        <IconTooltip
          label={messages.label('label.filters.idle', { filter: field.label })}
          render={
            <Button
              data-slot="dashboard-filter-idle"
              variant="ghost"
              size="icon-xs"
            />
          }
        >
          <InfoIcon />
        </IconTooltip>
      )}
      {set && !atDefault && (
        <IconButton
          data-slot="dashboard-filter-clear"
          label={messages.label(
            field.required
              ? 'label.filters.back-to-default'
              : 'label.filters.clear-one',
            { filter: field.label },
          )}
          variant="ghost"
          size="icon-xs"
          onClick={() => dashboard.setFilterValue(field.name, null)}
        >
          {field.required ? <RotateCcwIcon /> : <XIcon />}
        </IconButton>
      )}
      {settings}
    </div>
  );
}

/** The time grouping (整板 按日｜周｜月): one choice among the units offered. */
function GroupingControl({
  units,
  unit,
  idle,
  onChange,
  onRemove,
}: {
  units: readonly AnalysisDateUnit[];
  unit: AnalysisDateUnit;
  idle: boolean;
  onChange(unit: AnalysisDateUnit): void;
  onRemove?(): void;
}) {
  const messages = useViewMessages();
  const name = messages.label('label.filters.grouping');
  return (
    <div
      data-slot="dashboard-grouping"
      data-idle={idle || undefined}
      className="flex shrink-0 items-center gap-1"
    >
      <ToggleGroup
        value={[unit]}
        onValueChange={next => {
          const picked = next[0] as AnalysisDateUnit | undefined;
          if (picked && units.includes(picked)) onChange(picked);
        }}
        variant="outline"
        size="sm"
        spacing={0}
        aria-label={name}
      >
        {units.map(entry => (
          <ToggleGroupItem key={entry} value={entry}>
            {messages.label(`label.date-unit.${entry}`)}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
      {idle && (
        <IconTooltip
          label={messages.label('label.filters.idle', { filter: name })}
          render={
            <Button
              data-slot="dashboard-filter-idle"
              variant="ghost"
              size="icon-xs"
            />
          }
        >
          <InfoIcon />
        </IconTooltip>
      )}
      {onRemove && (
        <IconButton
          data-slot="dashboard-grouping-remove"
          label={messages.label('label.filters.grouping-remove')}
          variant="ghost"
          size="icon-xs"
          onClick={onRemove}
        >
          <XIcon />
        </IconButton>
      )}
    </div>
  );
}
