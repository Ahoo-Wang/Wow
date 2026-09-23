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

/**
 * The colours and edges a vendored component does not ship, in one place.
 *
 * Two rules of this package meet here and used to contradict each other:
 * `components/**` is vendored and is never edited by hand, and a call site
 * never carries a component's colours in a `className`. What was left was a
 * third path, which D16 ruling 8 chose: a **thin wrapper beside the
 * component** holding a cva of its own. The vendored cva stays untouched and
 * is still composed — the wrapper picks the registry variant its case starts
 * from and adds one layer over it — and the call site passes a variant
 * rather than a colour. Seven cases live here: a badge that has to read as a
 * status, a badge that has to hold a sentence, the answer that carries out a
 * destructive command, the one divider that has to be seen, the controls
 * inside a condition pill, which draw no chrome of their own, one row of
 * records in its three states, and the open view in the sidebar. The one
 * that is not here is `ui/alerts.tsx`, where
 * `LineAlert` does the same thing to `Alert`;
 * a callout has enough of its own to say (the tone's icon, the role it is
 * announced with) to be a file rather than an export here.
 *
 * The result frame's slot recipes are here for the same reason, one step
 * out: they are the chrome one of this package's own frames gives what a
 * *kind* puts inside it, and the frame is shared by three kinds.
 *
 * One kind of class does not belong here: a *layout* class at a call site —
 * a width, a gap, a `justify-start` — is what `className` is for and stays
 * where it is used.
 */

import type * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from 'cn';
import type { FieldTone } from '../model/index.js';
import { AlertDialogAction } from './components/alert-dialog.js';
import { Badge } from './components/badge.js';
import { Button } from './components/button.js';
import { ComboboxChips, ComboboxInput } from './components/combobox.js';
import { Input } from './components/input.js';
import { SelectTrigger } from './components/select.js';
import { Separator } from './components/separator.js';
import { TableRow } from './components/table.js';
import { SPACE, TEXT_UI } from './layout.js';

/**
 * The registry variant each tone starts from.
 *
 * `danger` starts from `destructive` so that everything but the tint and
 * the ink — the focus ring, its dark-theme counterpart — is the registry's
 * own; the others start from `secondary`, whose shape is right and whose
 * fill is replaced below.
 */
const TONE_BASE: Record<FieldTone, 'secondary' | 'destructive'> = {
  neutral: 'secondary',
  success: 'secondary',
  warning: 'secondary',
  danger: 'destructive',
};

/**
 * A toned badge is the **soft** recipe: a 10% tint of the tone, written in
 * the tone itself, with a dot of the same ink before the word and an edge
 * of the tone at 30% (v16 visual draft, the user's 2026-09-23 pick).
 *
 * Soft rather than the solid fill it replaced, because a status column is
 * read down the page and not one cell at a time: a column of solid red read
 * as an alarm on every row, when a failure among failures is the ordinary
 * case. The word carries the status (WCAG 1.4.1); the tint and the dot only
 * group it, so they can afford to be quiet.
 *
 * What the solid recipe was chosen for still has to hold, and the numbers
 * below are what `ToneBadgeInk*` and `BadgesOnRows*` measure on all three
 * grounds a row moves through — at rest, hovered, and selected, where the
 * row takes `--muted` and the tint lands on grey:
 *
 * - **The ink clears 4.5:1.** Light: danger 5.33 / 4.90, success 6.07 /
 *   5.59, warning 6.09 / 5.61 (rest / selected). Dark: success and warning
 *   clear 7:1; danger in its own token measured **3.92:1** on a selected row
 *   at the registry's 20% dark wash — the dark `--destructive` is dimmer
 *   than the other two — so in dark it writes in the token mixed a fifth of
 *   the way to `--foreground` (6.76 / 5.65). A mix and not a second colour,
 *   so a host moving `--fve-dark-destructive` still moves it.
 * - **The badge is still a badge on the row** (≥1.5:1): a 10% tint alone
 *   lands within 1.16–1.22:1 of the row (P-21), which is why the soft
 *   recipe was turned down once. The 30% edge in the tone's own colour is
 *   what answers it — 1.8–2.5:1 across tones, rows and themes — and it is a
 *   line of the badge's own colour, not the grey hairline around a colour
 *   that read as a halo.
 *
 * The tint is said with and without `dark:` because the registry's
 * `destructive` variant says its own twice (`bg-destructive/10` *and*
 * `dark:bg-destructive/20`), and only the unprefixed one is replaced by an
 * unprefixed rule — the dark theme would keep the 20% wash the ink above
 * does not clear.
 *
 * **`neutral` is an edge and nothing else.** A row is a surface that moves
 * under the badge: `bg-muted` once it is selected, the same shade mixed in
 * while it is hovered. The registry's `secondary` fill *is* that shade, so
 * on a selected row the badge measured **1.00:1** against the row under it,
 * in both themes — it stopped being a badge and became a word. `input`
 * rather than `border`, although `border` is what a divider uses: the theme
 * keeps them apart on exactly this question (`styles.css`) — `input` is the
 * edge of a thing rather than a line between things, and it is held at ≥3:1
 * because an unticked checkbox is *only* its ring. Measured on the selected
 * row, `border` comes to **1.155:1**, which is the same disappearance one
 * step slower. It takes no dot: the dot says "this is a status", and a
 * neutral value is a value.
 */
const SOFT =
  "before:size-1.5 before:shrink-0 before:rounded-full before:bg-current before:content-['']";

const toneBadgeVariants = cva('', {
  variants: {
    tone: {
      neutral: 'border-input',
      success: `bg-success/10 text-success border-success/30 ${SOFT}`,
      warning: `bg-warning/10 text-warning border-warning/30 ${SOFT}`,
      danger: `bg-destructive/10 dark:bg-destructive/10 text-destructive dark:text-[color-mix(in_oklab,var(--destructive)_80%,var(--foreground))] border-destructive/30 ${SOFT}`,
    } satisfies Record<FieldTone, string>,
  },
  defaultVariants: { tone: 'neutral' },
});

export interface ToneBadgeProps extends Omit<
  React.ComponentProps<typeof Badge>,
  'variant'
> {
  /**
   * Which status this is. The colour is never the only difference — the
   * label says which status it is — so this is emphasis on a distinction the
   * words already carry.
   */
  tone?: FieldTone;
}

/** A badge in the colour of a field's tone. */
export function ToneBadge({
  tone = 'neutral',
  className,
  ...props
}: ToneBadgeProps) {
  return (
    <Badge
      // Said on the element as well as drawn, so a host can style a tone and
      // a test can read one without matching on colour.
      data-tone={tone}
      variant={TONE_BASE[tone]}
      className={cn(toneBadgeVariants({ tone }), className)}
      {...props}
    />
  );
}

/**
 * A badge holding a sentence rather than a word: it wraps inside its row and
 * grows downwards instead of carrying the row off the edge of the view.
 *
 * The registry's badge is a one-line pill — `h-5`, `whitespace-nowrap`,
 * centred — which is right for "system" or "3 selected" and wrong for the
 * applied-condition bar, where one badge reads out a whole group, conditions
 * joined by its operator. The correction is the vendored component's own
 * shape and not this or that call site's layout, and it was written out three
 * times in one file — once per kind of condition the bar wears — so it is one
 * wrapper here, as the colours are.
 */
export function WrappingBadge({
  className,
  ...props
}: React.ComponentProps<typeof Badge>) {
  return (
    <Badge
      className={cn('h-auto max-w-full text-left whitespace-normal', className)}
      {...props}
    />
  );
}

/**
 * The answer that goes through with a delete, a discard or an overwrite.
 *
 * Same correction as the badge above, for the same reason and against the
 * same token. The registry's `destructive` button variant is a wash —
 * `bg-destructive/10` with `text-destructive` on it — and a wash leaves the
 * hue reading against a surface it has been lightened towards: the delete
 * confirmation's own button measured **3.97:1 at 14px** in the light theme,
 * short of WCAG 1.4.3's 4.5. That variant is the registry's way of drawing a
 * *quiet* destructive control in a row of others; the last answer of an
 * alert dialog is not quiet, and it is body-sized text rather than a 12px
 * badge, so there is nothing to fall back on.
 *
 * So the fill is the token and the ink is the token's own `-foreground`,
 * which is the pairing `styles.css` picked these steps for and which flips
 * with the theme by itself. The fill is said twice, unprefixed and `dark:`,
 * because the variant says its own twice (`bg-destructive/10` *and*
 * `dark:bg-destructive/20`) and only an unprefixed override replaces the
 * unprefixed one — without the second line the dark theme keeps the 20%
 * wash and the correction is true in one theme out of two. The hover step
 * goes the same way. Everything else is still the registry's: the shape, the
 * focus ring and its dark counterpart, which is why this composes
 * `variant="destructive"` rather than replacing it.
 */
const destructiveActionVariants = cva([
  'bg-destructive dark:bg-destructive text-destructive-foreground',
  'hover:bg-destructive/90 dark:hover:bg-destructive/90',
]);

/**
 * The confirming answer of a destructive `AlertDialog`.
 *
 * Every one of them is this component — `DeleteDialog`, `LeaveGuard` and the
 * overwrite side of `ConflictConfirm` — so there is one destructive answer in
 * the package and no call site spells a colour.
 *
 * `data-tone` is how it says so on the element, the way `ToneBadge` and
 * `LineAlert` do: a dialog's suite then asks whether its answer is the
 * destructive one instead of naming the fill, and the fill itself is
 * asserted once, here, in `test/variants.test.tsx`.
 */
export function DestructiveAction({
  className,
  ...props
}: Omit<React.ComponentProps<typeof AlertDialogAction>, 'variant'>) {
  return (
    <AlertDialogAction
      variant="destructive"
      data-tone="danger"
      className={cn(destructiveActionVariants(), className)}
      {...props}
    />
  );
}

/**
 * The one divider on this surface with a boundary to say.
 *
 * Every other line here separates things of the same kind — rows from rows,
 * a menu's groups from each other. The line at the end of the title bar
 * divides two **authorships**: this package's view-level controls on one
 * side, the host's own actions on the other.
 *
 * It is the edge the buttons on both sides of it wear: the registry's own
 * `--border`, and in the dark theme `--input`, exactly as the registry's
 * outline button is bordered (`border-border dark:border-input`) — the
 * story measures the two against each other rather than against a number. An earlier version painted it `--input` (this
 * package's `--input` is a mid grey, not shadcn's near-white) so that it
 * would measure ≥3:1 against the bar, and the result was a dark bar among
 * light-edged buttons: the one line on the row that did not belong to it
 * (user, 2026-09-22). A separator between two bordered groups is read by
 * being *taller than their gaps* — 20px in a 28px row, against the 8px
 * step inside a group — not by being darker than their borders; a
 * non-text contrast floor is for the edges of controls, and this is not
 * one. Spacing instead of a line is still not taken: the bar wraps at
 * narrow widths, and a 16px gap reads as the start of a wrap rather than
 * as a boundary.
 *
 * `data-vertical:self-center` because the registry's vertical separator
 * says `data-vertical:self-stretch`, and a stretched item with a height of
 * its own is not stretched but parked at the cross-start: the 20px line sat
 * against the top of the 28px row (user, 2026-09-22). Under the same
 * variant, so that the merge replaces the registry's word rather than
 * losing to it in the cascade — a plain `self-center` did.
 */
export function SectionDivider({
  className,
  ...props
}: React.ComponentProps<typeof Separator>) {
  return (
    <Separator
      orientation="vertical"
      className={cn('dark:bg-input h-5 data-vertical:self-center', className)}
      {...props}
    />
  );
}

/**
 * Whether a control draws its own box, or leaves it to what holds it.
 *
 * One border per condition (D12): a condition pill *is* the field, and the
 * operator select and the value control inside it draw no edge, no fill and
 * no shadow of their own, showing focus by the ring alone. Before D16 that
 * was written as `[&_[data-slot=input]]:border-transparent …` on the pill,
 * reaching two levels down into two vendored components by the slot names
 * they happen to ship with — the most upstream-fragile line in the package,
 * and silently undone by any registry rename. It is the control's own class
 * now.
 */
const controlChromeVariants = cva('', {
  variants: {
    chrome: {
      box: '',
      none: 'border-transparent bg-transparent shadow-none',
    },
  },
  defaultVariants: { chrome: 'none' },
});

/** What a control inside a pill takes on top of the vendored component's. */
export type ControlChromeProps = VariantProps<typeof controlChromeVariants>;

/**
 * The text box of a condition pill.
 *
 * `chrome="box"` asks for the registry's own edge back, for the one place
 * this control is borrowed outside a pill: the analysis editor's row limit,
 * which stands on a toolbar with nothing around it to be the field.
 */
export function PillInput({
  chrome,
  className,
  ...props
}: React.ComponentProps<typeof Input> & ControlChromeProps) {
  return (
    <Input
      className={cn(controlChromeVariants({ chrome }), className)}
      {...props}
    />
  );
}

/**
 * The select of a condition pill — its operator, and the values that are
 * picked rather than typed.
 *
 * `chrome="box"` is the group block's and/or, which sits on the group's own
 * header rather than inside a pill and is the only edge it has.
 */
/**
 * The chip box of a condition pill: the chosen candidates and the search
 * box among them, borderless like every other control inside a pill.
 */
export function PillChips({
  chrome,
  className,
  ...props
}: React.ComponentProps<typeof ComboboxChips> & ControlChromeProps) {
  return (
    <ComboboxChips
      className={cn(
        'min-h-7 px-1 py-0.5',
        controlChromeVariants({ chrome }),
        className,
      )}
      {...props}
    />
  );
}

/**
 * The text box of a condition pill that also offers a list — a value typed
 * or picked from the field's own values (`inputs/suggested.tsx`). The
 * registry's `ComboboxInput` is an `InputGroup` with an edge of its own; in a
 * pill the edge is the pill's (D12), so it is taken off here as it is off
 * `PillInput`. Its chevron is left off too: a press on the box opens the
 * list already, and the chevron would be one more unnamed Tab stop in every
 * pill — the placeholder says there is something to pick.
 */
export function PillComboboxInput({
  chrome,
  className,
  ...props
}: React.ComponentProps<typeof ComboboxInput> & ControlChromeProps) {
  return (
    <ComboboxInput
      showTrigger={false}
      className={cn(controlChromeVariants({ chrome }), className)}
      {...props}
    />
  );
}

export function PillSelectTrigger({
  chrome,
  className,
  ...props
}: React.ComponentProps<typeof SelectTrigger> & ControlChromeProps) {
  return (
    <SelectTrigger
      className={cn(controlChromeVariants({ chrome }), className)}
      {...props}
    />
  );
}

/**
 * One row of the record table's body, in the three states it has to be told
 * apart in: at rest, under the pointer, and picked.
 *
 * **Why the registry's own three will not do.** A cell of a held column is
 * `bg-inherit` — it takes the row's fill, which is what keeps a frozen
 * column opaque over the columns sliding beneath it (`record/sticky.ts`) —
 * so every one of a row's three fills has to be opaque. The registry hovers
 * to `bg-muted/50`, a 50% wash, and through it the reader saw the scrolling
 * column that the held cell is standing in front of. `--row-hover`
 * (`styles.css`) is that same shade mixed rather than washed, and
 * `has-aria-expanded:` is the row whose menu is open, which the registry
 * washes the same way.
 *
 * **And picked has to beat hovered.** The registry stops at
 * `data-[state=selected]:bg-muted`, which the unqualified `:hover` above
 * then overrode: a selected row lost its tint to the pointer merely passing
 * over it, and with it the only mark saying it was in the selection. The
 * selected hover is said again at the higher specificity, so the pointer
 * changes nothing about a row that is already picked.
 *
 * A `cva` with a base and no axes, like {@link DestructiveAction}'s: the
 * three states are the element's own `data-state` and `:hover`, not
 * something a caller chooses, so the call site passes the state and never a
 * colour (D16-8). Before this, all three fills were a `className` on the
 * vendored `TableRow` at `RecordTable`'s call site, with the hover a
 * constant away in `record/columns.ts` — a colour on a call site and a
 * recipe in two places.
 */
/**
 * The mark on a table row the keyboard is on (the record rows and the
 * analysis result's rows are each one Tab stop, `roving.ts`).
 *
 * Drawn on the row's cells, not the row: a cell paints over its row, and
 * a pinned cell is a layer of its own, so a row's outline is hidden under
 * the very cells it is meant to go round — the browser's own is a faint
 * 1px line nobody sees. Each cell takes the hover fill and an inset edge
 * above and below, the first and the last close the sides, and together
 * they read as one ring round the row.
 */
export const FOCUS_ROW = cn(
  'outline-none',
  'focus-visible:*:bg-row-hover',
  // The ring's colour at the half strength every focus ring here wears
  // (`ring-ring/50`), written out: Tailwind reads class names, not code.
  'focus-visible:*:shadow-[inset_0_2px_0_color-mix(in_oklab,var(--ring)_50%,transparent),inset_0_-2px_0_color-mix(in_oklab,var(--ring)_50%,transparent)]',
  'focus-visible:*:first:shadow-[inset_2px_2px_0_color-mix(in_oklab,var(--ring)_50%,transparent),inset_0_-2px_0_color-mix(in_oklab,var(--ring)_50%,transparent)]',
  'focus-visible:*:last:shadow-[inset_-2px_2px_0_color-mix(in_oklab,var(--ring)_50%,transparent),inset_0_-2px_0_color-mix(in_oklab,var(--ring)_50%,transparent)]',
);

/** The ring on a card the keyboard is on — the one every control wears. */
export const FOCUS_CARD =
  'outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50';

const tableDataRowVariants = cva([
  'bg-background',
  'hover:bg-row-hover has-aria-expanded:bg-row-hover',
  'data-[state=selected]:bg-muted data-[state=selected]:hover:bg-muted',
]);

/**
 * A row of records, coloured by the state it is in.
 *
 * `data-state="selected"` is the whole of what a caller says; the summary
 * band and the header are not rows of this kind and wear the band's own
 * grey instead (`record/sticky.ts`).
 */
export function TableDataRow({
  className,
  ...props
}: React.ComponentProps<typeof TableRow>) {
  return (
    <TableRow
      className={cn(tableDataRowVariants(), FOCUS_ROW, className)}
      {...props}
    />
  );
}

/**
 * One row of the sidebar's list of views.
 *
 * The open one is a sheet of the work area's own ground lifted off the
 * column: `background`, the vendored button's own transparent border painted
 * `border` all the way round, and the registry's `shadow-xs` — not another
 * step of grey. Four states used to
 * share one 3% grey, so the open view and a hovered one painted the same
 * colour and the list had no "you are here" at all; a raised sheet is a
 * different *kind* of mark, and no amount of theming can collapse it into
 * the fill beside it. It replaced a 2px bar of `primary` down the leading
 * edge (2026-09-23): an inset shadow follows the item's rounded corners, so
 * the bar bent into a "(" at both ends, and a border has no end to bend. Hover
 * is a step of the column's own scale in the other direction, which is why
 * it replaces the ghost variant's `muted` — on this ground that one is the
 * ground.
 *
 * `@shadcn/sidebar` ships `SidebarMenuButton` with this state built in and
 * is not taken (D16 ruling 2): it needs `SidebarProvider`, which writes a
 * cookie, registers a global ⌘B and positions `fixed` — a whole-page
 * assumption, and wrong for a surface embedded in someone else's page. What
 * is borrowed instead is its vocabulary: the `--sidebar*` tokens this list
 * is painted in are the registry's own.
 */
const sidebarItemVariants = cva(
  'hover:bg-sidebar-accent hover:text-sidebar-accent-foreground justify-start',
  {
    variants: {
      current: {
        true: 'bg-background text-foreground hover:bg-background hover:text-foreground border-border font-medium shadow-xs',
        false: '',
      },
    },
    defaultVariants: { current: false },
  },
);

export interface SidebarItemProps extends Omit<
  React.ComponentProps<typeof Button>,
  'variant' | 'size'
> {
  /** Whether this is the view on screen. */
  current?: boolean;
}

/** A view in the sidebar's column, open or not. */
export function SidebarItem({
  current = false,
  className,
  ...props
}: SidebarItemProps) {
  return (
    <Button
      variant="ghost"
      size="sm"
      aria-current={current}
      className={cn(sidebarItemVariants({ current }), className)}
      {...props}
    />
  );
}

/**
 * The frame round a result, and the padding it gives the two parts the
 * shell itself puts in it (D12 Ⅳ–Ⅶ).
 *
 * **It is a band, not a card** (2026-09-23, the user's call on the v16
 * review, overriding the earlier "keep the result's border"): one rule
 * across its top, and its sides and bottom are the work column's own edges
 * — it bleeds through `main`'s 16px padding (`-mx-4 -mb-4`, the same bleed
 * the opening skeleton's title rule uses). The card was there to hold the
 * toolbar, the rows and the pagination together while the block could end
 * mid-page; since the workbench fills its host and the block reaches the
 * bottom (`styles.css`, "A workbench fills its container"), its bottom and side edges only redrew the
 * workbench's own, one frame inside another, and cost 32px of width and
 * 16px of height the rows could have. The rule on top is the one line still
 * saying something: where the conditions end and the result begins.
 *
 * The frame has no padding, no ground and no row gap of its own: the rows
 * have to run to its edge, so the space inside belongs to each part — the
 * toolbar is its first row, ruled off, at the column's 16px so its controls
 * line up with the conditions above; a callout landing under that keeps a
 * margin of its own. Those two are `WorkbenchShell`'s own slots
 * (`toolbar`, `strips`), named the same whichever kind is open, so they
 * stay with the frame.
 *
 * What does **not** stay with it is one kind's furniture. Until the
 * 2026-09-22 review all five recipes sat in `ResultBlock` as descendant
 * selectors, and three of them named slots only a record view has —
 * `record-pagination`, `record-empty`, `record-cards`. D18-1 merges the
 * record and analysis workbenches behind one shell in phase 2 and that
 * shell is meant to grow no `if` per kind, so those three moved out to
 * `RESULT_SLOTS` below and each kind now hands the frame the recipes its
 * own `result` slot needs (`ResultBlock.slots`).
 */
export const resultFrameChrome =
  'border-border -mx-4 -mb-4 overflow-hidden border-t ' +
  '[&>[data-slot=result-toolbar]]:border-border [&>[data-slot=result-toolbar]]:border-b [&>[data-slot=result-toolbar]]:px-4 [&>[data-slot=result-toolbar]]:py-2 ' +
  '[&>[data-slot=status-strip]]:mx-4 [&>[data-slot=status-strip]]:my-3';

/**
 * What a kind's own parts wear inside that frame, one recipe per part.
 *
 * Each is a literal class string rather than a part composed with a slot
 * name, because Tailwind reads the source: a variant built at runtime is a
 * variant no stylesheet has.
 */
const RESULT_SLOTS = {
  /**
   * The frame's last row — ruled off above and on its own grey, so how
   * many rows there are and how to reach the next of them read as the
   * frame's footer rather than as one more row of the result: the record
   * view's pagination, and the analysis result's 「正在显示 N 组，耗时 X
   * 秒」. A kind that grows another names it here, beside these.
   */
  caption:
    '[&>[data-slot=record-pagination]]:border-border [&>[data-slot=record-pagination]]:bg-muted/40 [&>[data-slot=record-pagination]]:border-t [&>[data-slot=record-pagination]]:px-4 [&>[data-slot=record-pagination]]:py-2 ' +
    '[&>[data-slot=analysis-caption]]:border-border [&>[data-slot=analysis-caption]]:bg-muted/40 [&>[data-slot=analysis-caption]]:border-t [&>[data-slot=analysis-caption]]:px-4 [&>[data-slot=analysis-caption]]:py-2',
  /** A query that matched nothing, in air of its own rather than to the edge. */
  empty: '[&>[data-slot=record-empty]]:my-6',
  /** Cards keep the column's padding; a table is what runs to the edge. */
  cards: '[&>[data-slot=record-cards]]:p-4',
  /** A host command's line sits where the query strip does, and as it does. */
  bulk: '[&>[data-slot=bulk-status]]:mx-4 [&>[data-slot=bulk-status]]:my-3',
} as const;

/** One part of a kind's result, by the job it does inside the frame. */
export type ResultSlot = keyof typeof RESULT_SLOTS;

/** One kind's slot recipe, for `ResultBlock.slots`. */
export function resultSlots(...slots: readonly ResultSlot[]): string {
  return slots.map(slot => RESULT_SLOTS[slot]).join(' ');
}

/**
 * One slot of the analysis tray (D20): a named `section` with a small
 * heading — the slot's name, a plain-words hint of the question it answers,
 * and room at the end for a control that belongs to the heading. The cards
 * inside stack; the slot never draws a box of its own, because the tray is
 * the one surface and a box in a box is a frame around a frame.
 */
export function EditorSlot({
  name,
  title,
  hint,
  aside,
  className,
  children,
}: {
  name: string;
  title: string;
  hint?: string;
  aside?: React.ReactNode;
  className?: string;
  children?: React.ReactNode;
}) {
  return (
    <section
      data-slot={`analysis-slot-${name}`}
      aria-label={title}
      className={cn('flex min-w-0 flex-col', SPACE.GROUPS, className)}
    >
      {/* `h3`, under the view's own `h2`: the slot names a section of the
          page, and a reader jumping by heading must not find a level
          skipped (axe `heading-order`). It is small because it is a label,
          not because it is deep.

          The `aside` is a **control**, so it sits in the row beside the
          heading rather than inside it: a heading is the name of what
          follows, and a menu trigger inside one is read as part of that
          name — 「范围 条件：简单」 — and is reached by a reader jumping
          from heading to heading, where nothing is meant to be pressed. */}
      <div
        className={cn('text-muted-foreground flex items-center gap-2', TEXT_UI)}
      >
        <h3 className="flex min-w-0 items-center gap-2 font-semibold">
          <span className="text-foreground">{title}</span>
          {hint && <span className="font-normal">· {hint}</span>}
        </h3>
        {aside && <span className="ml-auto">{aside}</span>}
      </div>
      {children}
    </section>
  );
}

/**
 * One card in a slot — a dimension, a metric: a bordered row on the page's
 * ground, so it stands off the tray's tint, its controls in a line and the
 * remove control pushed to the end.
 */
export function EditorCard({
  className,
  ...props
}: React.ComponentProps<'div'>) {
  return (
    <div
      data-slot="editor-card"
      className={cn(
        'bg-background border-border flex min-w-0 flex-wrap items-center gap-2 rounded-md border px-2 py-1',
        TEXT_UI,
        className,
      )}
      {...props}
    />
  );
}

/**
 * One tile of the chart picker (D20 屏 I): an icon over a word on the
 * page's ground, the chosen one ringed, an unavailable one framed with its
 * reason under it, and the recommended one marked. The colours and states
 * are here so the picker stays markup.
 *
 * **Unavailable is said by the frame, not by fading.** The tile used to
 * wear `opacity-60` over `text-muted-foreground`, which put the reason line
 * — the one thing such a tile has to say — at 2.20:1 on the panel's ground,
 * far under the 4.5:1 axe asks of it. So the state is now the dashed border
 * and the muted fill, which carry no text through them, and the words keep
 * their own contrast: the same call `optionControls.tsx` records for
 * `OptionsSection` — secondary is said by weight and by frame, never by
 * being paler than it can be read.
 */
export function ChartTile({
  className,
  ...props
}: React.ComponentProps<'button'>) {
  return (
    <button
      type="button"
      data-slot="chart-tile"
      className={cn(
        'bg-background border-border text-foreground relative flex flex-col items-center gap-1 rounded-md border px-1 py-2 text-center',
        'aria-checked:ring-primary aria-checked:border-primary aria-checked:ring-1',
        'aria-disabled:border-dashed aria-disabled:bg-muted/40 aria-disabled:cursor-not-allowed',
        'focus-visible:ring-ring/50 outline-none focus-visible:ring-[3px]',
        '[&_[data-slot=chart-reason]]:text-foreground/70 [&_[data-slot=chart-reason]]:text-xs [&_[data-slot=chart-reason]]:leading-tight',
        '[&_[data-slot=chart-recommended]]:bg-primary [&_[data-slot=chart-recommended]]:text-primary-foreground [&_[data-slot=chart-recommended]]:absolute [&_[data-slot=chart-recommended]]:-top-2 [&_[data-slot=chart-recommended]]:left-1 [&_[data-slot=chart-recommended]]:rounded-full [&_[data-slot=chart-recommended]]:px-1.5 [&_[data-slot=chart-recommended]]:text-[10px]',
        TEXT_UI,
        className,
      )}
      {...props}
    />
  );
}

/**
 * A long value read whole — an error message, a stack trace — in a record's
 * detail. Monospaced and kept as written: a stack trace is lines, and the
 * author's line breaks are the reading. It scrolls inside itself past a
 * screenful, so one trace does not push every other field off the panel,
 * and it can be scrolled from the keyboard because it is focusable.
 */
export function LongText({ className, ...props }: React.ComponentProps<'pre'>) {
  return (
    <pre
      data-slot="long-text"
      tabIndex={0}
      className={cn(
        'max-h-80 overflow-auto rounded-md bg-muted/60 p-2 font-mono text-xs leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere] text-foreground',
        'focus-visible:ring-ring/50 outline-none focus-visible:ring-[3px]',
        className,
      )}
      {...props}
    />
  );
}
