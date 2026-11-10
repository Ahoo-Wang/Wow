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

import type { ReactElement } from 'react';
import { DragDropProvider } from '@dnd-kit/react';
import { useSortable } from '@dnd-kit/react/sortable';
import { PlusIcon, XIcon } from 'lucide-react';
import type {
  FieldDefinition,
  FieldGroupDefinition,
  RecordSort,
} from '../../model/index.js';
import { SortDirections, readsAsNames } from '../../model/index.js';
import { Button } from '../components/button.js';
import { Empty, EmptyDescription, EmptyHeader } from '../components/empty.js';
import {
  ItemActions,
  ItemContent,
  ItemMedia,
  ItemTitle,
} from '../components/item.js';
import { RowItem } from '../kit/RowItem.js';
import { DragHandle, moveTarget, type HandleMove } from '../kit/DragHandle.js';
import { sortableList, withoutOptimisticSorting } from '../kit/dragPlugins.js';
import { IconButton } from '../kit/IconButton.js';
import {
  Popover,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from '../components/popover.js';
import {
  DropdownMenu,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import { DropdownMenuContent, PopoverContent } from '../kit/popups.js';
import { GroupedMenu } from '../kit/FieldMenu.js';
import {
  reorderSort,
  sortDrop,
  sortDragAccessibility,
  sortEntryId,
  sortEntryIndex,
} from './drag.js';
import {
  DIRECTION_LABEL,
  DirectionMark,
  SortSummary,
  directionOf,
  sortButtonName,
} from './SortSummary.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import { TEXT_UI } from '../kit/layout.js';
import {
  ToolbarItem,
  libraryPopover,
  type DetachedPopover,
} from '../kit/toolbar.js';
import { useAnnouncer } from '../kit/Announcer.js';
import { cn } from 'cn';

/**
 * What the editor needs of whoever owns the sort: the record table, or the
 * analysis editor with its aliases standing in for fields.
 */
export interface SortOwner {
  sort: RecordSort[];
  setSort(sort: RecordSort[]): void;
  maxSortFields: number;
}

export interface SortSettingsProps {
  table: SortOwner;
  /** The fields the definition offers; only sortable ones are listed. */
  fields: readonly FieldDefinition[];
  /** The picker groups of the definition the fields come from. */
  fieldGroups?: readonly FieldGroupDefinition[];
  /**
   * What is being ordered, which decides the words (the 2026-09-23 audit,
   * P2-3). `'records'` — the record toolbar — orders rows by fields, and
   * the unsorted button says 「排序」 because nothing else names it.
   * `'groups'` — the analysis tray — orders an answer's groups by its
   * dimensions and metrics, and the control sits under its own 「排序」
   * title: the button then says the state, 「未排序」, rather than the
   * title again, and the editor speaks of dimensions and metrics.
   */
  of?: 'records' | 'groups';
  /**
   * The control that opens it, where the toolbar's is not on screen: the
   * error strip's worded button for a config that will not run (as
   * `ColumnSettings`' `trigger`). Left out, the toolbar's own button.
   */
  trigger?: ReactElement<Record<string, unknown>>;
  /** One half of it, as `ColumnSettings`' `detached`. */
  detached?: DetachedPopover;
}

/** The words that differ between ordering rows and ordering groups. */
const WORDS = {
  records: {
    none: 'label.sort.title',
    hint: 'label.sort.hint',
    unsorted: 'label.sort.unsorted',
    add: 'label.sort.add',
    full: 'label.sort.full',
  },
  groups: {
    none: 'label.sort.groups.none',
    hint: 'label.sort.groups.hint',
    unsorted: 'label.sort.groups.unsorted',
    add: 'label.sort.groups.add',
    full: 'label.sort.groups.full',
  },
} as const;

/**
 * What the rows are ordered by, said on the button and edited behind it.
 *
 * The header's own toggle orders by one column at a time and cannot say
 * which of several comes first. This does both: the button reads the sort
 * back in words, and the editor behind it shows each field with its
 * direction and its place in the priority.
 */
export function SortSettings({
  table,
  fields,
  fieldGroups,
  of = 'records',
  trigger,
  detached,
}: SortSettingsProps) {
  const messages = useViewMessages();
  const words = WORDS[of];
  const { say: announce, region: announcement } =
    useAnnouncer('sort-announcement');
  const sortable = fields.filter(field => field.sortable === true);
  // Nothing to offer *and* nothing to take back: a button that opens an
  // empty editor leads nowhere. A definition that stopped declaring a field
  // sortable while a saved config still orders by it is the other case —
  // `validateRecord` refuses the draft and the workbench says so, and
  // hiding the control here would leave the user reading an error whose one
  // cause is behind a door that is no longer there.
  if (sortable.length === 0 && table.sort.length === 0) return null;

  const used = new Set(table.sort.map(entry => entry.field));
  // A cursor view sorts on at most so many fields, and `validateRecord`
  // refuses a longer sort outright: `apply` would not run, the rows would
  // keep the order they had, and the view would sit in an error the user was
  // invited into. So the picker stops where the kernel starts refusing.
  const full = table.sort.length >= table.maxSortFields;
  // What is left to offer. Counted rather than compared against the whole
  // list: a definition that stopped declaring a field sortable leaves a
  // sort on it that is used and no longer offered, and "as many used as
  // there are" would then read as "still something to add".
  const available = sortable.filter(field => !used.has(field.name));
  // A reference read as names sorts by its stored id, and its
  // name in the list says so, as its header does.
  const labels = new Map(
    fields.map(field => {
      const said = messages.say(field.label);
      return [
        field.name,
        field.sortable === true && readsAsNames(field)
          ? messages.label('label.sort.by-id-field', { field: said })
          : said,
      ];
    }),
  );
  const labelOf = (field: string) => labels.get(field) ?? field;
  /**
   * The field an id names: the one place where what the library is carrying
   * — a position in this list — becomes a word the reader is looking at.
   * Both voices go through it, the library's and the editor's own.
   */
  const named = (id: string) => {
    const at = sortEntryIndex(id);
    const entry = at === null ? undefined : table.sort[at];
    return entry === undefined ? id : labelOf(entry.field);
  };
  /**
   * Commits one move and says where the entry landed, for both inputs.
   *
   * The whole order goes through `setSort` — one `edit` and one `apply`,
   * the same write a flipped direction or a removed entry makes. Which
   * field comes first is not a different kind of edit from those, and the
   * rows on screen were projected from the config that ran: an order that
   * is not applied is an order nobody can see.
   */
  const moveTo = (from: number, to: number) => {
    const order = reorderSort(table.sort, from, to);
    if (!order) return;
    table.setSort(order);
    announce(
      messages.label('label.sort.moved', {
        field: named(sortEntryId(from)),
        index: to + 1,
        total: order.length,
      }),
    );
  };

  const handle = libraryPopover(detached?.handle);
  const opener = (
    <>
      {/* A toolbar item where a toolbar is around it, an ordinary button
          anywhere else: the bar owns the roving focus order and this is one
          of the stops in it. */}
      {trigger ? (
        <PopoverTrigger handle={handle} data-control="sort" render={trigger} />
      ) : (
        <ToolbarItem
          render={
            <PopoverTrigger
              handle={handle}
              data-control="sort"
              // Bordered like every other function on the bar (D12 Ⅳ), and the
              // one that keeps its words: what it says is the sort in force.
              // Which is also why it needs a name once something is sorted —
              // "Amount, descending" is a sort, and never says so.
              aria-label={sortButtonName(table.sort, labelOf, messages)}
              render={<Button variant="outline" size="sm" />}
            />
          }
        >
          <SortSummary
            sort={table.sort}
            labelOf={labelOf}
            messages={messages}
            none={messages.label(words.none)}
          />
        </ToolbarItem>
      )}
    </>
  );
  if (detached?.part === 'trigger') return opener;

  return (
    <Popover handle={handle}>
      {detached ? null : opener}
      <PopoverContent align="end" className="fve:w-80">
        <PopoverHeader>
          <PopoverTitle>{messages.label('label.sort.title')}</PopoverTitle>
          <PopoverDescription>{messages.label(words.hint)}</PopoverDescription>
        </PopoverHeader>

        {/* A ceiling, not an empty state and not a callout: it is one line
            about the control below it, and it appears exactly when that
            control has gone quiet. `Alert` is for something that has
            happened — inside a popover this small it would be a bordered,
            icon-bearing box taking more room than the list it is about. */}
        {full && (
          <p className="fve:text-muted-foreground">
            {messages.label(words.full, {
              max: table.maxSortFields,
            })}
          </p>
        )}

        {table.sort.length === 0 ? (
          // Nothing here yet, which is what `Empty` is for: the same shape
          // the result block and the view list use when they have nothing
          // to show, rather than a third way of saying it.
          <Empty data-slot="sort-unsorted" className="fve:p-0">
            <EmptyHeader>
              <EmptyDescription>
                {messages.label(words.unsorted)}
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        ) : (
          <DragDropProvider
            {...sortableList(sortDragAccessibility(messages, named))}
            onDragEnd={({ operation, canceled }) => {
              const drop = sortDrop(operation, canceled);
              if (drop) moveTo(drop.from, drop.to);
            }}
          >
            <ul
              data-slot="sort-entries"
              aria-label={messages.label('label.sort.title')}
              className="fve:flex fve:flex-col fve:gap-1"
            >
              {table.sort.map((entry, index) => (
                <SortEntry
                  // Keyed by its field and which of that field's entries it
                  // is: a config that sorts twice by one field is two
                  // entries, and removing one of them has to leave the other
                  // where it is — while a move keeps the row, so its handle
                  // keeps the keyboard and its menu, as every list does.
                  key={`${entry.field}-${occurrence(table.sort, index)}`}
                  entry={entry}
                  index={index}
                  total={table.sort.length}
                  label={labelOf(entry.field)}
                  onFlip={() =>
                    table.setSort(
                      table.sort.map((other, at) =>
                        at === index ? flip(other) : other,
                      ),
                    )
                  }
                  onRemove={() =>
                    table.setSort(
                      table.sort.filter((_other, at) => at !== index),
                    )
                  }
                  onMove={move =>
                    moveTo(index, moveTarget(move, index, table.sort.length))
                  }
                />
              ))}
            </ul>
          </DragDropProvider>
        )}

        {/* One voice for a move the user asked for with the arrow keys; the
            library announces its own pick-up and cancel. */}
        {announcement}

        {/* A new field joins at the end, ascending: it breaks the ties of
            the fields already there, and anywhere else would quietly change
            what the rows are mainly ordered by. */}
        <DropdownMenu>
          <DropdownMenuTrigger
            render={
              <Button
                variant="outline"
                size="sm"
                disabled={full || available.length === 0}
              />
            }
          >
            <PlusIcon data-icon="inline-start" />
            {messages.label(words.add)}
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start">
            <GroupedMenu
              items={available}
              groups={fieldGroups ?? []}
              itemKey={field => field.name}
              render={field => (
                <DropdownMenuItem
                  key={field.name}
                  onClick={() =>
                    table.setSort([
                      ...table.sort,
                      { field: field.name, direction: SortDirections.ASC },
                    ])
                  }
                >
                  {labelOf(field.name)}
                </DropdownMenuItem>
              )}
            />
          </DropdownMenuContent>
        </DropdownMenu>
      </PopoverContent>
    </Popover>
  );
}

/**
 * One sort entry: its handle, its place, its field, its direction and its
 * way out.
 *
 * The number is drawn from the entry's place in the list rather than stored,
 * so a move renumbers everything below it without anything having to say so.
 *
 * The optimistic plugin is left out on purpose, as it is in the column
 * settings: it reorders the DOM while the pointer moves, which makes the
 * indexes this list is rendered from stale exactly when the drop is read.
 * Without it the library still draws the drag preview, and the committed
 * order is computed from the two ids the drop reports.
 */
function SortEntry({
  entry,
  index,
  total,
  label,
  onFlip,
  onRemove,
  onMove,
}: {
  entry: RecordSort;
  index: number;
  /** How many entries there are; a list of one has no order to change. */
  total: number;
  label: string;
  onFlip(): void;
  onRemove(): void;
  /** Moves this entry, from the arrow keys on its handle or its menu. */
  onMove(move: HandleMove): void;
}) {
  const messages = useViewMessages();
  const direction = directionOf(entry.direction);
  const { ref, handleRef, isDragging } = useSortable({
    id: sortEntryId(index),
    index,
    plugins: withoutOptimisticSorting,
  });
  return (
    <RowItem
      render={<li />}
      ref={ref}
      density="dense"
      data-slot="sort-entry"
      data-field={entry.field}
      data-dragging={isDragging ? '' : undefined}
    >
      {/* The handle and the place it holds are one group: where this entry
          sits and how to move it are the same subject. */}
      <ItemMedia className="fve:gap-1">
        <DragHandle
          ref={handleRef}
          label={messages.label('label.sort.drag', { field: label })}
          index={index}
          total={total}
          dragging={isDragging}
          // A single entry is already first and last at once: a handle that
          // can only put it back where it is says it can do something it
          // cannot.
          disabled={total < 2}
          onMove={onMove}
        />
        <span
          className={cn(
            'fve:text-muted-foreground fve:w-4 fve:text-center',
            TEXT_UI,
          )}
        >
          {index + 1}
        </span>
      </ItemMedia>

      <ItemContent className="fve:min-w-0">
        <ItemTitle className="fve:max-w-full">{label}</ItemTitle>
      </ItemContent>

      <ItemActions className="fve:gap-1">
        <Button
          type="button"
          variant="ghost"
          size="sm"
          aria-label={messages.label('label.sort.direction', { field: label })}
          onClick={onFlip}
        >
          <DirectionMark direction={direction} icon="inline-start" />
          {messages.label(DIRECTION_LABEL[direction])}
        </Button>
        <IconButton
          type="button"
          label={messages.label('label.sort.none', { field: label })}
          variant="ghost"
          size="icon-xs"
          onClick={onRemove}
        >
          <XIcon />
        </IconButton>
      </ItemActions>
    </RowItem>
  );
}

/** Flips one entry, reading an unreadable direction as ascending first. */
function flip(entry: RecordSort): RecordSort {
  return {
    field: entry.field,
    direction:
      directionOf(entry.direction) === SortDirections.ASC
        ? SortDirections.DESC
        : SortDirections.ASC,
  };
}

/** Which of its field's entries the one at `index` is, counting from 0. */
function occurrence(sort: readonly RecordSort[], index: number): number {
  const field = sort[index]?.field;
  return sort.slice(0, index).filter(entry => entry.field === field).length;
}
