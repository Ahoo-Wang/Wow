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

import { useId, useRef, type RefObject } from 'react';
import {
  ArrowLeftIcon,
  Settings2Icon,
  ChartAreaIcon,
  ChartColumnIcon,
  ChartLineIcon,
  ChartNoAxesCombinedIcon,
  ChartPieIcon,
  ChartScatterIcon,
  FunnelIcon,
  Grid3x3Icon,
  SquareSigmaIcon,
  TableIcon,
} from 'lucide-react';
import {
  CHART_PICKER_ORDER,
  type ChartFit,
  type Picked,
} from '../../analysis/index.js';
import type { ChartType } from '../../model/index.js';
import { cn } from 'cn';
import { IconButton } from '../IconButton.js';
import { TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { ChartTile } from '../variants.js';

const ICON: Record<ChartType | 'table', typeof TableIcon> = {
  bar: ChartColumnIcon,
  line: ChartLineIcon,
  area: ChartAreaIcon,
  combo: ChartNoAxesCombinedIcon,
  pie: ChartPieIcon,
  heatmap: Grid3x3Icon,
  scatter: ChartScatterIcon,
  funnel: FunnelIcon,
  metric: SquareSigmaIcon,
  table: TableIcon,
};

export type { Picked };

export interface ChartPickerProps {
  /** How each type fits the result on hand (`fitCharts`). */
  fits: Record<ChartType, ChartFit>;
  picked: Picked;
  onPick(picked: Picked): void;
  /** Opens the chosen type's options: the panel's second level. */
  onOptions(): void;
  /** Closes the panel: the way back to the view list. */
  onBack(): void;
  /**
   * The panel's own heading, which is where the keyboard is put when this
   * level comes up. `AnalysisParts` holds the ref, because the level it
   * belongs to is the state that moved (A1).
   */
  headingRef?: RefObject<HTMLHeadingElement | null>;
}

/**
 * The visualization panel's first level (D20 屏 I): a grid of the chart
 * types as tiles, in the sidebar column where the view list was. The
 * capability decides which tiles exist; the result's shape decides which
 * are greyed, and a greyed tile says what it lacks under itself; the one
 * the shape reads best as wears a mark. The table is a tile too, so
 * "back to the table" and "as a pie" are one gesture.
 *
 * It is a radiogroup, with the one tab stop a radiogroup has: the arrow
 * keys move the choice and the focus together over the tiles that can
 * draw, and Space and Enter choose where they stopped. A greyed tile is
 * stepped over — there is nothing to choose — and carries its reason in
 * its **description**, so a reader walking the group hears why it is out of
 * reach rather than only that it is. It is `aria-disabled` and not
 * `disabled` for that reason: `disabled` takes an element out of the
 * accessible tree's reach in some readers, reason and all.
 *
 * **A tile is named by what is written on it.** An `aria-label` would
 * replace the whole of the tile's content, and the 「推荐」 badge inside it
 * would then never be read — a mark drawn for everyone that only the sighted
 * ever got. So the name is the visible name, and `aria-describedby` points
 * at the badge and the reason where each exists: the reader hears 「柱状图,
 * 推荐」 and 「热力图, 需要两个维度」, which is what the tile says on screen.
 *
 * The chosen tile wears the gear that opens its options (D20 屏 J),
 * beside the tile rather than inside it — a button holds no button.
 */
export function ChartPicker({
  fits,
  picked,
  onPick,
  onOptions,
  onBack,
  headingRef,
}: ChartPickerProps) {
  const messages = useViewMessages();
  // One prefix for the picker, suffixed per tile: the ids the tiles' own
  // spans are addressed by, so the description is the markup on screen.
  const ids = useId();
  const tiles: { value: Picked; fit: ChartFit }[] = [
    ...CHART_PICKER_ORDER.map(type => ({ value: type, fit: fits[type] })),
    { value: 'table', fit: { available: true } },
  ];
  // Arrow keys move the choice and the focus together, as a radiogroup's
  // do; a greyed tile is passed over by the keys, though a tab may land on
  // it and hear why.
  const refs = useRef(new Map<Picked, HTMLButtonElement>());
  const move = (from: number, step: number) => {
    for (let at = 1; at <= tiles.length; at += 1) {
      const next = tiles[(from + step * at + tiles.length * at) % tiles.length];
      if (!next || !next.fit.available) continue;
      onPick(next.value);
      refs.current.get(next.value)?.focus();
      return;
    }
  };
  return (
    <div
      data-slot="chart-picker"
      className={cn('flex flex-col gap-3 p-3', TEXT_UI)}
    >
      <div className="flex items-center gap-2">
        <IconButton
          label={messages.label('label.chart.picker-back')}
          variant="ghost"
          size="icon-sm"
          onClick={onBack}
        >
          <ArrowLeftIcon />
        </IconButton>
        {/* `tabIndex={-1}`: the heading is not a control and stays off the
            Tab route, but the panel that has just replaced the view list has
            to be where the keyboard is, and a heading is what says which
            level it landed on. */}
        <h2 ref={headingRef} tabIndex={-1} className="text-base font-semibold">
          {messages.label('label.chart.picker')}
        </h2>
      </div>
      <div
        role="radiogroup"
        aria-label={messages.label('label.chart.picker')}
        className="grid grid-cols-3 gap-2 *:min-w-0 [&>div>button]:w-full"
      >
        {tiles.map(({ value, fit }, index) => {
          const Icon = ICON[value];
          const name = messages.label(
            value === 'table'
              ? 'label.layout.table'
              : `label.chart.type.${value}`,
          );
          const reason = fit.reason && messages.label(fit.reason);
          const nameId = `${ids}-name-${value}`;
          const badgeId = `${ids}-recommended-${value}`;
          const reasonId = `${ids}-reason-${value}`;
          const describedBy =
            [fit.recommended && badgeId, reason && reasonId]
              .filter(Boolean)
              .join(' ') || undefined;
          return (
            <div key={value} className="relative flex">
              <ChartTile
                ref={node => {
                  if (node) refs.current.set(value, node);
                  else refs.current.delete(value);
                }}
                role="radio"
                aria-checked={picked === value}
                aria-disabled={!fit.available || undefined}
                // The word on the tile, and nothing else: without this the
                // name is taken from the whole of the content, badge and
                // reason included, and the description would only say them
                // twice.
                aria-labelledby={nameId}
                aria-describedby={describedBy}
                data-chart-type={value}
                data-recommended={fit.recommended || undefined}
                tabIndex={picked === value ? 0 : -1}
                onClick={() => {
                  if (fit.available) onPick(value);
                }}
                onKeyDown={event => {
                  if (event.key === 'ArrowRight' || event.key === 'ArrowDown')
                    move(index, 1);
                  else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp')
                    move(index, -1);
                  else if (event.key === ' ' || event.key === 'Enter') {
                    event.preventDefault();
                    if (fit.available) onPick(value);
                  }
                }}
              >
                <Icon aria-hidden className="size-5" />
                <span id={nameId}>{name}</span>
                {fit.recommended && (
                  <span id={badgeId} data-slot="chart-recommended">
                    {messages.label('label.chart.recommended')}
                  </span>
                )}
                {reason && (
                  <span id={reasonId} data-slot="chart-reason">
                    {reason}
                  </span>
                )}
              </ChartTile>
              {picked === value && (
                <IconButton
                  label={messages.label('label.chart.options-of', { name })}
                  variant="ghost"
                  size="icon-xs"
                  className="absolute top-0.5 right-0.5"
                  data-slot="chart-options-open"
                  onClick={onOptions}
                >
                  <Settings2Icon />
                </IconButton>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
