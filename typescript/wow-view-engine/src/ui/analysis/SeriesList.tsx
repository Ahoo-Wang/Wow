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

import { DragDropProvider } from '@dnd-kit/react';
import { useSortable } from '@dnd-kit/react/sortable';
import { Accessibility } from '@dnd-kit/dom';
import { OptimisticSortingPlugin } from '@dnd-kit/dom/sortable';
import { PlusIcon, XIcon } from 'lucide-react';
import { movedTo } from '../../analysis/index.js';
import type {
  CartesianSeries,
  CartesianSpec,
  ChartType,
} from '../../model/index.js';
import { useAnnouncer } from '../Announcer.js';
import { Button } from '../components/button.js';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import {
  ItemActions,
  ItemContent,
  ItemMedia,
  ItemTitle,
} from '../components/item.js';
import { DragHandle } from '../DragHandle.js';
import { dragAccessibility } from '../dragAnnounce.js';
import { dropped, type DropOperation } from '../dragDrop.js';
import { IconButton } from '../IconButton.js';
import {
  useViewMessages,
  type MessageFormatters,
} from '../MessagesProvider.js';
import { DropdownMenuContent } from '../popups.js';
import { RowItem } from '../RowItem.js';
import { CompactSelect } from './CompactSelect.js';
import { useListFocus, type ListFocus } from './listFocus.js';
import { OptionsSection, type Choice } from './optionControls.js';

/** The two places one drop on the series list is between. */
export interface SeriesDrop {
  from: number;
  to: number;
}

/**
 * The move a finished drag on the series list is, or null.
 *
 * A series is carried under the alias of the metric it draws: a cartesian
 * chart draws one series per metric — that is what "add series" offers and
 * what the list has always been keyed by — so the two ids the drop reports
 * are the two metrics it is between, and no separate identity has to be
 * invented for the library to hold. Whether there was a drop at all is the
 * guard every sortable list here shares ({@link dropped}); what is added on
 * top is that both ids name a series *this* chart draws, since the panel is
 * not the only draggable thing the page holds.
 *
 * It is a function of its own because it is the one decision a pointer makes
 * that jsdom cannot reach — `@dnd-kit/dom` picks its target by measuring
 * boxes, and every box there is 0×0 at the origin.
 */
export function seriesDrop(
  series: readonly CartesianSeries[],
  operation: DropOperation,
  canceled: boolean | undefined,
): SeriesDrop | null {
  const drop = dropped(operation, canceled);
  if (!drop) return null;
  const from = series.findIndex(one => one.metric === drop.source);
  const to = series.findIndex(one => one.metric === drop.target);
  if (from < 0 || to < 0 || from === to) return null;
  return { from, to };
}

/**
 * What a screen reader hears while a series is being carried: the shared
 * wording of a drag ({@link dragAccessibility}) said in this list's own
 * words, with the metric aliases the library is holding turned back into
 * the column titles on screen.
 */
export function seriesDragAccessibility(
  messages: MessageFormatters,
  nameOf: (alias: string) => string,
) {
  return dragAccessibility(
    messages.label('label.chart.series-instructions'),
    {
      picked: name => messages.label('label.chart.series-picked', { name }),
      cancelled: name =>
        messages.label('label.chart.series-cancelled', { name }),
    },
    nameOf,
  );
}

export interface SeriesListProps {
  /** The chart type, which decides whether a series picks its own mark. */
  type: ChartType;
  spec: CartesianSpec;
  /** The metrics the result holds, each named as its column is titled. */
  metrics: readonly Choice[];
  onChange(spec: CartesianSpec): void;
}

/**
 * The 系列 slot of the data page: one row per metric drawn, in the order
 * they are drawn in.
 *
 * **The order is a setting, not an accident.** A stack is read from the
 * bottom up and a legend from the first entry on; which series comes first
 * is therefore something the analyst decides, and the only thing this list
 * changes about a series — `cartesian.series` is reordered, and no member of
 * any entry is touched. It is carried by the same handle the record view's
 * sort editor and the column settings are carried by (`DragHandle`, over
 * `@dnd-kit`), so the arrow keys move a row one place without a pointer and
 * the drag is announced in this package's own words rather than the
 * library's English (`dragAnnounce.ts`).
 *
 * The optimistic plugin is left out exactly as it is in those two lists: it
 * reorders the DOM while the pointer moves, which makes the indexes this
 * list is rendered from stale precisely when the drop is read.
 */
export function SeriesList({ type, spec, metrics, onChange }: SeriesListProps) {
  const messages = useViewMessages();
  // The panel's own live region, alive only while the panel is: it answers
  // the arrow keys pressed inside it, as the sort editor's answers the ones
  // pressed inside its popover (`Announcer.tsx`).
  const { say, region } = useAnnouncer('series-announcement');
  const drawn = new Set(spec.series.map(series => series.metric));
  const undrawn = metrics.filter(metric => !drawn.has(metric.value));
  // A series taken out leaves the keyboard on the series that took its
  // place (`listFocus.ts`). The arrow keys need nothing: a row is keyed by
  // its metric, so a move carries the focused handle's own node with it.
  const focus = useListFocus({
    list: '[data-slot="chart-options-series"]',
    item: '[data-slot="series-card"]',
    add: '[data-slot="add-series"]',
  });
  const nameOf = (alias: string) =>
    metrics.find(metric => metric.value === alias)?.label ?? alias;
  const update = (series: CartesianSeries[]) => onChange({ ...spec, series });
  const patch = (index: number, change: Partial<CartesianSeries>) =>
    update(
      spec.series.map((series, at) =>
        at === index ? { ...series, ...change } : series,
      ),
    );
  /**
   * Commits one move and says where the series landed, for both inputs.
   * Nothing is written for a move that changes nothing — an end of the list
   * is an end, and a redraw that redraws the same picture is a lie told to
   * a reader listening for the landing.
   */
  const moveTo = (from: number, to: number) => {
    const carried = spec.series[from];
    if (!carried || from === to || to < 0 || to >= spec.series.length) return;
    const order = movedTo(spec.series, from, to);
    update(order);
    say(
      messages.label('label.chart.series-moved', {
        name: nameOf(carried.metric),
        index: to + 1,
        total: order.length,
      }),
    );
  };
  return (
    <OptionsSection
      name="series"
      title={messages.label('label.chart.slot.series')}
    >
      <DragDropProvider
        plugins={defaults =>
          defaults.map(plugin =>
            plugin === Accessibility
              ? Accessibility.configure(
                  seriesDragAccessibility(messages, nameOf),
                )
              : plugin,
          )
        }
        onDragEnd={({ operation, canceled }) => {
          const drop = seriesDrop(spec.series, operation, canceled);
          if (drop) moveTo(drop.from, drop.to);
        }}
      >
        <ul
          data-slot="series-list"
          aria-label={messages.label('label.chart.slot.series')}
          className="flex flex-col gap-1"
        >
          {spec.series.map((series, index) => (
            <SeriesRow
              key={series.metric}
              type={type}
              series={series}
              index={index}
              total={spec.series.length}
              name={nameOf(series.metric)}
              focus={focus}
              onPatch={change => patch(index, change)}
              onRemove={() =>
                update(spec.series.filter((_series, at) => at !== index))
              }
              onMove={step => moveTo(index, index + step)}
            />
          ))}
        </ul>
      </DragDropProvider>

      {/* One voice for the move the arrow keys make; the library announces
          its own pick-up and cancel. */}
      {region}

      {/* A split chart draws one metric: the pivot is its series. */}
      {spec.splitBy === undefined && (
        <DropdownMenu>
          <DropdownMenuTrigger
            render={
              <Button
                variant="ghost"
                size="sm"
                disabled={undrawn.length === 0}
                data-slot="add-series"
                className="self-start"
              />
            }
          >
            <PlusIcon data-icon="inline-start" />
            {messages.label('label.chart.add-series')}
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start">
            <DropdownMenuGroup>
              {undrawn.map(metric => (
                <DropdownMenuItem
                  key={metric.value}
                  onClick={() =>
                    update([
                      ...spec.series,
                      type === 'combo'
                        ? { metric: metric.value, type: 'bar' }
                        : { metric: metric.value },
                    ])
                  }
                >
                  {metric.label}
                </DropdownMenuItem>
              ))}
            </DropdownMenuGroup>
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </OptionsSection>
  );
}

/** One series: its handle, its name, how it is drawn, and its way out. */
function SeriesRow({
  type,
  series,
  index,
  total,
  name,
  focus,
  onPatch,
  onRemove,
  onMove,
}: {
  type: ChartType;
  series: CartesianSeries;
  index: number;
  /** How many series there are; a chart of one has no order to change. */
  total: number;
  name: string;
  /** Where the keyboard goes when this row is the one removed. */
  focus: ListFocus;
  onPatch(change: Partial<CartesianSeries>): void;
  onRemove(): void;
  /** Moves this series one place, from the arrow keys on its handle. */
  onMove(step: -1 | 1): void;
}) {
  const messages = useViewMessages();
  const { ref, handleRef, isDragging } = useSortable({
    id: series.metric,
    index,
    plugins: defaults =>
      defaults.filter(plugin => plugin !== OptimisticSortingPlugin),
  });
  return (
    <RowItem
      render={<li />}
      ref={ref}
      variant="outline"
      density="dense"
      data-slot="series-card"
      data-metric={series.metric}
      data-dragging={isDragging ? '' : undefined}
    >
      <ItemMedia>
        <DragHandle
          ref={handleRef}
          label={messages.label('label.chart.drag-series', { name })}
          dragging={isDragging}
          // One series is first and last at once: a handle that can only put
          // it back where it is says it can do something it cannot.
          disabled={total < 2}
          onMove={onMove}
        />
      </ItemMedia>

      <ItemContent className="min-w-0">
        {/* The row is one line and a long metric name is cut off in it, so
            the whole of it is on the element for a pointer to read. */}
        <ItemTitle className="max-w-full font-medium" title={name}>
          {name}
        </ItemTitle>
      </ItemContent>

      <ItemActions className="gap-1">
        {type === 'combo' && (
          <CompactSelect
            label={messages.label('label.chart.mark-of', { name })}
            items={(['bar', 'line', 'area'] as const).map(mark => ({
              value: mark,
              label: messages.label(`label.chart.mark.${mark}`),
            }))}
            value={series.type ?? 'bar'}
            onChange={next => onPatch({ type: next })}
          />
        )}
        <CompactSelect
          label={messages.label('label.chart.axis-of', { name })}
          items={(['left', 'right'] as const).map(axis => ({
            value: axis,
            label: messages.label(`label.chart.axis.${axis}`),
          }))}
          value={series.axis ?? 'left'}
          onChange={axis => onPatch({ axis })}
        />
        <IconButton
          label={messages.label('label.chart.remove-series', { name })}
          variant="ghost"
          size="icon-xs"
          disabled={total <= 1}
          onClick={event => {
            focus.removing(event, index);
            onRemove();
          }}
        >
          <XIcon />
        </IconButton>
      </ItemActions>
    </RowItem>
  );
}
