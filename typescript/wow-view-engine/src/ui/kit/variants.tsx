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
 * rather than a colour. These cases live here: a badge that has to read as a
 * status (and one that says a change, in the colour the host's convention
 * gives it), a badge that has to hold a sentence, the answer that carries out a
 * destructive command, the dashed edge of a button that adds what is
 * not there yet, the one divider that has to be seen, one row of
 * records in its three states, and the open view in the sidebar. Two families
 * live beside it, written the same way: the controls inside a condition pill,
 * which draw no chrome of their own (`pills.tsx`), and the frames — the focus
 * rings, a card's lift, the dashboard's three (a panel's frame with its
 * warning edge, the edge a filter chip's controls share, the band a board in
 * a mode wears), the result's frame and its slot recipes (`frames.tsx`). The one
 * that is not here is `ui/kit/alerts.tsx`, where
 * `LineAlert` does the same thing to `Alert`;
 * a callout has enough of its own to say (the tone's icon, the role it is
 * announced with) to be a file rather than an export here.
 *
 * The result frame's slot recipes sit with the frames for the same reason,
 * one step out: they are the chrome one of this package's own frames gives
 * what a *kind* puts inside it, and the frame is shared by three kinds.
 *
 * One kind of class does not belong here: a *layout* class at a call site —
 * a width, a gap, a `justify-start` — is what `className` is for and stays
 * where it is used.
 */

import type * as React from 'react';
import { cva } from 'class-variance-authority';
import { cn } from 'cn';
import type { FieldTone } from '../../model/index.js';
import { AlertDialogAction } from '../components/alert-dialog.js';
import { Badge } from '../components/badge.js';
import { Button } from '../components/button.js';
import { Separator } from '../components/separator.js';
import { TableRow } from '../components/table.js';
import { FOCUS_ROW } from './frames.js';

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
 *   5.59, warning 6.09 / 5.61 (rest / selected). Dark: every tone writes
 *   in its own token. Danger once measured **3.92:1** on a selected row at
 *   the registry's 20% dark wash and wore a mix toward `--foreground` in
 *   the dark alone; since the dark status colours were quietened and
 *   `--destructive` lifted (phase 5, Q44, `styles.css`) it reads 5.48:1
 *   there on its own (4.56:1 in the example theme `porcelain`, the tightest measured) (`test/presetContrast.test.ts`).
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
  "fve:before:size-1.5 fve:before:shrink-0 fve:before:rounded-full fve:before:bg-current fve:before:content-['']";

const toneBadgeVariants = cva('', {
  variants: {
    tone: {
      neutral: 'fve:border-input',
      // The wash and the edge are the stylesheet's, by `data-tone`: as much
      // of the tone as `--_fve-badge-fill` and `--_fve-badge-edge` say
      // (10% and 30% unset; `styles.css`, theme-architecture.md 4.2).
      success: `fve:text-success ${SOFT}`,
      warning: `fve:text-warning ${SOFT}`,
      danger: `fve:text-destructive ${SOFT}`,
    } satisfies Record<FieldTone, string>,
    /**
     * `false` where an icon already marks the badge — a metric card's
     * change leads with its arrow, and a dot beside the arrow is two marks
     * saying one thing.
     */
    dot: { true: '', false: 'fve:before:hidden' },
  },
  defaultVariants: { tone: 'neutral', dot: true },
});

interface ToneBadgeProps extends Omit<
  React.ComponentProps<typeof Badge>,
  'variant'
> {
  /**
   * Which status this is. The colour is never the only difference — the
   * label says which status it is — so this is emphasis on a distinction the
   * words already carry.
   */
  tone?: FieldTone;
  /** Whether the tone's dot leads the word; see `toneBadgeVariants`. */
  dot?: boolean;
}

/** A badge in the colour of a field's tone. */
export function ToneBadge({
  tone = 'neutral',
  dot = true,
  className,
  ...props
}: ToneBadgeProps) {
  return (
    <Badge
      // Said on the element as well as drawn, so a host can style a tone and
      // a test can read one without matching on colour.
      data-tone={tone}
      variant={TONE_BASE[tone]}
      className={cn(toneBadgeVariants({ tone, dot }), className)}
      {...props}
    />
  );
}

export interface ChangeBadgeProps extends Omit<
  React.ComponentProps<typeof Badge>,
  'variant'
> {
  /** Which way the change went; `flat` is no change, and has no colour. */
  direction: 'up' | 'down' | 'flat';
  /**
   * Whether that is the good way (`success`) or the bad one (`danger`);
   * `neutral` for no change.
   */
  tone: Extract<FieldTone, 'success' | 'danger' | 'neutral'>;
}

/**
 * A change — up or down, good or bad — as a toned badge (themes.md 2.6).
 *
 * The same soft recipe as `ToneBadge` (a 10% tint, the colour as the writing,
 * a 30% edge), in one colour the stylesheet picks rather than the component:
 * `--_fve-change`, which `styles.css` sets from the tone under the default
 * convention and from the direction (`--_fve-rise` / `--_fve-fall`) once the host
 * names one by direction (`data-fve-change-colors`). So the component knows
 * no convention and needs no context, and a popup under another convention
 * is recoloured by the attribute it copies. Both are said on the element —
 * `data-tone` and `data-change` — as the tone badge says its tone. The
 * colour is never all it says: a caller leads it with the direction's arrow
 * and writes the sign. No change is the neutral badge, under every
 * convention.
 */
export function ChangeBadge({
  direction,
  tone,
  className,
  ...props
}: ChangeBadgeProps) {
  if (direction === 'flat' || tone === 'neutral')
    return (
      <ToneBadge
        data-change={direction}
        tone="neutral"
        dot={false}
        className={className}
        {...props}
      />
    );
  return (
    <Badge
      data-tone={tone}
      data-change={direction}
      variant="secondary"
      className={cn('fve:text-change', className)}
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
      className={cn(
        'fve:h-auto fve:max-w-full fve:text-left fve:whitespace-normal',
        className,
      )}
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
  'fve:bg-destructive fve:dark:bg-destructive fve:text-destructive-foreground',
  'fve:hover:bg-destructive/90 fve:dark:hover:bg-destructive/90',
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
 * An edge that says "not chosen yet": the dashed outline of an add button
 * (user, review of #3781 — 「+ 商品」 drawn as a borderless ghost read as a
 * value already picked). The registry's outline edge in its own colours
 * (`border-border`, `dark:border-input`), only dashed; hover fills as the
 * outline button does and the edge stays dashed. Size, hit area and focus
 * ring are the registry's.
 */
const addButtonVariants = cva('fve:border-dashed');

/**
 * A button that adds something not there yet — 「+ 添加」 on the tray's
 * metrics, dimensions and range, 「+ 商品」 into the chain, 「+ 只保留」.
 * What was added (a level card, a metric pill, a dimension chip, a
 * condition) keeps its solid edge, so the two never read alike.
 *
 * `data-affordance="add"` says so on the element, for a suite to ask
 * instead of naming the edge; the edge itself is asserted once, in
 * `test/variants.test.tsx`. It renders as a trigger's `render` element too.
 */
export function AddButton({
  className,
  ...props
}: Omit<React.ComponentProps<typeof Button>, 'variant'>) {
  return (
    <Button
      variant="outline"
      size="sm"
      data-affordance="add"
      className={cn(addButtonVariants(), className)}
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
      className={cn(
        'fve:dark:bg-input fve:h-5 fve:data-vertical:self-center',
        className,
      )}
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
 * column that the held cell is standing in front of. `--_fve-row-hover`
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
const tableDataRowVariants = cva([
  // The rows' own ground (`--_fve-content`, the page's `background` unset),
  // every other one striped where a theme turns the stripe on.
  'fve:bg-content fve:even:bg-row-stripe',
  'fve:hover:bg-row-hover fve:has-aria-expanded:bg-row-hover',
  'fve:data-[state=selected]:bg-row-selected fve:data-[state=selected]:hover:bg-row-selected fve:data-[state=selected]:text-row-selected-foreground',
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
 * step of grey (the roles `nav-current`, `nav-current-edge` and
 * `nav-current-shadow`, so a theme can draw it as a tint with no edge and
 * no lift instead). Four states used to
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
  // The row's height is the surface's density (`--_fve-sidebar-item-height`,
  // `styles.css`): 28px at the default, which is the registry's `sm`.
  'fve:hover:bg-sidebar-accent fve:hover:text-sidebar-accent-foreground fve:h-(--_fve-sidebar-item-height) fve:justify-start',
  {
    variants: {
      current: {
        true: 'fve:bg-nav-current fve:text-nav-current-foreground fve:hover:bg-nav-current fve:hover:text-nav-current-foreground fve:border-nav-current-edge fve:shadow-nav-current fve:font-medium',
        false: '',
      },
    },
    defaultVariants: { current: false },
  },
);

interface SidebarItemProps extends Omit<
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
