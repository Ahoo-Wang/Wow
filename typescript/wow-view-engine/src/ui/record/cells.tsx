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
import type { FieldTone } from '../../model/index.js';
import { isSafeContentUrl } from '../../dashboard/index.js';
import { Badge } from '../components/badge.js';
import {
  badgeEntries,
  displayValue,
  formatNumber,
  type BadgeEntry,
  type DisplayContext,
  type DisplayField,
} from '../display.js';
import type { MessageFormatters } from '../MessagesProvider.js';

/**
 * What a renderer knows about the field a value came from — which is what any
 * reading of a value needs, `cellText` in a CSV included, so it is that type
 * under the name this side uses for it.
 */
export type CellField = DisplayField;

/**
 * The variant each tone is drawn with. The colour is never the only
 * difference — the label says which status this is — so this is emphasis on a
 * distinction the words already carry.
 */
const TONE_VARIANT: Record<FieldTone, 'secondary' | 'destructive'> = {
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
 * to move. The defaults are exactly what was written here before.
 *
 * `danger` says the fill twice because the registry's `destructive` variant
 * says its own twice: `bg-destructive/10` *and* `dark:bg-destructive/20`.
 * Only the unprefixed one is replaced by an unprefixed override, so the dark
 * theme kept the 20% wash and a cancelled row measured **1.29:1** against
 * the row it was on — the solid fill this paragraph describes was true in
 * one theme out of two. This is not a hardcoded light/dark pair: it is the
 * same semantic token written at the variant's own specificity, so a host
 * moving `--fve-dark-destructive` still moves it.
 */
const TONE_CLASS: Partial<Record<FieldTone, string>> = {
  success: 'bg-success text-success-foreground',
  warning: 'bg-warning text-warning-foreground',
  danger: 'bg-destructive dark:bg-destructive text-destructive-foreground',
};

/**
 * The edge a badge with no tone is drawn with — which is the whole of it.
 *
 * A row is a surface that moves under the badge: `bg-muted` once it is
 * selected, the same shade mixed in while it is hovered. The registry's
 * `secondary` fill *is* that shade, so on a selected row the badge measured
 * **1.00:1** against the row under it, in both themes — it stopped being a
 * badge and became a word. Several states sharing one 3% grey is fine right
 * up until two of them are stacked.
 *
 * `input` rather than `border`, although `border` is what a divider uses:
 * the theme keeps them apart on exactly this question (`styles.css`) —
 * `input` is the edge of a thing rather than a line between things, and it
 * is held at ≥3:1 because an unticked checkbox is *only* its ring. A badge
 * that has lost its fill to the row is in the same position, and `border`
 * is not: measured on the selected row it comes to **1.155:1**, which is
 * the same disappearance one step slower. Nothing moves either way — the
 * registry already draws `border border-transparent` on every badge, so
 * this only gives that line a colour.
 *
 * **A toned badge takes no edge.** It is a filled status surface (see
 * `TONE_CLASS`) and clears the row by its fill alone; a grey hairline
 * around a solid colour reads as a halo, not as an outline.
 */
const BADGE_EDGE = 'border-input';

/**
 * How wide a `text` cell may grow before it wraps. A table lays out by
 * content, so a paragraph with no ceiling makes one column as wide as its
 * longest note and pushes every other column off the screen; the clamp only
 * limits the lines, and a line has to end somewhere for there to be a second
 * one.
 */
const TEXT_CELL = 'max-w-[var(--fve-record-text-max-w,24rem)]';

/**
 * One value as its field reads it.
 *
 * The table and the cards both come through here, so a card can never
 * disagree with the column it was folded out of. What the field declares
 * decides: `status` and `tags` wear badges, `link` is a guarded external
 * link, `text` is a clamped paragraph, and a field that declares nothing
 * falls through to the kind's own rendering — a date in the surface's zone, a
 * number in its format, a boolean in the catalogue's words.
 */
export function cellValue(
  value: unknown,
  field: CellField,
  messages: MessageFormatters,
  display: DisplayContext,
): React.ReactNode {
  if (value === null || value === undefined) return null;

  const badges = badgeEntries(value, field);
  if (badges) return <Badges entries={badges} />;

  const cell = field.cell ?? field.kind;
  if (cell === 'link' && typeof value === 'string' && isSafeContentUrl(value))
    return (
      <a
        data-slot="cell-link"
        href={value}
        // A record's URLs come from the data, and the data is not this
        // application: the new document must not reach back through
        // `window.opener`, nor arrive carrying where it was opened from.
        target="_blank"
        rel="noopener noreferrer"
        className="text-primary underline underline-offset-4"
      >
        {value}
      </a>
    );
  if (cell === 'text' && typeof value === 'string')
    return (
      <span
        data-slot="cell-text"
        // Clamped rather than truncated: a note is worth three lines in a row
        // that stays the height of a row, and the whole of it is one hover
        // away. The newlines the author typed are kept, since a paragraph
        // folded into one line is a different paragraph.
        className={`line-clamp-3 whitespace-pre-wrap ${TEXT_CELL}`}
        title={value}
      >
        {value}
      </span>
    );

  // A time, a date or an enum shows as the field says; a number keeps its
  // format and a boolean its wording below.
  const shown = displayValue(value, field, display);
  if (shown !== undefined) return shown;
  if (typeof value === 'number')
    return formatNumber(value, field.numberFormat, display.locale);
  if (typeof value === 'boolean')
    return messages.label(value ? 'label.value.yes' : 'label.value.no');
  if (typeof value === 'string') return value;
  if (typeof value === 'bigint') return value.toString();
  return JSON.stringify(value);
}

/**
 * The badges of one cell, side by side.
 *
 * Keyed by the value and its place, never by the label: a list may hold the
 * same value twice and two options may be worded alike, and two children
 * under one key is a reconciliation React is free to get wrong.
 */
function Badges({ entries }: { entries: readonly BadgeEntry[] }) {
  return (
    <span className="flex flex-wrap items-center gap-1">
      {entries.map((entry, index) => {
        const tone = entry.tone ?? 'neutral';
        return (
          <Badge
            key={`${String(entry.value)}-${index}`}
            // Said on the element as well as drawn, so a host can style a
            // tone and a test can read one without matching on colour.
            data-tone={tone}
            variant={TONE_VARIANT[tone]}
            className={TONE_CLASS[tone] ?? BADGE_EDGE}
          >
            {entry.label}
          </Badge>
        );
      })}
    </span>
  );
}
