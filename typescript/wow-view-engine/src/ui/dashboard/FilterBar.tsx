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

import { useRef, type ReactNode } from 'react';
import { InfoIcon, RotateCcwIcon, XIcon } from 'lucide-react';
import {
  filterControlValue,
  filterEditor,
  filterStoredValue,
  filtersOnTab,
} from '../../dashboard/index.js';
import type { FilterSummaryItem } from '../../filter/index.js';
import type {
  AnalysisDateUnit,
  DashboardField,
  DashboardFilters,
  FilterValue,
} from '../../model/index.js';
import { filterTypeOf, sameJson } from '../../model/index.js';
import type { DashboardController } from '../../react/index.js';
import { Badge } from '../components/badge.js';
import { Button } from '../components/button.js';
import { ToggleGroup, ToggleGroupItem } from '../components/toggle-group.js';
import { FilterValueEditor } from '../FilterValueEditor.js';
import { IconButton, IconTooltip } from '../IconButton.js';
import { panelNames } from '../DashboardPanel.js';
import { cn } from '../lib/utils.js';
import {
  useViewMessages,
  type MessageFormatters,
} from '../MessagesProvider.js';
import { ControlFrame } from '../variants.js';
import {
  filterModeOf,
  holdsGrouping,
  type BoardFilterModes,
} from './filterModes.js';
import {
  useFilterOrder,
  type FilterCarry,
  type FilterOrder,
} from './FilterOrder.js';
import {
  CHIP,
  FixedScope,
  LockedChip,
  LockedReading,
} from './FilterReadings.js';
import { FilterSheet } from './FilterSheet.js';
import {
  addFilterOf,
  boardOf,
  chipOf,
  chipsOf,
  undoOf,
  useLanding,
  valueOf,
} from './landing.js';

export interface FilterBarProps {
  dashboard: DashboardController;
  /**
   * While the board is built: each filter's settings, and the grouping's
   * removal, beside it (`FilterSettings`); left out, the bar is read.
   */
  settings?(field: DashboardField): ReactNode;
  /** While the board is built: taking the time grouping off. */
  onRemoveGrouping?(): void;
  /**
   * While the board is built: taking the board's fixed scope out whole
   * (D23 Q16); left out, it is read with a lock.
   */
  onRemoveFixed?(): void;
  /** Where 「添加筛选」 stands while the board is built. */
  add?: ReactNode;
  /**
   * How an embedding page offers each filter (`DashboardFilterMode`): a
   * locked one is drawn as what it holds, without a control; a hidden one
   * not at all. Every filter is adjustable when left out.
   */
  modes?: BoardFilterModes;
  /**
   * While the board is built: the filters put in another order, by a handle
   * on each chip (`useFilterOrder`); left out, the order is read. Never with
   * `modes`: a board is built in the workbench alone, whose bar holds every
   * filter (D36).
   */
  order?: FilterOrder;
  /**
   * The board's fixed scope in force (D26 Q31): at the head of the row, as
   * 「固定范围」 — read-only to a reader (D27), removable whole while the
   * board is built (`onRemoveFixed`).
   */
  fixed?: readonly FilterSummaryItem[] | undefined;
  /**
   * The one-column reading (below `md`): the bar is one button that opens
   * every filter in a sheet from the bottom edge, what the reader cannot
   * change read beside it (D26 Q38, `FilterSheet`).
   */
  narrow?: boolean;
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
  onRemoveFixed,
  add,
  modes,
  order,
  fixed = NO_FIXED,
  narrow = false,
}: FilterBarProps) {
  const messages = useViewMessages();
  // What the page hid is not on the bar; what it locked is, as a reading.
  const fields = dashboard.filterFields.filter(
    field => filterModeOf(modes, field.name) !== 'hidden',
  );
  const sortable = useFilterOrder(fields, order);
  // A press that takes its own control away lands the keyboard on the next
  // sensible one (U-02); the board is read as the press happens, since the
  // bar itself may be gone by the time the keyboard lands.
  const bar = useRef<HTMLDivElement>(null);
  const land = useLanding();
  const groupingMode = modes?.grouping ?? 'adjustable';
  const grouping = groupingMode === 'hidden' ? null : dashboard.timeGrouping;
  if (fields.length === 0 && grouping === null && !add && fixed.length === 0)
    return null;

  const onTab = dashboard.panels.filter(panel => panel.tab === dashboard.tab);
  const reaching = filtersOnTab(onTab, dashboard.tab);
  const grouped = onTab.some(panel => panel.grouping === 'taken');
  const { filters } = dashboard;
  const cleared = sameJson(filters, startOf(dashboard, modes));
  // 「清空」 is for what the reader holds: a bar of locked filters alone has
  // nothing it could clear.
  const clearable =
    fields.some(field => filterModeOf(modes, field.name) === 'adjustable') ||
    (grouping !== null && groupingMode === 'adjustable');
  // The panel a value was pressed on, by the name the board calls it
  // (D22 I, 「来自「北区订单」」).
  const names = panelNames(dashboard.panels, messages);

  const scope = fixed.length > 0 && (
    <FixedScope
      items={fixed}
      onRemove={
        onRemoveFixed &&
        (from => {
          // The chip goes with its ✕: on to 「撤销」, which brings it back,
          // as a panel removed lands there.
          const board = boardOf(from);
          onRemoveFixed();
          land(
            () =>
              undoOf(board) ?? valueOf(chipsOf(board)[0]) ?? addFilterOf(board),
          );
        })
      }
    />
  );
  const items = (
    <>
      {sortable.wrap(
        fields.map((field, index) =>
          sortable.carry(field, index, carry =>
            filterModeOf(modes, field.name) === 'locked' ? (
              <LockedChip
                key={field.name}
                field={field}
                dashboard={dashboard}
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
                // The ✕ goes with the value: back to the value's control.
                onCleared={() => {
                  const board = bar.current;
                  land(() => valueOf(chipOf(board, field.name)));
                }}
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
      {grouping && groupingMode === 'adjustable' && (
        <GroupingControl
          units={grouping.units}
          unit={filters.unit ?? grouping.default}
          idle={!grouped}
          onChange={unit => dashboard.setGroupingUnit(unit)}
          onRemove={
            onRemoveGrouping &&
            (() => {
              const board = boardOf(bar.current);
              onRemoveGrouping();
              land(() => addFilterOf(board));
            })
          }
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
          onClick={() => {
            const board = bar.current;
            dashboard.clearFilters();
            // Disabled by its own press: on to the first value the reader
            // can set again, or the time grouping.
            land(
              () =>
                valueOf(
                  chipsOf(board).find(chip => !('locked' in chip.dataset)),
                ) ?? board?.querySelector('[data-slot="dashboard-grouping"]'),
            );
          }}
        >
          {messages.label('label.filters.clear')}
        </Button>
      )}
    </>
  );

  if (narrow)
    return (
      <FilterSheet
        listRef={bar}
        count={
          fields.filter(
            field =>
              filterModeOf(modes, field.name) === 'adjustable' &&
              filters.values[field.name] !== undefined,
          ).length
        }
        // Only for something to change: what the reader holds, or — while
        // the board is built — every filter's settings.
        opens={
          clearable ||
          Boolean(add) ||
          (settings !== undefined && fields.length > 0)
        }
        held={
          <>
            {scope}
            {fields.map(
              field =>
                filterModeOf(modes, field.name) === 'locked' && (
                  <LockedChip
                    key={field.name}
                    field={field}
                    dashboard={dashboard}
                    beside
                  />
                ),
            )}
          </>
        }
      >
        {items}
      </FilterSheet>
    );
  return (
    <div
      ref={bar}
      data-slot="dashboard-filter-bar"
      role="region"
      aria-label={messages.label('label.filters.bar')}
      className="flex flex-wrap items-center gap-2"
    >
      {scope}
      {items}
    </div>
  );
}

const NO_FIXED: readonly FilterSummaryItem[] = [];

/**
 * What a search filter's empty box says, on the bar and in its settings:
 * 「搜索…」, the invitation to type, rather than 「未设置」 — an empty search
 * is the box's normal state, not a value missing. Nothing for a filter of
 * another type.
 */
export function searchPlaceholder(
  field: DashboardField,
  messages: MessageFormatters,
): string | undefined {
  return filterTypeOf(field.kind) === 'search'
    ? messages.label('label.filters.search-placeholder')
    : undefined;
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
    const held = filterModeOf(modes, field.name) !== 'adjustable';
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
  onCleared,
}: {
  field: DashboardField;
  dashboard: DashboardController;
  idle: boolean;
  /** Told once its ✕ took the value away, and the ✕ with it. */
  onCleared(): void;
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
  const atDefault = field.required === true && sameJson(value, field.default);
  return (
    <ControlFrame
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
      // Quieter, not fainter (`ControlFrame`): a dashed edge on the page's
      // ground, the words at their own contrast.
      className={cn(CHIP, carry ? 'pl-0.5' : 'pl-2')}
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
            required={field.required}
            onChange={next =>
              dashboard.setFilterValue(
                field.name,
                filterStoredValue(field, next, kinds),
              )
            }
            options={field.options}
            source={field.remote ? dashboard.filterOptions(field.remote) : null}
            candidates={dashboard.filterCandidates(field.name)}
            placeholder={searchPlaceholder(field, messages)}
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
          onClick={() => {
            dashboard.setFilterValue(field.name, null);
            onCleared();
          }}
        >
          {field.required ? <RotateCcwIcon /> : <XIcon />}
        </IconButton>
      )}
      {settings}
    </ControlFrame>
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
