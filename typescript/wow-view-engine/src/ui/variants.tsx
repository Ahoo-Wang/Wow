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
 * rather than a colour. Three cases live here: a badge that has to read as a
 * status, the controls inside a condition pill, which draw no chrome of
 * their own, and the open view in the sidebar. The fourth is `ui/alerts.tsx`,
 * where `LineAlert` does the same thing to `Alert`; a callout has enough of
 * its own to say (the tone's icon, the role it is announced with) to be a
 * file rather than an export here.
 *
 * A fourth case does not belong here: a *layout* class at a call site —
 * a width, a gap, a `justify-start` — is what `className` is for and stays
 * where it is used.
 */

import type * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from 'cn';
import type { FieldTone } from '../model/index.js';
import { Badge } from './components/badge.js';
import { Button } from './components/button.js';
import { Input } from './components/input.js';
import { SelectTrigger } from './components/select.js';

/**
 * The registry variant each tone starts from.
 *
 * `danger` starts from `destructive` so that everything but the fill —
 * the focus ring, its dark-theme counterpart — is the registry's own; the
 * others start from `secondary`, whose shape is right and whose fill is
 * replaced below.
 */
const TONE_BASE: Record<FieldTone, 'secondary' | 'destructive'> = {
  neutral: 'secondary',
  success: 'secondary',
  warning: 'secondary',
  danger: 'destructive',
};

/**
 * A toned badge is a *filled* status surface with the page's own colour as
 * its text, which is the recipe `styles.css` records for these tokens.
 *
 * Not a tint of it, although that is what the registry's `destructive`
 * variant does and what this tried first: a 10% wash leaves the same hue
 * reading against a surface it has been lightened towards, and axe measured
 * 3.98:1 for destructive, 4.32 for success and 4.37 for warning at the 12px
 * a badge is set in — all of them short of 4.5. The tokens are picked to
 * clear that ratio *against the surface*, so filling with the token and
 * writing in the surface colour is the pairing they were chosen for, and it
 * flips with the theme: light text on a dark fill, dark text on a light one.
 * The ink is each fill's own `-foreground` rather than `text-background`,
 * which was the right value under the wrong name — a host moving
 * `--fve-success` to a pale green got white writing on it and had nothing
 * to move.
 *
 * `danger` says the fill twice because the registry's `destructive` variant
 * says its own twice: `bg-destructive/10` *and* `dark:bg-destructive/20`.
 * Only the unprefixed one is replaced by an unprefixed override, so the dark
 * theme kept the 20% wash and a cancelled row measured **1.29:1** against
 * the row it was on — the solid fill this paragraph describes was true in
 * one theme out of two. This is not a hardcoded light/dark pair: it is the
 * same semantic token written at the variant's own specificity, so a host
 * moving `--fve-dark-destructive` still moves it.
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
 * step slower. Nothing moves either way — the registry already draws
 * `border border-transparent` on every badge, so this only gives that line
 * a colour.
 *
 * **A toned badge takes no edge.** It clears the row by its fill alone; a
 * grey hairline around a solid colour reads as a halo, not as an outline.
 */
const toneBadgeVariants = cva('', {
  variants: {
    tone: {
      neutral: 'border-input',
      success: 'bg-success text-success-foreground',
      warning: 'bg-warning text-warning-foreground',
      danger: 'bg-destructive dark:bg-destructive text-destructive-foreground',
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
 * One row of the sidebar's list of views.
 *
 * The open one is the work area's own ground with a 2px bar of `primary`
 * down its leading edge — not another step of grey. Four states used to
 * share one 3% grey, so the open view and a hovered one painted the same
 * colour and the list had no "you are here" at all; a bar is a different
 * *kind* of mark, and no amount of theming can collapse it into the fill
 * beside it. Hover is a step of the column's own scale in the other
 * direction, which is why it replaces the ghost variant's `muted` — on this
 * ground that one is the ground.
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
        true: 'bg-background text-foreground hover:bg-background hover:text-foreground font-medium shadow-[inset_2px_0_0_var(--primary)]',
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
