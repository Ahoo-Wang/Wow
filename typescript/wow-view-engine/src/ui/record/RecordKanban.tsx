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

import type * as React from 'react';
import { useId, useRef } from 'react';
import type {
  RecordKanbanController,
  RecordTableController,
} from '../../react/index.js';
import type { RecordLaneView, RecordRow } from '../../record/index.js';
import { Button } from '../components/button.js';
import { Skeleton } from '../components/skeleton.js';
import { Spinner } from '../components/spinner.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import { EmptyResult } from './EmptyResult.js';
import type { EmptyWayOut } from './emptyWayOut.js';
import { useOpenRows } from './openRows.js';
import { RecordCard } from './RecordCard.js';
import type { RecordCell } from './RecordTable.js';
import { summaryText } from './summaryText.js';
import { summaryFunctionKey } from '../kit/display.js';
import { useSurfaceDisplay } from '../kit/ViewSurface.js';
import { cn } from 'cn';
import { TEXT_UI } from '../kit/layout.js';
import type { SummaryRow } from '../../record/index.js';
import type { FieldOption, FieldTone } from '../../model/index.js';
import { CARD_LIFT } from '../kit/variants.js';
import { Card, CardContent, CardHeader } from '../components/card.js';

export interface RecordKanbanProps {
  /** The cards, rows and status: the same controller the cards draw from. */
  table: RecordTableController;
  /** The lanes (`useRecordKanban`). */
  kanban: RecordKanbanController;
  /** Renders one value of a card; see `RecordCardsProps.renderCell`. */
  renderCell?(cell: RecordCell): React.ReactNode;
  /** What a host offers on one card, at its foot. */
  rowActions?(row: RecordRow): React.ReactNode;
  /** Opens a card's detail: a press, or Enter/Space on the card. */
  onOpen?(row: RecordRow): void;
  /** The empty result's wording, when the host has its own. */
  emptyTitle?: string;
  emptyDescription?: string;
  /** Which way out an empty result offers; see `RecordTable`. */
  emptyWayOut?: EmptyWayOut;
  /** The one way out of an empty result; see `EmptyResult`. */
  onEmptyAction?(): void;
}

/**
 * The result as lanes, one per stage (D83): the lane field's options in the
 * definition's order, each a named region holding its cards — the very
 * cards the card layout draws — with its count, its summaries and, while it
 * has more, a「加载更多」of its own.
 *
 * The lanes stand in one row that scrolls sideways, at every width: wrapped,
 * a stage would no longer stand beside the one before it. An empty stage is
 * drawn and says so — that it is empty is the reading — while the「其他」lane
 * is drawn only when it holds a card, since it is no stage.
 *
 * It keeps the cards' three gates, and is read-only: no pick boxes, no
 * moving a card between lanes.
 */
export function RecordKanban({
  table,
  kanban,
  renderCell,
  rowActions,
  onOpen,
  emptyTitle,
  emptyDescription,
  emptyWayOut = 'add',
  onEmptyAction,
}: RecordKanbanProps) {
  if (!table.hasResult && table.status !== 'loading') return null;
  // The lanes it will draw, already: the draft says which they are, so the
  // answer lands in the shape that is waiting for it rather than in a grid.
  if (table.status === 'loading' && table.rows.length === 0)
    return (
      <KanbanSkeleton
        lanes={shownLanes(kanban)}
        fields={table.card.fields.length}
      />
    );
  if (table.status === 'success' && table.rows.length === 0)
    return (
      <EmptyResult
        title={emptyTitle}
        description={emptyDescription}
        wayOut={emptyWayOut}
        onAction={onEmptyAction}
      />
    );

  return (
    <div
      data-slot="record-kanban"
      // On a narrow screen a swipe comes to rest on a lane, not across two.
      className="fve:bg-canvas fve:relative fve:flex fve:snap-x fve:snap-proximity fve:scroll-px-3 fve:gap-3 fve:overflow-x-auto fve:p-3"
    >
      {kanban.lanes
        .filter(lane => lane.value !== null || lane.rows.length > 0)
        .map(lane => (
          <KanbanLane
            key={lane.id}
            lane={lane}
            table={table}
            loading={kanban.loadingLane === lane.id}
            onLoadMore={() => kanban.loadLane(lane.id)}
            renderCell={renderCell}
            rowActions={rowActions}
            onOpen={onOpen}
          />
        ))}
    </div>
  );
}

function KanbanLane({
  lane,
  table,
  loading,
  onLoadMore,
  renderCell,
  rowActions,
  onOpen,
}: {
  lane: RecordLaneView;
  table: RecordTableController;
  loading: boolean;
  onLoadMore(): void;
  renderCell: RecordKanbanProps['renderCell'];
  rowActions: RecordKanbanProps['rowActions'];
  onOpen: RecordKanbanProps['onOpen'];
}) {
  const messages = useViewMessages();
  // A lane is read top to bottom: ↑ and ↓ move along its cards.
  const list = useRef<HTMLDivElement>(null);
  const opening = useOpenRows(list, onOpen, 'row');
  const rangeId = useId();
  const name =
    lane.value === null
      ? messages.label('label.kanban.other')
      : messages.say(lane.label);
  const shown = lane.rows.length;
  // The count read off the whole lane, or — where nothing counted it — the
  // cards loaded, with a「+」while there are more.
  const count =
    lane.total !== null
      ? String(lane.total)
      : lane.next
        ? messages.label('label.kanban.more', { count: shown })
        : String(shown);
  const summaries = lane.summaries;

  return (
    <section
      data-slot="kanban-lane"
      data-lane={lane.id}
      aria-label={
        lane.total !== null
          ? messages.label('label.kanban.lane', {
              lane: name,
              count: lane.total,
            })
          : messages.label('label.kanban.lane-loaded', {
              lane: name,
              count: shown,
            })
      }
      // A column of its own: the lanes stand as tall as the board, and each
      // scrolls its own cards under a header that stays. Drawn by an edge,
      // not a fill: the cards stand on the canvas as they do in the card
      // layout, and a fill that was below them in light mode rose above
      // them in dark (0.35 against the cards' 0.29), sinking every card.
      className="fve:border-border fve:flex fve:min-h-0 fve:w-72 fve:shrink-0 fve:snap-start fve:flex-col fve:gap-2 fve:rounded-lg fve:border fve:p-2"
    >
      <div
        data-slot="kanban-lane-header"
        className="fve:border-border fve:flex fve:flex-col fve:gap-1 fve:border-b fve:px-1 fve:pb-2"
      >
        <div className="fve:flex fve:items-center fve:gap-2">
          <LaneTitle
            name={name}
            tone={lane.value === null ? null : (lane.tone ?? 'neutral')}
          />
          {/* Said in the lane's name already; drawn for the eye alone, at
              the lane's right edge so the counts line up across lanes. */}
          <span
            data-slot="kanban-lane-count"
            aria-hidden="true"
            className="fve:text-muted-foreground fve:ml-auto fve:text-sm fve:tabular-nums"
          >
            {count}
          </span>
        </div>
        {summaries && <LaneSummaries row={summaries} />}
      </div>
      {shown === 0 ? (
        <p
          data-slot="kanban-lane-empty"
          className="fve:text-muted-foreground fve:px-1 fve:text-sm"
        >
          {messages.label('label.kanban.empty-lane')}
        </p>
      ) : (
        // Room for a card's focus ring inside the scroll port that clips it.
        <div
          ref={list}
          data-slot="kanban-lane-cards"
          className="fve:relative fve:-m-1 fve:flex fve:min-h-0 fve:flex-1 fve:flex-col fve:gap-2 fve:overflow-y-auto fve:p-1 fve:*:shrink-0"
        >
          {lane.rows.map(row => (
            <RecordCard
              key={String(row.key)}
              table={table}
              row={row}
              renderCell={renderCell}
              selectable={false}
              rangeId={rangeId}
              rowActions={rowActions}
              openable={onOpen !== undefined}
              rowProps={opening.row(row)}
            />
          ))}
        </div>
      )}
      {opening.hintId && (
        <span id={opening.hintId} className="fve:sr-only">
          {messages.label('label.record.detail.hint')}
        </span>
      )}
      {lane.next && (
        <Button
          variant="ghost"
          size="sm"
          data-slot="kanban-load-more"
          disabled={loading}
          onClick={onLoadMore}
        >
          {loading && <Spinner data-icon="inline-start" />}
          {/* How many are still to come, where the lane was counted. */}
          {lane.total !== null && lane.total > shown
            ? messages.label('label.kanban.load-more-rest', {
                count: lane.total - shown,
              })
            : messages.label('label.kanban.load-more')}
        </Button>
      )}
    </section>
  );
}

/**
 * What says which stage a lane is: the stage's name as the lane's title,
 * led by a dot in its tone (`null` for the「其他」lane, which is no stage and
 * takes a ring). A title rather than a badge: a badge is the language of a
 * value in a row — small, filled, edged — and as the head of a column it
 * made the one word the lane is about the weakest thing in it.
 */
function LaneTitle({ name, tone }: { name: string; tone: FieldTone | null }) {
  return (
    <span
      data-slot="kanban-lane-title"
      {...(tone ? { 'data-tone': tone } : {})}
      className="fve:flex fve:min-w-0 fve:items-center fve:gap-2"
    >
      <span
        aria-hidden="true"
        className={cn(
          'fve:size-2 fve:shrink-0 fve:rounded-full',
          tone
            ? ['fve:bg-current', DOT[tone]]
            : 'fve:border-muted-foreground fve:border',
        )}
      />
      <span title={name} className="fve:truncate fve:text-sm fve:font-semibold">
        {name}
      </span>
    </span>
  );
}

/** A stage's dot, in the colour its badge wears elsewhere (`ToneBadge`). */
const DOT: Record<FieldTone, string> = {
  neutral: 'fve:text-muted-foreground',
  success: 'fve:text-success',
  warning: 'fve:text-warning',
  danger: 'fve:text-destructive',
};

/**
 * A lane's summaries, a line each: what the number is on the left, in the
 * words the summary row's hover uses (「预计金额的总和」), and the number on
 * the right, where the eye looks for it and where it lines up lane after
 * lane. No scope word — a lane's numbers are only ever the whole lane's.
 */
function LaneSummaries({ row }: { row: SummaryRow }) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  if (row.cells.length === 0) return null;
  return (
    <dl data-slot="kanban-lane-summaries" className="fve:flex fve:flex-col">
      {row.cells.map(cell => (
        <div
          key={`${cell.field}:${cell.fn}`}
          className="fve:flex fve:items-baseline fve:justify-between fve:gap-2"
        >
          <dt className={cn('fve:text-quiet-foreground fve:truncate', TEXT_UI)}>
            {messages.label('label.summary.of', {
              fn: messages.label(summaryFunctionKey(cell.fn, cell.cell)),
              field: messages.say(cell.label),
            })}
          </dt>
          <dd
            data-slot="summary-value"
            className="fve:text-sm fve:font-medium fve:whitespace-nowrap fve:tabular-nums"
          >
            {summaryText(cell, messages, display)}
          </dd>
        </div>
      ))}
    </dl>
  );
}

/** The lanes the draft shows, in order: its lane field's options, less the folded. */
function shownLanes(kanban: RecordKanbanController): FieldOption[] {
  const hidden = new Set<unknown>(kanban.spec?.hidden ?? []);
  return kanban.laneOptions
    .filter(option => !hidden.has(option.value))
    .slice(0, kanban.maxLanes);
}

/**
 * A first load: the lanes it will draw, each with its badge and two cards'
 * worth of bars — one per field a card holds, as `SkeletonCards` draws them.
 */
function KanbanSkeleton({
  lanes,
  fields,
}: {
  lanes: readonly FieldOption[];
  fields: number;
}) {
  const messages = useViewMessages();
  const lines = Array.from({ length: Math.max(fields, 1) }, (_u, i) => i);
  return (
    <div
      data-slot="record-kanban-skeleton"
      aria-hidden
      className="fve:bg-canvas fve:relative fve:flex fve:gap-3 fve:overflow-x-hidden fve:p-3"
    >
      {lanes.map(option => (
        <div
          key={String(option.value)}
          className="fve:border-border fve:flex fve:w-72 fve:shrink-0 fve:flex-col fve:gap-2 fve:rounded-lg fve:border fve:p-2"
        >
          <div className="fve:px-1">
            <LaneTitle
              name={messages.say(option.label)}
              tone={option.tone ?? 'neutral'}
            />
          </div>
          {[0, 1].map(card => (
            <Card key={card} className={CARD_LIFT}>
              <CardHeader>
                <Skeleton className="fve:h-5 fve:w-1/2" />
              </CardHeader>
              <CardContent className="fve:flex fve:flex-col fve:gap-2">
                {lines.map(line => (
                  <Skeleton key={line} className="fve:h-4 fve:w-full" />
                ))}
              </CardContent>
            </Card>
          ))}
        </div>
      ))}
    </div>
  );
}
