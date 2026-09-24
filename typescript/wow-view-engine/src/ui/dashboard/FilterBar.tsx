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
import { InfoIcon, LockIcon, RotateCcwIcon, XIcon } from 'lucide-react';
import {
  filterCondition,
  filterControlValue,
  filterEditor,
  filterStoredValue,
  filtersOnTab,
} from '../../dashboard/index.js';
import { describeFilter } from '../../filter/index.js';
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
import { cn } from '../lib/utils.js';
import { useViewMessages } from '../MessagesProvider.js';
import { summaryText } from '../summary.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import {
  filterModeOf,
  holdsGrouping,
  sameValue,
  type BoardFilterModes,
} from './filterModes.js';
import {
  useFilterOrder,
  type FilterCarry,
  type FilterOrder,
} from './FilterOrder.js';

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
  /**
   * How an embedding page offers each filter (`DashboardFilterMode`): a
   * locked one is drawn as what it holds, without a control; a hidden one
   * not at all. Every filter is editable when left out.
   */
  modes?: BoardFilterModes;
  /**
   * While the board is built: the filters put in another order, by a handle
   * on each chip (`useFilterOrder`); left out, the order is read.
   */
  order?: FilterOrder;
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
  modes,
  order,
}: FilterBarProps) {
  const messages = useViewMessages();
  // What the page hid is not on the bar; what it locked is, as a reading.
  const fields = dashboard.filterFields.filter(
    field => filterModeOf(modes, field.name) !== 'hidden',
  );
  const sortable = useFilterOrder(fields, dashboard.filterFields, order);
  const groupingMode = modes?.grouping ?? 'editable';
  const grouping = groupingMode === 'hidden' ? null : dashboard.timeGrouping;
  if (fields.length === 0 && grouping === null && !add) return null;

  const onTab = dashboard.panels.filter(panel => panel.tab === dashboard.tab);
  const reaching = filtersOnTab(onTab, dashboard.tab);
  const grouped = onTab.some(panel => panel.grouping === 'taken');
  const { filters } = dashboard;
  const cleared = sameValue(filters, startOf(dashboard, modes));
  // 「清空」 is for what the reader holds: a bar of locked filters alone has
  // nothing it could clear.
  const clearable =
    fields.some(field => filterModeOf(modes, field.name) === 'editable') ||
    (grouping !== null && groupingMode === 'editable');
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
      {sortable.wrap(
        fields.map((field, index) =>
          sortable.carry(field, index, carry =>
            filterModeOf(modes, field.name) === 'locked' ? (
              <LockedChip
                key={field.name}
                field={field}
                dashboard={dashboard}
                settings={settings?.(field)}
                carry={carry}
              />
            ) : (
              <FilterChip
                key={field.name}
                field={field}
                dashboard={dashboard}
                idle={!reaching.has(field.name)}
                pressedOn={names.get(filters.from?.[field.name] ?? '')}
                settings={settings?.(field)}
                carry={carry}
              />
            ),
          ),
        ),
      )}
      {grouping && groupingMode === 'locked' && (
        <LockedReading
          slot="dashboard-grouping"
          label={messages.label('label.filters.grouping')}
          reading={messages.label(
            `label.date-unit.${filters.unit ?? grouping.default}`,
          )}
        />
      )}
      {grouping && groupingMode === 'editable' && (
        <GroupingControl
          units={grouping.units}
          unit={filters.unit ?? grouping.default}
          idle={!grouped}
          onChange={unit => dashboard.setGroupingUnit(unit)}
          onRemove={onRemoveGrouping}
        />
      )}
      {add}
      {clearable && (
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
 * required filter at its default, the time grouping at its own — and what
 * the page holds as it stands.
 */
function startOf(
  dashboard: DashboardController,
  modes: BoardFilterModes | undefined,
): DashboardFilters {
  const { filters } = dashboard;
  const values: Record<string, FilterValue> = {};
  for (const field of dashboard.filterFields) {
    const held = filterModeOf(modes, field.name) !== 'editable';
    const start = held
      ? filters.values[field.name]
      : field.required
        ? field.default
        : undefined;
    if (start !== undefined) values[field.name] = start;
  }
  const grouping = dashboard.timeGrouping;
  if (!grouping) return { values };
  return {
    values,
    unit: holdsGrouping(modes)
      ? (filters.unit ?? grouping.default)
      : grouping.default,
  };
}

/** One filter: its name, its value, and the way back to nothing. */
function FilterChip({
  field,
  dashboard,
  idle,
  pressedOn,
  settings,
  carry,
}: {
  field: DashboardField;
  dashboard: DashboardController;
  idle: boolean;
  /** The panel whose press set the value, by its name on the board. */
  pressedOn?: string;
  settings?: ReactNode;
  /** While the board is built: the handle it is carried by. */
  carry?: FilterCarry;
}) {
  const messages = useViewMessages();
  const kinds = dashboard.kinds;
  const value = dashboard.filters.values[field.name];
  const set = value !== undefined;
  const atDefault = field.required === true && sameValue(value, field.default);
  return (
    <div
      ref={carry?.ref}
      data-slot="dashboard-filter"
      data-filter={field.name}
      data-idle={idle || undefined}
      data-required={field.required || undefined}
      data-dragging={carry?.dragging || undefined}
      role="group"
      // The star is drawn, and said as a word: 「创建时间（必填）」.
      aria-label={
        field.required
          ? `${field.label} ${messages.label('label.filters.required')}`
          : field.label
      }
      // Quieter, not fainter: a dashed edge on the muted ground, with the
      // words at their own contrast — a faded chip is text axe cannot read.
      className={cn(
        'bg-muted/40 data-[idle]:bg-background flex min-w-0 shrink-0 items-center gap-1 rounded-md border py-0.5 pr-0.5 text-sm data-[idle]:border-dashed',
        carry ? 'pl-0.5' : 'pl-2',
      )}
    >
      {carry?.handle}
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

/**
 * A filter the page locked (`DashboardFilterMode`): what it holds, said as
 * the applied band says a condition — 「客户 是 明远商贸」 — with a lock,
 * and no control: the page fixed it, and the reader reads it. Its settings
 * still stand beside it while the board is built.
 */
function LockedChip({
  field,
  dashboard,
  settings,
  carry,
}: {
  field: DashboardField;
  dashboard: DashboardController;
  settings?: ReactNode;
  carry?: FilterCarry;
}) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const kinds = dashboard.kinds;
  const leaf =
    kinds &&
    filterCondition(field, dashboard.filters.values[field.name], kinds);
  const [item] =
    leaf && kinds
      ? describeFilter([field], { op: 'and', children: [leaf] }, kinds)
      : [];
  const reading = item
    ? summaryText({ ...item, label: undefined }, messages, display)
    : messages.label('label.embed.any');
  return (
    <LockedReading
      slot="dashboard-filter"
      name={field.name}
      label={field.label}
      reading={reading}
      settings={settings}
      carry={carry}
    />
  );
}

/** One reading the page fixed: its name, what it holds, and the lock. */
function LockedReading({
  slot,
  name,
  label,
  reading,
  settings,
  carry,
}: {
  slot: string;
  name?: string;
  label: string;
  reading: string;
  settings?: ReactNode;
  /** While the board is built, a locked filter is carried like the rest. */
  carry?: FilterCarry;
}) {
  const messages = useViewMessages();
  const locked = messages.label('label.embed.locked');
  return (
    <div
      ref={carry?.ref}
      data-slot={slot}
      data-filter={name}
      data-locked=""
      data-dragging={carry?.dragging || undefined}
      role="group"
      // 「客户（由页面设定）」: the lock is said, not only drawn.
      aria-label={messages.label('label.embed.locked-name', {
        filter: label,
      })}
      className={cn(
        'bg-muted/40 flex min-w-0 shrink-0 items-center gap-1 rounded-md border py-0.5 pr-0.5 text-sm',
        carry ? 'pl-0.5' : 'pl-2',
      )}
    >
      {carry?.handle}
      <span className="text-muted-foreground shrink-0 whitespace-nowrap">
        {label}
      </span>
      <span data-slot="filter-reading" className="min-w-0 truncate">
        {reading}
      </span>
      <IconTooltip
        label={locked}
        render={
          <Button
            data-slot="dashboard-filter-locked"
            variant="ghost"
            size="icon-xs"
          />
        }
      >
        <LockIcon />
      </IconTooltip>
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
