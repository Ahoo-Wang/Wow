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
import { LockIcon } from 'lucide-react';
import { filterCondition } from '../../dashboard/index.js';
import { describeFilter, type FilterSummaryItem } from '../../filter/index.js';
import type { DashboardField } from '../../model/index.js';
import type { DashboardController } from '../../react/index.js';
import { Button } from '../components/button.js';
import { IconTooltip } from '../IconButton.js';
import { cn } from '../lib/utils.js';
import { useViewMessages } from '../MessagesProvider.js';
import { summaryText } from '../summary.js';
import { ControlFrame } from '../variants.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import type { FilterCarry } from './FilterOrder.js';

/*
 * What the filter bar holds that the reader reads and does not change:
 * the board's fixed scope, a filter the page locked, the time grouping the
 * page locked — each one chip, said as the applied band says a condition,
 * with the reason it stays beside it.
 */

/** How a chip lays out its name, its control and its buttons in its frame. */
export const CHIP =
  'flex min-w-0 shrink-0 items-center gap-1 py-0.5 pr-0.5 text-sm';

/**
 * The board's fixed scope (D26 Q31, D27): the condition a board saved
 * before its filters could not hold, in force on every panel. Read-only and
 * said as 「固定范围」, the way a locked filter says the page holds it: the
 * board keeps it, and no reader takes it out.
 */
export function FixedScope({ items }: { items: readonly FilterSummaryItem[] }) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const label = messages.label('label.filters.fixed');
  return (
    <ControlFrame
      data-slot="dashboard-fixed-scope"
      role="group"
      aria-label={label}
      className={cn(CHIP, 'pl-2')}
    >
      <span className="text-muted-foreground shrink-0 whitespace-nowrap">
        {label}
      </span>
      {items.map(item => (
        <span
          key={item.path.join('.')}
          data-slot="filter-reading"
          data-unresolved={item.unresolved || undefined}
          className="min-w-0 truncate"
        >
          {summaryText(item, messages, display)}
        </span>
      ))}
      <IconTooltip
        label={messages.label('label.filters.fixed-note')}
        render={
          <Button
            data-slot="dashboard-fixed-scope-note"
            variant="ghost"
            size="icon-xs"
          />
        }
      >
        <LockIcon />
      </IconTooltip>
    </ControlFrame>
  );
}

/**
 * A filter the page locked (`DashboardFilterMode`): what it holds, said as
 * the applied band says a condition — 「客户 是 明远商贸」 — with a lock,
 * and no control: the page fixed it, and the reader reads it. Its settings
 * still stand beside it while the board is built.
 */
export function LockedChip({
  field,
  dashboard,
  settings,
  carry,
  beside = false,
}: {
  field: DashboardField;
  dashboard: DashboardController;
  settings?: ReactNode;
  carry?: FilterCarry;
  /**
   * Read beside the narrow bar's button rather than on the bar: the same
   * reading, under a slot of its own so the bar's chips stay the bar's.
   */
  beside?: boolean;
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
      slot={beside ? 'dashboard-filter-held' : 'dashboard-filter'}
      name={field.name}
      label={field.label}
      reading={reading}
      settings={settings}
      carry={carry}
    />
  );
}

/** One reading the page fixed: its name, what it holds, and the lock. */
export function LockedReading({
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
    <ControlFrame
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
      className={cn(CHIP, carry ? 'pl-0.5' : 'pl-2')}
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
    </ControlFrame>
  );
}
