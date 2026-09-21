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

import { useState, type KeyboardEvent } from 'react';
import { DragDropProvider } from '@dnd-kit/react';
import { useSortable } from '@dnd-kit/react/sortable';
import { Accessibility } from '@dnd-kit/dom';
import { OptimisticSortingPlugin } from '@dnd-kit/dom/sortable';
import {
  ArrowDownIcon,
  ArrowDownUpIcon,
  ArrowUpIcon,
  GripVerticalIcon,
  PlusIcon,
  XIcon,
} from 'lucide-react';
import type {
  FieldDefinition,
  FieldGroupDefinition,
  RecordSort,
  SortDirection,
} from '../model/index.js';
import type { RecordTableController } from '../react/index.js';
import { Button } from './components/button.js';
import { IconButton } from './IconButton.js';
import {
  Popover,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from './components/popover.js';
import {
  DropdownMenu,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from './components/dropdown-menu.js';
import { DropdownMenuContent, PopoverContent } from './popups.js';
import { GroupedMenu } from './FieldMenu.js';
import {
  reorderSort,
  sortDrop,
  sortDragAccessibility,
  sortEntryId,
  sortEntryIndex,
} from './sort/drag.js';
import type { MessageKey } from './messages.js';
import { useViewMessages, type MessageFormatters } from './MessagesProvider.js';
import { TEXT_UI } from './layout.js';
import { cn } from 'cn';

/**
 * Wording per direction. A stored config is untrusted data — `validateShape`
 * only asks a sort entry for a `field` — so an entry may arrive with no
 * direction at all, or with `up`. That reaches this render before the user
 * can fix it, and an unknown key here used to become `undefined` on the way
 * into `messages.label`, which throws: the whole workbench went down where a
 * fixable finding belonged. `validateRecord` now reports the direction, and
 * this reads it back defensively anyway, because being the second line of
 * defence is the only way a render is allowed to depend on validation.
 */
const DIRECTION_LABEL: Record<SortDirection, MessageKey> = {
  ASC: 'label.sort.asc',
  DESC: 'label.sort.desc',
};

function directionOf(direction: SortDirection | undefined): SortDirection {
  return direction === 'DESC' ? 'DESC' : 'ASC';
}

export interface SortSettingsProps {
  table: RecordTableController;
  /** The fields the definition offers; only sortable ones are listed. */
  fields: readonly FieldDefinition[];
  /** The picker groups of the definition the fields come from. */
  fieldGroups?: readonly FieldGroupDefinition[];
}

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
}: SortSettingsProps) {
  const messages = useViewMessages();
  const [announcement, setAnnouncement] = useState('');
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
  const labels = new Map(fields.map(field => [field.name, field.label]));
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
    setAnnouncement(
      messages.label('label.sort.moved', {
        field: named(sortEntryId(from)),
        index: to + 1,
        total: order.length,
      }),
    );
  };

  return (
    <Popover>
      <PopoverTrigger
        data-control="sort"
        // Bordered like every other function on the bar (D12 Ⅳ), and the one
        // that keeps its words: what it says is the sort in force.
        render={<Button variant="outline" size="sm" />}
      >
        <ArrowDownUpIcon data-icon="inline-start" />
        <SortSummary sort={table.sort} labelOf={labelOf} messages={messages} />
      </PopoverTrigger>
      <PopoverContent align="end" className="w-80">
        <PopoverHeader>
          <PopoverTitle>{messages.label('label.sort.title')}</PopoverTitle>
          <PopoverDescription>
            {messages.label('label.sort.hint')}
          </PopoverDescription>
        </PopoverHeader>

        {full && (
          <p className="text-muted-foreground">
            {messages.label('label.sort.full', {
              max: table.maxSortFields,
            })}
          </p>
        )}

        {table.sort.length === 0 ? (
          <p className="text-muted-foreground">
            {messages.label('label.sort.unsorted')}
          </p>
        ) : (
          <DragDropProvider
            plugins={defaults =>
              defaults.map(plugin =>
                plugin === Accessibility
                  ? Accessibility.configure(
                      sortDragAccessibility(messages, named),
                    )
                  : plugin,
              )
            }
            onDragEnd={({ operation, canceled }) => {
              const drop = sortDrop(operation, canceled);
              if (drop) moveTo(drop.from, drop.to);
            }}
          >
            <ul
              data-slot="sort-entries"
              aria-label={messages.label('label.sort.title')}
              className="flex flex-col gap-1"
            >
              {table.sort.map((entry, index) => (
                <SortEntry
                  // Keyed by its place as well as its field: a config that
                  // sorts twice by one field is two entries, and removing one
                  // of them has to leave the other where it is.
                  key={`${entry.field}-${index}`}
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
                  onMove={step => moveTo(index, index + step)}
                />
              ))}
            </ul>
          </DragDropProvider>
        )}

        {/* One voice for a move the user asked for with the arrow keys; the
            library announces its own pick-up and cancel. */}
        <div
          data-slot="sort-announcement"
          role="status"
          aria-live="polite"
          className="sr-only"
        >
          {announcement}
        </div>

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
            {messages.label('label.sort.add')}
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
                      { field: field.name, direction: 'ASC' },
                    ])
                  }
                >
                  {field.label}
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
  /** Moves this entry one place, from the arrow keys on its handle. */
  onMove(step: -1 | 1): void;
}) {
  const messages = useViewMessages();
  const direction = directionOf(entry.direction);
  const { ref, handleRef, isDragging } = useSortable({
    id: sortEntryId(index),
    index,
    plugins: defaults =>
      defaults.filter(plugin => plugin !== OptimisticSortingPlugin),
  });
  return (
    <li
      ref={ref}
      data-slot="sort-entry"
      data-field={entry.field}
      data-dragging={isDragging ? '' : undefined}
      className="flex items-center gap-1.5 rounded-md data-dragging:bg-muted"
    >
      <IconButton
        ref={handleRef}
        type="button"
        label={messages.label('label.sort.drag', { field: label })}
        // Not while the entry is in the air — see `ColumnRow`, which carries
        // the same handle for the same reason.
        silent={isDragging}
        variant="ghost"
        size="icon-xs"
        className="cursor-grab"
        // A single entry is already first and last at once: a handle that
        // can only put it back where it is says it can do something it
        // cannot.
        disabled={total < 2}
        onKeyDown={(event: KeyboardEvent) => {
          // While the library is carrying the entry the arrows are its: two
          // handlers on one press would move it twice.
          if (isDragging) return;
          const step = STEP[event.key];
          if (!step) return;
          event.preventDefault();
          onMove(step);
        }}
      >
        <GripVerticalIcon />
      </IconButton>
      <span className={cn('text-muted-foreground w-4 text-center', TEXT_UI)}>
        {index + 1}
      </span>
      <span className="flex-1 truncate">{label}</span>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        aria-label={messages.label('label.sort.direction', { field: label })}
        onClick={onFlip}
      >
        <DirectionMark direction={direction} />
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
    </li>
  );
}

/** Arrow keys that move an entry, and how far. */
const STEP: Record<string, -1 | 1 | undefined> = {
  ArrowUp: -1,
  ArrowDown: 1,
};

/**
 * The sort on the button: the first field and its direction, plus how many
 * more there are. The whole list would not fit, and the first field is the
 * one the rows are actually in the order of.
 */
function SortSummary({
  sort,
  labelOf,
  messages,
}: {
  sort: readonly RecordSort[];
  labelOf(field: string): string;
  messages: MessageFormatters;
}) {
  const first = sort[0];
  if (!first) return <>{messages.label('label.sort.title')}</>;
  const direction = directionOf(first.direction);
  return (
    <>
      {labelOf(first.field)}
      <DirectionMark direction={direction} />
      <span className="sr-only">
        {messages.label(DIRECTION_LABEL[direction])}
      </span>
      {sort.length > 1 && (
        <span className="text-muted-foreground">
          {messages.label('label.sort.more', { count: sort.length - 1 })}
        </span>
      )}
    </>
  );
}

function DirectionMark({ direction }: { direction: SortDirection }) {
  return direction === 'ASC' ? <ArrowUpIcon /> : <ArrowDownIcon />;
}

/** Flips one entry, reading an unreadable direction as ascending first. */
function flip(entry: RecordSort): RecordSort {
  return {
    field: entry.field,
    direction: directionOf(entry.direction) === 'ASC' ? 'DESC' : 'ASC',
  };
}
