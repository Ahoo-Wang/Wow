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

import { useId, type KeyboardEvent } from 'react';
import { useSortable } from '@dnd-kit/react/sortable';
import { OptimisticSortingPlugin } from '@dnd-kit/dom/sortable';
import { CircleSlashIcon, GripVerticalIcon, PinIcon } from 'lucide-react';
import { columnPin, type SummaryFunction } from '../../model/index.js';
import { IconButton } from '../IconButton.js';
import { Checkbox } from '../components/checkbox.js';
import { FieldDescription } from '../components/field.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../components/select.js';
import { SelectContent } from '../popups.js';
import { useViewMessages } from '../MessagesProvider.js';
import { ACTIONS_COLUMN, type ColumnSettingRow } from './rows.js';
import { TEXT_UI } from '../layout.js';
import { cn } from 'cn';

/** The value the summary select carries for "summarise nothing". */
const NO_SUMMARY = 'none';

/**
 * Wording per pin state.
 *
 * Read through `columnPin` rather than indexed with whatever the row holds:
 * a stored `pinned: 'top'` used to reach this as a key the catalogue has
 * never heard of, hand `undefined` to `messages.label`, and take the
 * workbench down from inside a popover. `validateRecord` reports the value
 * so it can be fixed; this renders it as "not pinned" in the meantime,
 * because a render may lean on validation only while it is also the second
 * line of defence.
 */
const PIN_LABEL = {
  left: 'label.columns.pin.left',
  right: 'label.columns.pin.right',
  none: 'label.columns.pin.none',
} as const;

/**
 * The sentence a row draws under itself, per reason, and whether it is
 * drawn or only read out.
 *
 * `unknown` and `last-visible` are on screen: the first is what tells a
 * reader that this row is not like the others — the grey it used to be
 * drawn in was the only difference, which is no difference at all to anyone
 * who cannot tell those two greys apart — and the second names the one
 * checkbox that has just refused. `hidden` is read out only: every switched
 * off column would draw it, so a panel with five of them would say the same
 * sentence five times, while its controls already show that they are off.
 */
const NOTES = {
  unknown: { key: 'label.columns.unknown', shown: true },
  // The same leftover said in the words that are true of it: this one is
  // not a column at all, so "this column is not in the data" would name
  // something the reader cannot find in the table either way.
  'summary-unknown': { key: 'label.columns.summary-unknown', shown: true },
  'last-visible': { key: 'label.columns.last-visible', shown: true },
  hidden: { key: 'label.columns.hidden', shown: false },
} as const;

type NoteKind = keyof typeof NOTES;

export interface ColumnRowProps {
  row: ColumnSettingRow;
  /** How many columns the table shows; the last one may not be hidden. */
  shownCount: number;
  onToggle(): void;
  onPin(): void;
  onSummary(fn: SummaryFunction | null): void;
  /** Moves the row one place, from the arrow keys on its handle. */
  onMove(step: -1 | 1): void;
  /** True while the library is carrying this row, so the arrows are its. */
  dragging?: boolean;
  elementRef?(element: HTMLElement | null): void;
  handleRef?(element: HTMLElement | null): void;
}

/**
 * One column in the settings: drag handle, visibility, name, summary, pin.
 *
 * The two columns the definition places — the row key and the host's action
 * column — show their state and disable their controls rather than leaving
 * them out: a row that is missing its pin toggle reads as an oversight,
 * while one that shows a pin it cannot change says who decides. Neither
 * carries a sentence about itself: the pin toggle's own name says which
 * side it is held on, and the area it is listed in is named too.
 *
 * Every other reason a control here is refused is written on the row that
 * owns it. A rule belongs to the thing it governs — collected at the top of
 * the panel, five of them were a paragraph the reader had to match against
 * the row in front of them.
 */
export function ColumnRow({
  row,
  shownCount,
  onToggle,
  onPin,
  onSummary,
  onMove,
  dragging,
  elementRef,
  handleRef,
}: ColumnRowProps) {
  const messages = useViewMessages();
  const noteId = useId();
  const actions = row.field === ACTIONS_COLUMN;
  const label = actions ? messages.label('label.toolbar.actions') : row.label;
  // On a summary-only row the checkbox does not show or hide a column —
  // there is no column — so it is named after the one thing it holds.
  const toggleLabel = messages.label(
    row.summaryOnly ? 'label.columns.keep-summary' : 'label.columns.show',
    { field: label },
  );
  // The table has to keep one column: hiding the last one leaves a result
  // with nothing in it and no way back except the picker that emptied it.
  // The guard keeps a table from being left with nothing in it. A broken
  // column puts nothing in it either, so it is never the last one worth
  // keeping — guarding it would lock the one control that repairs it. The
  // action column is refused for being the host's rather than for being the
  // last, so the sentence about the last one is not its.
  const last = row.visible && !row.broken && !actions && shownCount <= 1;
  // Why the pin and the summary on this row are refused, when they are —
  // the one reason both share, since both want a column that is shown and
  // can render. The handle takes it too when it is refused, which is when
  // the row has no place in the order at all: a column switched off keeps
  // one, so it is dragged like any other and says nothing about it.
  const refused: NoteKind | null = row.broken
    ? row.summaryOnly
      ? 'summary-unknown'
      : 'unknown'
    : row.visible
      ? null
      : 'hidden';
  const note: NoteKind | null = refused ?? (last ? 'last-visible' : null);
  const pinned = columnPin(row.pinned);
  const pinState = messages.label(PIN_LABEL[pinned ?? 'none']);
  // What the select may be set to: what the field declares, plus whatever
  // the config already says if the definition has stopped declaring it. A
  // field that lost its summary capabilities leaves a config the kernel
  // refuses (`record.summary.unsupported`) — which blocks the query and the
  // save — and the one control that could take it back was the one that
  // stopped rendering. Offering its own value back, even unsupported, is
  // what makes "no summary" reachable.
  const functions =
    row.summary !== null && !row.functions.includes(row.summary)
      ? [...row.functions, row.summary]
      : row.functions;

  return (
    <li
      ref={elementRef}
      data-slot="column-setting"
      data-field={row.field}
      data-region={row.region}
      data-broken={row.broken ? '' : undefined}
      data-dragging={dragging ? '' : undefined}
      className="flex flex-col rounded-md px-1 py-0.5 data-broken:text-muted-foreground data-dragging:bg-muted"
    >
      <div className="flex items-center gap-1.5">
        <IconButton
          ref={handleRef}
          type="button"
          label={messages.label('label.columns.drag', { field: label })}
          // Not while the row is in the air: the tooltip would follow the
          // pointer across the list it is meant to be dropping into. Focus
          // still opens it, which is where the name earns its keep — the
          // arrow keys this handle answers are written nowhere else.
          silent={dragging}
          variant="ghost"
          size="icon-xs"
          className="cursor-grab"
          disabled={!row.movable}
          aria-describedby={refused && !row.movable ? noteId : undefined}
          onKeyDown={(event: KeyboardEvent) => {
            // While the library is carrying the row the arrows are its: two
            // handlers on one press would move the column twice.
            if (dragging) return;
            const step = STEP[event.key];
            if (!step) return;
            event.preventDefault();
            onMove(step);
          }}
        >
          <GripVerticalIcon />
        </IconButton>

        <Checkbox
          checked={row.visible}
          disabled={actions || last}
          aria-label={toggleLabel}
          // The checkbox on a broken row is enabled and is the repair, so it
          // takes the same sentence for the opposite reason: not why it is
          // refused, but why it is the one control worth pressing here.
          aria-describedby={row.broken || last ? noteId : undefined}
          onCheckedChange={onToggle}
        />

        <span className="flex-1 truncate">{label}</span>

        {functions.length > 0 && (
          <Select
            disabled={!row.visible || row.broken}
            items={[
              {
                value: NO_SUMMARY,
                label: messages.label('label.summary.fn.none'),
              },
              ...functions.map(fn => ({
                value: fn,
                label: messages.label(`label.summary.fn.${fn}`),
              })),
            ]}
            value={row.summary ?? NO_SUMMARY}
            onValueChange={(value: string | null) =>
              // Matched against the list the control was built from rather
              // than cast: anything else is not an answer it offered.
              onSummary(functions.find(fn => fn === value) ?? null)
            }
          >
            <SelectTrigger
              size="sm"
              className="w-28"
              aria-label={messages.label('label.columns.summary', {
                field: label,
              })}
              aria-describedby={refused ? noteId : undefined}
            >
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                <SelectItem value={NO_SUMMARY}>
                  {messages.label('label.summary.fn.none')}
                </SelectItem>
                {functions.map(fn => (
                  <SelectItem key={fn} value={fn}>
                    {messages.label(`label.summary.fn.${fn}`)}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        )}

        {/* A pin is where a column is held while the rest scrolls, and a
          column the table does not draw is held nowhere: the area it would
          move to shows nothing of it, and `config.summaries` has no cell to
          fill under a column that is not there either. Showing it first is
          the move, so both controls say they cannot rather than writing
          something no reader can see. The handle is not like them any more —
          a switched-off column keeps its place in the order, so it has an
          order to drag. A broken column answers none of the three: its one
          control is the checkbox that takes it out. */}
        {/* The state travels in the name — "Pinning of Amount: Pinned
            left" — and not as `aria-pressed`, which has two values where
            this control cycles through three (`nextPin`: none → left →
            right). "Pressed" would say that the column is held without
            saying which end it is held at, which is less than the name
            already says, and saying both would be the same fact twice. */}
        <IconButton
          type="button"
          label={messages.label('label.columns.pin', {
            field: label,
            state: pinState,
          })}
          variant="ghost"
          size="icon-xs"
          disabled={row.fixed || !row.visible || row.broken}
          aria-describedby={refused ? noteId : undefined}
          onClick={onPin}
        >
          <PinIcon
            data-pinned={pinned ?? undefined}
            className={cn(
              'text-muted-foreground',
              // Pinned is a filled pin in the row's own ink; unpinned is the
              // outline, quiet.
              pinned !== null && 'fill-current text-foreground',
            )}
          />
        </IconButton>
      </div>

      {note && (
        // The sentence a control on this row is described by, so it is the
        // field description it has always been in everything but name. The
        // `id` stays: `aria-describedby` is what ties it to whichever of the
        // four controls the reason belongs to, and a description that names
        // its controls cannot be a `FieldLabel` — one label cannot be shared
        // by four controls, which is why the `<span>` above stays a `<span>`
        // and each control carries its own `aria-label`.
        <FieldDescription
          id={noteId}
          data-slot="column-note"
          data-note={note}
          className={cn(
            // No `text-muted-foreground`: `FieldDescription` already is it.
            NOTES[note].shown
              ? ['flex items-start gap-1 pl-8', TEXT_UI]
              : 'sr-only',
          )}
        >
          {row.broken && (
            // Marked as well as said, and not by colour: the icon is the
            // difference a reader sees before reading anything, and it is
            // `aria-hidden` because the sentence beside it is the one thing
            // a reader hears.
            <CircleSlashIcon aria-hidden className="mt-0.5 size-3 shrink-0" />
          )}
          {messages.label(NOTES[note].key)}
        </FieldDescription>
      )}
    </li>
  );
}

/** Arrow keys that move a row, and how far. */
const STEP: Record<string, -1 | 1 | undefined> = {
  ArrowUp: -1,
  ArrowDown: 1,
};

/**
 * A column that can be dragged, wired to the library.
 *
 * Only movable rows become sortable items, which is how an area keeps its
 * columns: a fixed row is not a drop target at all, so nothing can be
 * carried past the key column or behind the actions.
 *
 * The optimistic plugin is left out on purpose. It reorders the DOM while
 * the pointer moves, which makes the indexes this component is rendered
 * from stale exactly when the drop is read; without it the library still
 * draws the drag preview, and the committed order is computed from the two
 * ids the drop reports.
 */
export function SortableColumnRow(
  props: ColumnRowProps & { index: number; group: string },
) {
  const { index, group, ...rest } = props;
  const { ref, handleRef, isDragging } = useSortable({
    id: rest.row.field,
    index,
    group,
    plugins: defaults =>
      defaults.filter(plugin => plugin !== OptimisticSortingPlugin),
  });

  return (
    <ColumnRow
      {...rest}
      dragging={isDragging}
      elementRef={ref}
      handleRef={handleRef}
    />
  );
}
