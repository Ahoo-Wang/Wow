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
import { Badge } from '../components/badge.js';
import { Button } from '../components/button.js';
import { Spinner } from '../components/spinner.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import { ToneBadge } from '../kit/variants.js';
import { CardSummaries } from './CardSummaries.js';
import { EmptyResult } from './EmptyResult.js';
import type { EmptyWayOut } from './emptyWayOut.js';
import { useOpenRows } from './openRows.js';
import { RecordCard } from './RecordCard.js';
import type { RecordCell } from './RecordTable.js';
import { SkeletonCards } from './SkeletonCards.js';

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
  if (table.status === 'loading' && table.rows.length === 0)
    return <SkeletonCards fields={table.card.fields.length} />;
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
      className="fve:bg-canvas fve:relative fve:flex fve:items-start fve:gap-3 fve:overflow-x-auto fve:p-3"
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
      className="fve:flex fve:w-72 fve:shrink-0 fve:flex-col fve:gap-2"
    >
      <div
        data-slot="kanban-lane-header"
        className="fve:flex fve:items-center fve:gap-2"
      >
        {lane.value === null ? (
          <Badge variant="outline">{name}</Badge>
        ) : (
          <ToneBadge tone={lane.tone ?? 'neutral'}>{name}</ToneBadge>
        )}
        {/* Said in the lane's name already; drawn for the eye alone. */}
        <span
          data-slot="kanban-lane-count"
          aria-hidden="true"
          className="fve:text-muted-foreground fve:text-sm fve:tabular-nums"
        >
          {count}
        </span>
      </div>
      {summaries && <CardSummaries rows={[summaries]} />}
      {shown === 0 ? (
        <p
          data-slot="kanban-lane-empty"
          className="fve:text-muted-foreground fve:text-sm"
        >
          {messages.label('label.kanban.empty-lane')}
        </p>
      ) : (
        <div
          ref={list}
          data-slot="kanban-lane-cards"
          className="fve:flex fve:flex-col fve:gap-2"
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
          {messages.label('label.kanban.load-more')}
        </Button>
      )}
    </section>
  );
}
