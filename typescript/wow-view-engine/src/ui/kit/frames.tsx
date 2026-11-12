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
 * The frames this package draws round what it shows, and the marks on them:
 * the focus rings a row, a card and a panel wear (`FOCUS_ROW`, `FOCUS_CARD`,
 * `FOCUS_PANEL`), how a card stands off its ground (`CARD_LIFT`), the
 * dashboard's three — a panel's card with its warning edge, the edge a
 * filter chip's controls share, the band a board in a mode wears — the
 * result's frame and the recipes a kind's parts wear inside it, the analysis
 * tray's rows and cards, a chart picker's tile, and a long value read whole.
 *
 * Each is a recipe or a thin wrapper, as `variants.tsx` says of all of them
 * (D16 ruling 8); `test/variants.test.tsx` holds their class recipes.
 */

import type * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from 'cn';
import { Card } from '../components/card.js';
import { SPACE, TEXT_UI } from './layout.js';

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
 *
 * The edge is the recipe every control wears (`Button`, `FOCUS_CARD`): a
 * `--ring` line at full strength, which holds the 3:1 WCAG 1.4.11 asks
 * of a focus indicator, and the half-strength halo inside it as emphasis.
 * It is `focus-width` wide (1px unset, the registry's edge; D76), so a
 * theme that owes 2px (WCAG 2.4.13) gets 2px on a row too. It used to be
 * the halo alone, 2px of `--ring` at 50%, which measured
 * 1.74:1 on the light page and 2.38:1 on the dark one (`FocusMarks*` in
 * `stories/view-engine/ThemeTokens.test.stories.tsx`; phase 5, 5A). The
 * full-strength shadows come first, so they are painted over the halo.
 */
export const FOCUS_ROW = cn(
  'fve:outline-none',
  'fve:focus-visible:*:bg-row-hover',
  // The ring, then the halo every control here wears (`--_fve-focus-halo`,
  // none unless a theme draws one), written out: Tailwind reads class
  // names, not code, so each is spelled whole. `focus-width` is read with
  // the registry's 1px behind it, and the halo is 2px beyond the edge.
  'fve:focus-visible:*:shadow-[inset_0_var(--_fve-focus-width,1px)_0_var(--ring),inset_0_calc(var(--_fve-focus-width,1px)*-1)_0_var(--ring),inset_0_calc(var(--_fve-focus-width,1px)_+_2px)_0_var(--_fve-focus-halo),inset_0_calc((var(--_fve-focus-width,1px)_+_2px)*-1)_0_var(--_fve-focus-halo)]',
  'fve:focus-visible:*:first:shadow-[inset_0_var(--_fve-focus-width,1px)_0_var(--ring),inset_0_calc(var(--_fve-focus-width,1px)*-1)_0_var(--ring),inset_var(--_fve-focus-width,1px)_0_0_var(--ring),inset_0_calc(var(--_fve-focus-width,1px)_+_2px)_0_var(--_fve-focus-halo),inset_0_calc((var(--_fve-focus-width,1px)_+_2px)*-1)_0_var(--_fve-focus-halo),inset_calc(var(--_fve-focus-width,1px)_+_2px)_0_0_var(--_fve-focus-halo)]',
  'fve:focus-visible:*:last:shadow-[inset_0_var(--_fve-focus-width,1px)_0_var(--ring),inset_0_calc(var(--_fve-focus-width,1px)*-1)_0_var(--ring),inset_calc(var(--_fve-focus-width,1px)*-1)_0_0_var(--ring),inset_0_calc(var(--_fve-focus-width,1px)_+_2px)_0_var(--_fve-focus-halo),inset_0_calc((var(--_fve-focus-width,1px)_+_2px)*-1)_0_var(--_fve-focus-halo),inset_calc((var(--_fve-focus-width,1px)_+_2px)*-1)_0_0_var(--_fve-focus-halo)]',
);

/**
 * The mark on a card the keyboard is on — the one every control wears: a
 * `--ring` edge at full strength, `focus-width` wide, `focus-offset` from
 * the card's edge (a theme may pull it onto the edge with a negative
 * offset, as azure does), and the theme's halo outside it
 * (`--_fve-focus-halo`, none unless a theme draws one), which takes the
 * place of the card's own faint ring while it is focused. A halo alone
 * measured 1.77:1 on the light page and 2.15:1 on the dark one (phase 5,
 * 5A; `FocusMarks*`), which is why the edge is the mark.
 */
export const FOCUS_CARD = cn(
  'fve:outline-none fve:focus-visible:outline-solid fve:focus-visible:outline-[length:var(--_fve-focus-width,1px)] fve:focus-visible:outline-offset-[var(--_fve-focus-offset)] fve:focus-visible:outline-ring',
  'fve:focus-visible:ring-[3px] fve:focus-visible:ring-(color:--_fve-focus-halo)',
);

/**
 * The mark on a dashboard panel whose body — the region that scrolls, a Tab
 * stop of its own (U-03) — the keyboard is on, drawn on the card round it:
 * `FOCUS_CARD`'s edge and halo, worn while the body is focused. The body is
 * as wide as the card that clips it (`overflow-hidden`), so a ring outside
 * the body lost both sides and left a grey hairline at 1.77:1; turned inward
 * it covered the body's first line — the month over a metric card, a table's
 * head — 4px deep (Storybook review P2). The card clips its children, not
 * its own outline, and its padding keeps the mark off every word.
 */
export const FOCUS_PANEL = cn(
  'fve:has-[[data-panel-body]:focus-visible]:outline-solid fve:has-[[data-panel-body]:focus-visible]:outline-[length:var(--_fve-focus-width,1px)] fve:has-[[data-panel-body]:focus-visible]:outline-offset-[var(--_fve-focus-offset)] fve:has-[[data-panel-body]:focus-visible]:outline-ring',
  'fve:has-[[data-panel-body]:focus-visible]:ring-[3px] fve:has-[[data-panel-body]:focus-visible]:ring-(color:--_fve-focus-halo)',
);

/**
 * How a card stands off what it sits on, in the theme's words: the ring in
 * `--_fve-card-edge` (the registry's `ring-foreground/10` unset) and the shadow
 * `--_fve-card-shadow` (none unset). Every card on the surface wears it — a
 * board's panels, the record cards and their skeletons.
 */
export const CARD_LIFT =
  'fve:ring-(color:--_fve-card-edge) fve:shadow-(--_fve-card-shadow)';

/**
 * A dashboard panel's frame: the registry's card, whose edge is a ring
 * (`ring-1 ring-foreground/10`), not a border — so a panel carrying a
 * warning takes the warning colour on that ring (U-04). It used to ask for
 * `border-warning`, which painted a border 0px wide: the state was on the
 * element and nowhere on the screen.
 *
 * The ring's colour and the card's shadow are the theme's (`--_fve-card-edge`,
 * `--_fve-card-shadow`; themes.md 7): the registry's hairline and no shadow
 * unless a theme lifts its cards instead. A warning still takes the ring,
 * one variant over the theme's colour.
 */
export function PanelCard({
  className,
  ...props
}: React.ComponentProps<typeof Card>) {
  return (
    <Card
      className={cn(CARD_LIFT, 'fve:data-[warning]:ring-warning', className)}
      {...props}
    />
  );
}

/**
 * The one edge a few borderless controls share — a board filter's chip
 * (the name, the value control, ✕), a locked filter's reading, and the
 * same controls in the filter's settings (U-06). Nothing inside draws an
 * edge (D12: the controls are the condition editor's pill controls), so
 * this edge *is* the control's, and it is `--input`, the edge that holds
 * 3:1 (1.4.11), not `--border`, which measured 1.22:1 on the chip's fill.
 * `data-idle` — a filter reaching nothing on the tab on screen — is
 * quieter by its frame, dashed on the page's ground, never by fading the
 * words.
 *
 * It is the theme's control fill and edge (`--_fve-control`,
 * `--_fve-control-edge`; themes.md 7): a chip whose controls are picked — a
 * select names itself by its words and its chevron — needs no edge to be
 * found (1.4.11 asks a boundary of none). A chip holding a text box keeps
 * the `--input` edge whatever the theme says: a field typed into is found
 * by its boundary.
 */
export function ControlFrame({
  className,
  ...props
}: React.ComponentProps<'div'>) {
  return (
    <div
      // The chip's height and its controls' are `filter-height`'s
      // (`styles.css`), found by this.
      data-control-frame=""
      className={cn(
        'fve:rounded-md fve:border fve:border-(--_fve-control-edge) fve:bg-(--_fve-control) fve:has-[[data-slot=input]]:border-input fve:data-[idle]:border-dashed fve:data-[idle]:border-input fve:data-[idle]:bg-background',
        className,
      )}
      {...props}
    />
  );
}

const modeBarVariants = cva('fve:bg-muted/50 fve:border-border fve:border', {
  variants: {
    size: {
      /** Across the board: the edit bar, the wiring bar. */
      bar: 'fve:rounded-lg fve:px-3 fve:py-2',
      /** Under one panel: its wiring strip. */
      strip: 'fve:rounded-md fve:px-2 fve:py-1',
    },
  },
  defaultVariants: { size: 'bar' },
});

/**
 * A line saying the board is in a mode — being built, a filter being
 * wired — and one panel's strip in that mode: a muted band with an edge,
 * written once here rather than twice at the call sites (U-06).
 */
export function ModeBar({
  size,
  className,
  ...props
}: React.ComponentProps<'div'> & VariantProps<typeof modeBarVariants>) {
  return (
    <div className={cn(modeBarVariants({ size }), className)} {...props} />
  );
}

/**
 * The frame round a result, and the padding it gives the two parts the
 * shell itself puts in it (–Ⅶ).
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
  'fve:bg-content fve:border-border fve:-mx-4 fve:-mb-4 fve:overflow-hidden fve:border-t ' +
  'fve:[&>[data-slot=result-toolbar]]:border-border fve:[&>[data-slot=result-toolbar]]:border-b fve:[&>[data-slot=result-toolbar]]:px-4 fve:[&>[data-slot=result-toolbar]]:py-2 ' +
  'fve:[&>[data-slot=status-strip]]:mx-4 fve:[&>[data-slot=status-strip]]:my-3';

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
    'fve:[&>[data-slot=record-pagination]]:border-border fve:[&>[data-slot=record-pagination]]:bg-muted/40 fve:[&>[data-slot=record-pagination]]:border-t fve:[&>[data-slot=record-pagination]]:px-4 fve:[&>[data-slot=record-pagination]]:py-2 ' +
    'fve:[&>[data-slot=analysis-caption]]:border-border fve:[&>[data-slot=analysis-caption]]:bg-muted/40 fve:[&>[data-slot=analysis-caption]]:border-t fve:[&>[data-slot=analysis-caption]]:px-4 fve:[&>[data-slot=analysis-caption]]:py-2',
  /** A query that matched nothing, in air of its own rather than to the edge. */
  empty: 'fve:[&>[data-slot=record-empty]]:my-6',
  /** Cards keep the column's padding; a table is what runs to the edge. */
  cards: 'fve:[&>[data-slot=record-cards]]:p-4',
  /** A host command's line sits where the query strip does, and as it does. */
  bulk: 'fve:[&>[data-slot=bulk-status]]:mx-4 fve:[&>[data-slot=bulk-status]]:my-3',
} as const;

/** One part of a kind's result, by the job it does inside the frame. */
type ResultSlot = keyof typeof RESULT_SLOTS;

/** One kind's slot recipe, for `ResultBlock.slots`. */
export function resultSlots(...slots: readonly ResultSlot[]): string {
  return slots.map(slot => RESULT_SLOTS[slot]).join(' ');
}

/**
 * One row of the analysis tray: a named `section` whose head is the
 * analyst's term — 展开, 指标, 维度, 结果, 范围 — with the ⓘ that explains it
 * (`tip`) at its top-right, then what the row holds, then, at the row's
 * end, what belongs to the row as a whole (`end`: the counting unit, the
 * conditions' grammar). From `md` up the head is a column of its own — the
 * row is a subgrid of the tray's two columns, the first as wide as the
 * widest term — so the terms line up down the tray, the rows read as a
 * form, and no row gives up more width than its longest term needs;
 * narrower, the head sits over the row. The row never draws a box of its own,
 * because the tray is the one surface and a box in a box is a frame around
 * a frame.
 */
export function EditorSlot({
  name,
  title,
  tip,
  end,
  className,
  children,
}: {
  name: string;
  title: string;
  /** The ⓘ beside the term, a control of its own (`TermTip`). */
  tip?: React.ReactNode;
  /** What stands at the row's end. */
  end?: React.ReactNode;
  className?: string;
  children?: React.ReactNode;
}) {
  return (
    <section
      data-slot={`analysis-slot-${name}`}
      aria-label={title}
      className={cn(
        'fve:grid fve:min-w-0 fve:grid-cols-1 fve:items-start fve:md:col-span-2 fve:md:grid-cols-subgrid',
        SPACE.WITHIN,
        className,
      )}
    >
      {/* `h3`, under the view's own `h2`: the row names a section of the
          page, and a reader jumping by heading must not find a level
          skipped (axe `heading-order`). The ⓘ is a **control**, so it sits
          beside the heading rather than inside it: a heading is the name of
          what follows, and a button inside one is read as part of that
          name and reached by a reader jumping from heading to heading,
          where nothing is meant to be pressed. */}
      <div
        data-slot="analysis-slot-head"
        className={cn(
          'fve:flex fve:min-h-7 fve:items-center fve:gap-0.5',
          TEXT_UI,
        )}
      >
        <h3 className="fve:text-foreground fve:font-semibold">{title}</h3>
        {tip}
      </div>
      <div className="fve:flex fve:min-w-0 fve:flex-wrap fve:items-start fve:gap-x-3 fve:gap-y-2">
        <div
          data-slot="analysis-slot-body"
          className={cn(
            'fve:flex fve:min-w-0 fve:flex-1 fve:basis-64 fve:flex-col',
            SPACE.GROUPS,
          )}
        >
          {children}
        </div>
        {end && (
          <div
            data-slot="analysis-slot-end"
            className={cn(
              'fve:ml-auto fve:flex fve:min-h-7 fve:shrink-0 fve:items-center',
              TEXT_UI,
            )}
          >
            {end}
          </div>
        )}
      </div>
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
        'fve:bg-background fve:border-border fve:flex fve:min-w-0 fve:flex-wrap fve:items-center fve:gap-2 fve:rounded-md fve:border fve:px-2 fve:py-1',
        TEXT_UI,
        className,
      )}
      {...props}
    />
  );
}

/**
 * One tile of the chart picker: an icon over a word on the
 * page's ground, the chosen one ringed, an unavailable one framed with its
 * reason under it, and the recommended one marked — the mark centred across
 * the bottom edge, where it has the tile's whole width and covers neither the
 * icon nor the name. The colours and states are here so the picker stays
 * markup.
 *
 * **Unavailable is said by the frame, not by fading.** The tile used to
 * wear `opacity-60` over `text-muted-foreground`, which put the reason line
 * — the one thing such a tile has to say — at 2.20:1 on the panel's ground,
 * far under the 4.5:1 axe asks of it. So the state is now the dashed border
 * and the muted fill, which carry no text through them, and the words keep
 * their own contrast: the same call `optionControls.tsx` records for
 * `OptionsSection` — secondary is said by weight and by frame, never by
 * being paler than it can be read.
 *
 * **What it holds stands in its middle**, and the tile is at least 64px
 * tall: a row is as tall as its tallest tile, and the others centre their
 * icon and name in that height rather than leaving the lower half blank
 * (2026-09-23 audit); the floor keeps the name clear of the mark hanging
 * across the bottom edge.
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
        'fve:bg-background fve:border-border fve:text-foreground fve:relative fve:flex fve:min-h-16 fve:w-full fve:flex-col fve:items-center fve:justify-center fve:gap-1 fve:rounded-md fve:border fve:px-1 fve:py-2 fve:text-center',
        'fve:aria-checked:ring-primary fve:aria-checked:border-primary fve:aria-checked:ring-1',
        'fve:aria-disabled:border-dashed fve:aria-disabled:bg-muted/40 fve:aria-disabled:cursor-not-allowed',
        'fve:focus-visible:ring-ring/50 fve:outline-none fve:focus-visible:ring-[3px]',
        'fve:[&_[data-slot=chart-reason]]:text-quiet-foreground fve:[&_[data-slot=chart-reason]]:text-xs fve:[&_[data-slot=chart-reason]]:leading-tight',
        'fve:[&_[data-slot=chart-hint]]:text-quiet-foreground fve:[&_[data-slot=chart-hint]]:text-xs fve:[&_[data-slot=chart-hint]]:leading-tight',
        'fve:[&_[data-slot=chart-recommended]]:bg-primary fve:[&_[data-slot=chart-recommended]]:text-primary-foreground fve:[&_[data-slot=chart-recommended]]:absolute fve:[&_[data-slot=chart-recommended]]:-bottom-2 fve:[&_[data-slot=chart-recommended]]:left-1/2 fve:[&_[data-slot=chart-recommended]]:-translate-x-1/2 fve:[&_[data-slot=chart-recommended]]:whitespace-nowrap fve:[&_[data-slot=chart-recommended]]:rounded-full fve:[&_[data-slot=chart-recommended]]:px-1.5 fve:[&_[data-slot=chart-recommended]]:text-[10px]',
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
        'fve:relative fve:max-h-80 fve:overflow-auto fve:rounded-md fve:bg-muted/60 fve:p-2 fve:font-mono fve:text-xs fve:leading-relaxed fve:whitespace-pre-wrap fve:[overflow-wrap:anywhere] fve:text-foreground',
        'fve:focus-visible:ring-ring/50 fve:outline-none fve:focus-visible:ring-[3px]',
        className,
      )}
      {...props}
    />
  );
}
