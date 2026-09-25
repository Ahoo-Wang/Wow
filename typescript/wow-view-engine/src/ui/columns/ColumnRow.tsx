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

import { useId } from 'react';
import { useSortable } from '@dnd-kit/react/sortable';
import { CircleSlashIcon, PinIcon } from 'lucide-react';
import type { SummaryFunction } from '../../model/index.js';
import { summaryFunctionKey } from '../display.js';
import { DragHandle } from '../DragHandle.js';
import { withoutOptimisticSorting } from '../dragPlugins.js';
import { IconButton } from '../IconButton.js';
import type { MessageFormatters } from '../MessagesProvider.js';
import { Checkbox } from '../components/checkbox.js';
import { FieldDescription } from '../components/field.js';
import {
  ItemActions,
  ItemContent,
  ItemMedia,
  ItemTitle,
} from '../components/item.js';
import { RowItem } from '../RowItem.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../components/select.js';
import { SelectContent } from '../popups.js';
import { useViewMessages } from '../MessagesProvider.js';
import type { ColumnSettingRow } from './rows.js';
import { TEXT_UI } from '../layout.js';
import { cn } from 'cn';

/** The value the summary select carries for "summarise nothing". */
const NO_SUMMARY = 'none';

/**
 * What the pin toggle changed, per state it lands in.
 *
 * Its own wording rather than the two words the areas are headed with
 * (`label.columns.pin.left` / `pin.none`, in `ColumnSettings`): a heading
 * names a list, while an announcement reports a change that has already
 * happened and has to say so in its own right — a live region that suddenly
 * says "Pinned left" names neither the column nor the fact that anything
 * moved.
 */
const PIN_ANNOUNCEMENT = {
  on: 'label.columns.pinned.left',
  off: 'label.columns.pinned.none',
} as const;

/**
 * What a reader hears once a column has been pinned or let go.
 *
 * `aria-pressed` reports the toggle's new state, and a reader hears it the
 * moment they press — but only as one word ("pressed"), which names neither
 * the column nor the edge it is now held against. So the panel's one live
 * region says the sentence as well (`ColumnSettings`), which is also where
 * the next state is decided, so the wording is written once.
 */
export function pinAnnouncement(
  messages: MessageFormatters,
  field: string,
  pinned: boolean,
): string {
  return messages.label(PIN_ANNOUNCEMENT[pinned ? 'on' : 'off'], { field });
}

/**
 * The sentence a row draws under itself, per reason, and whether it is
 * drawn or only read out.
 *
 * `unknown` and `primary-required` are on screen: the first is what tells a
 * reader that this row is not like the others — the grey it used to be
 * drawn in was the only difference, which is no difference at all to anyone
 * who cannot tell those two greys apart — and the second says why the one
 * checkbox that is never offered is off. `hidden` is read out only: every switched
 * off column would draw it, so a panel with five of them would say the same
 * sentence five times, while its controls already show that they are off.
 */
const NOTES = {
  unknown: { key: 'label.columns.unknown', shown: true },
  // The same leftover said in the words that are true of it: this one is
  // not a column at all, so "this column is not in the data" would name
  // something the reader cannot find in the table either way.
  'summary-unknown': { key: 'label.columns.summary-unknown', shown: true },
  // The key's checkbox is off for good, and the sentence says why once,
  // on the one row that carries it.
  'primary-required': { key: 'label.columns.primary-required', shown: true },
  hidden: { key: 'label.columns.hidden', shown: false },
} as const;

type NoteKind = keyof typeof NOTES;

export interface ColumnRowProps {
  row: ColumnSettingRow;
  onToggle(): void;
  onPin(): void;
  /**
   * Whether the cap (D17-4) is not drawing this row's pin right now. The
   * config still holds it and the switch still says so; the row adds that
   * it is let go, or the screen shows a pin with no edge under it.
   */
  released?: boolean;
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
 * The one column the definition places — the row key — shows its state and
 * disables its controls rather than leaving them out: a row that is missing
 * its pin toggle reads as an oversight, while one that shows a pin it
 * cannot change says who decides. It carries no sentence about the pin: the
 * toggle's own name says that it is held, and the area it is listed in says
 * so again. The host's action column is not a row here at all (D19) — it is
 * a render slot rather than something a config names.
 *
 * Every other reason a control here is refused is written on the row that
 * owns it. A rule belongs to the thing it governs — collected at the top of
 * the panel, five of them were a paragraph the reader had to match against
 * the row in front of them.
 */
export function ColumnRow({
  row,
  onToggle,
  onPin,
  released = false,
  onSummary,
  onMove,
  dragging,
  elementRef,
  handleRef,
}: ColumnRowProps) {
  const messages = useViewMessages();
  const noteId = useId();
  const label = row.label;
  // On a summary-only row the checkbox does not show or hide a column —
  // there is no column — so it is named after the one thing it holds.
  const toggleLabel = messages.label(
    row.summaryOnly ? 'label.columns.keep-summary' : 'label.columns.show',
    { field: label },
  );
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
  const note: NoteKind | null =
    refused ?? (row.primary ? 'primary-required' : null);
  const pinned = row.pinned;
  // The toggle's name: what it holds, and — while the cap has let this pin
  // go — that the table is not drawing it. Whether it is on is
  // `aria-pressed`, so the name says it no more.
  const pinName = messages.label('label.columns.pin', { field: label });
  const pinLabel = released
    ? `${pinName} · ${messages.label('label.columns.pin-released')}`
    : pinName;
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
    <RowItem
      render={<li />}
      ref={elementRef}
      density="dense"
      data-slot="column-setting"
      data-field={row.field}
      data-region={row.region}
      data-broken={row.broken ? '' : undefined}
      data-dragging={dragging ? '' : undefined}
      // `SPACE.WITHIN` between the handle, the checkbox and the name — they
      // are one group, the row's subject — and the row's own `SPACE.GROUPS`
      // before the controls that act on it.
      className="gap-y-0.5"
    >
      <ItemMedia className="gap-1">
        <DragHandle
          ref={handleRef}
          label={messages.label('label.columns.drag', { field: label })}
          dragging={dragging}
          disabled={!row.movable}
          describedBy={refused && !row.movable ? noteId : undefined}
          onMove={onMove}
        />

        <Checkbox
          checked={row.visible}
          // The key stays: it is what says which record a row is (D13), so
          // the projection never draws a table without it and the settings
          // never offer to.
          disabled={row.primary}
          // Said outright because the settings open from the result
          // toolbar: Base UI's toolbar hands its roving-focus context to
          // everything under it, the popup included, and a checkbox that
          // believes it is a toolbar item leaves its tab stop to the bar —
          // which never gives it one. It rendered with no `tabindex` at all,
          // so no column could be shown or hidden from the keyboard (the
          // 2026-09-25 keyboard walkthrough; the root cause is a TODO).
          tabIndex={row.primary ? -1 : 0}
          aria-label={toggleLabel}
          // The checkbox on a broken row is enabled and is the repair, so it
          // takes the same sentence for the opposite reason: not why it is
          // refused, but why it is the one control worth pressing here.
          aria-describedby={row.broken || row.primary ? noteId : undefined}
          onCheckedChange={onToggle}
        />
      </ItemMedia>

      <ItemContent className="min-w-0">
        <ItemTitle className="max-w-full">{label}</ItemTitle>
      </ItemContent>

      <ItemActions>
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
                label: messages.label(summaryFunctionKey(fn, row.cell)),
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
                    {/* Named as this column's own values are named: the
                        earliest of a date column, the smallest of a
                        number one. */}
                    {messages.label(summaryFunctionKey(fn, row.cell))}
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
        {/* Two states, so the state is `aria-pressed` and the name is the
            plain "Pin Amount" a toggle button wears (D12 Ⅳ). It used to
            carry the state instead — "Pinning of Amount: Pinned left" —
            because the control cycled through three, and "pressed" cannot
            say which of two edges a column is held at. There is one edge to
            pin to now (D19), so the platform's own state fits it exactly,
            and a reader hears the change on the press rather than having to
            read the name again. What `aria-pressed` cannot say is that the
            cap is not drawing this pin right now (D17-4), so that clause
            stays in the name where it is the whole of the news. */}
        <IconButton
          type="button"
          label={pinLabel}
          variant="ghost"
          size="icon-xs"
          aria-pressed={pinned}
          disabled={row.primary || !row.visible || row.broken}
          data-released={released || undefined}
          aria-describedby={refused ? noteId : undefined}
          onClick={onPin}
        >
          <PinIcon
            className={cn(
              'text-muted-foreground',
              // Pinned is a filled pin in the row's own ink; unpinned is the
              // outline, quiet. A pin the cap let go is filled — the config
              // holds it — and faded, because the table is not drawing it.
              pinned && 'fill-current text-foreground',
              released && 'opacity-50',
            )}
          />
        </IconButton>
      </ItemActions>

      {note && (
        // The sentence a control on this row is described by, so it is the
        // field description it has always been in everything but name. The
        // `id` stays: `aria-describedby` is what ties it to whichever of the
        // four controls the reason belongs to, and a description that names
        // its controls cannot be a `FieldLabel` — one label cannot be shared
        // by four controls, which is why the name above is an `ItemTitle`
        // rather than a label and each control carries its own `aria-label`.
        <FieldDescription
          id={noteId}
          data-slot="column-note"
          data-note={note}
          className={cn(
            // No `text-muted-foreground`: `FieldDescription` already is it.
            // `basis-full` puts it on a line of its own: the row is one
            // wrapping flex container now, not a column with a row in it.
            NOTES[note].shown
              ? ['flex basis-full items-start gap-1 pl-8', TEXT_UI]
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
    </RowItem>
  );
}

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
    plugins: withoutOptimisticSorting,
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
