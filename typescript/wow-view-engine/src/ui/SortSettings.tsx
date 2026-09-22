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
import {
  ArrowDownIcon,
  ArrowDownUpIcon,
  ArrowUpIcon,
  PlusIcon,
  XIcon,
} from 'lucide-react';
import type {
  FieldDefinition,
  FieldGroupDefinition,
  RecordSort,
  SortDirection,
} from '../model/index.js';
import { Button } from './components/button.js';
import { Empty, EmptyDescription, EmptyHeader } from './components/empty.js';
import {
  ItemActions,
  ItemContent,
  ItemMedia,
  ItemTitle,
} from './components/item.js';
import { RowItem } from './RowItem.js';
import { DragHandle } from './DragHandle.js';
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
import { ToolbarItem } from './toolbar.js';
import { useAnnouncer } from './Announcer.js';
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

/**
 * The name of the toolbar's sort button, or nothing when its own words are
 * already the whole of it.
 *
 * Once something is sorted the button reads the sort back — "Amount",
 * an arrow, "+1" — and a reader who arrives at it is told a field name and
 * a direction with no idea what the control in front of them *is*. Nothing
 * on it says "sort": the word is what it wears only while nothing is
 * sorted. So a sort in force gives it a name of its own
 * (`label.sort.button`), which is the visible text with that word in front
 * of it — the same string said two ways, never two names (D12).
 *
 * The count comes after it in the button's own wording, the way
 * `label.sort.at` is appended in the editor: what is on screen and what is
 * read out then hold the same three pieces, which is what WCAG 2.5.3 asks
 * of a name that is not the visible text verbatim.
 */
function sortButtonName(
  sort: readonly RecordSort[],
  labelOf: (field: string) => string,
  messages: MessageFormatters,
): string | undefined {
  const first = sort[0];
  if (!first) return undefined;
  const name = messages.label('label.sort.button', {
    field: labelOf(first.field),
    direction: messages.label(DIRECTION_LABEL[directionOf(first.direction)]),
  });
  const more = sort.length - 1;
  return more === 0
    ? name
    : `${name} ${messages.label('label.sort.more', { count: more })}`;
}

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
    announce(
      messages.label('label.sort.moved', {
        field: named(sortEntryId(from)),
        index: to + 1,
        total: order.length,
      }),
    );
  };

  return (
    <Popover>
      {/* A toolbar item where a toolbar is around it, an ordinary button
          anywhere else: the bar owns the roving focus order and this is one
          of the stops in it. */}
      <ToolbarItem
        render={
          <PopoverTrigger
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
        <SortSummary sort={table.sort} labelOf={labelOf} messages={messages} />
      </ToolbarItem>
      <PopoverContent align="end" className="w-80">
        <PopoverHeader>
          <PopoverTitle>{messages.label('label.sort.title')}</PopoverTitle>
          <PopoverDescription>
            {messages.label('label.sort.hint')}
          </PopoverDescription>
        </PopoverHeader>

        {/* A ceiling, not an empty state and not a callout: it is one line
            about the control below it, and it appears exactly when that
            control has gone quiet. `Alert` is for something that has
            happened — inside a popover this small it would be a bordered,
            icon-bearing box taking more room than the list it is about. */}
        {full && (
          <p className="text-muted-foreground">
            {messages.label('label.sort.full', {
              max: table.maxSortFields,
            })}
          </p>
        )}

        {table.sort.length === 0 ? (
          // Nothing here yet, which is what `Empty` is for: the same shape
          // the result block and the view list use when they have nothing
          // to show, rather than a third way of saying it.
          <Empty data-slot="sort-unsorted" className="p-0">
            <EmptyHeader>
              <EmptyDescription>
                {messages.label('label.sort.unsorted')}
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
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
      <ItemMedia className="gap-1">
        <DragHandle
          ref={handleRef}
          label={messages.label('label.sort.drag', { field: label })}
          dragging={isDragging}
          // A single entry is already first and last at once: a handle that
          // can only put it back where it is says it can do something it
          // cannot.
          disabled={total < 2}
          onMove={onMove}
        />
        <span className={cn('text-muted-foreground w-4 text-center', TEXT_UI)}>
          {index + 1}
        </span>
      </ItemMedia>

      <ItemContent className="min-w-0">
        <ItemTitle className="max-w-full">{label}</ItemTitle>
      </ItemContent>

      <ItemActions className="gap-1">
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

/**
 * The sort on the button: the first field and its direction, plus how many
 * more there are. The whole list would not fit, and the first field is the
 * one the rows are actually in the order of.
 *
 * **One arrow, after the name.** The button used to lead with a neutral
 * `↕` as well — the control's own glyph — and then draw the direction after
 * the field, so a sorted table read `↕ 金额 ↓`: two arrows 48px apart with
 * the word they both talk about between them, and the first of them saying
 * nothing the second did not. The `↕` is now what the button wears while
 * nothing is sorted and the direction replaces it once something is, so
 * there is one mark in one place and it always means the same thing. After
 * the name rather than before it, which is where a table header puts it
 * (`SortableHeader`): the bar's button and the column it is talking about
 * then say the sort the same way round.
 *
 * It takes the button's trailing icon slot (`data-icon="inline-end"`) only
 * when it really is what ends the button — that slot tightens the padding on
 * its side, and with more than one field sorted the count follows the mark,
 * which makes the mark inline content rather than an affix.
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
  if (!first)
    return (
      <>
        {messages.label('label.sort.title')}
        <ArrowDownUpIcon data-slot="sort-available" data-icon="inline-end" />
      </>
    );
  const direction = directionOf(first.direction);
  const more = sort.length - 1;
  return (
    <>
      {labelOf(first.field)}
      <DirectionMark
        direction={direction}
        {...(more === 0 ? ({ icon: 'inline-end' } as const) : {})}
      />
      {/* The direction used to be spelled out here for a reader, because the
          arrow is a picture. It is in the button's own name now
          ({@link sortButtonName}) along with the word "sort" the arrow never
          said either, and a name is what a reader hears: a second copy in
          the content would be read by nobody and maintained by everybody. */}
      {more > 0 && (
        // No `text-muted-foreground`: this sits inside the bar's outline
        // button, where that grey lands at 4.34:1 — under the 4.5:1 axe
        // asks for — and the button has a foreground of its own to inherit
        // (`EditorBand` records the same call for its mode word). It stays
        // secondary by being a count after a name, not by being paler.
        <span>{messages.label('label.sort.more', { count: more })}</span>
      )}
    </>
  );
}

/**
 * Which way one field goes, as the arrow for it.
 *
 * `icon` is the button affix the arrow is — the registry sizes and spaces an
 * icon by that slot — and it is left out where the arrow is inline content
 * rather than an affix: on the trigger with several fields sorted, the count
 * follows the arrow and the trailing slot's tighter padding would be a lie.
 */
function DirectionMark({
  direction,
  icon,
}: {
  direction: SortDirection;
  icon?: 'inline-start' | 'inline-end';
}) {
  const Arrow = direction === 'ASC' ? ArrowUpIcon : ArrowDownIcon;
  return <Arrow data-slot="sort-direction" data-icon={icon} />;
}

/** Flips one entry, reading an unreadable direction as ascending first. */
function flip(entry: RecordSort): RecordSort {
  return {
    field: entry.field,
    direction: directionOf(entry.direction) === 'ASC' ? 'DESC' : 'ASC',
  };
}
