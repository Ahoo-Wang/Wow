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
 * The sort said in words and arrows: the wording per direction, the toolbar
 * button's name and what it shows (`SortSummary`), and the arrow for one
 * field (`DirectionMark`). `SortSettings` draws them on its button and its
 * rows.
 */

import { ArrowDownIcon, ArrowDownUpIcon, ArrowUpIcon } from 'lucide-react';
import type { RecordSort, SortDirectionName } from '../../model/index.js';
import { SortDirections } from '../../model/index.js';
import type { MessageKey } from '../kit/messages.js';
import type { MessageFormatters } from '../kit/MessagesProvider.js';

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
export const DIRECTION_LABEL: Record<SortDirectionName, MessageKey> = {
  [SortDirections.ASC]: 'label.sort.asc',
  [SortDirections.DESC]: 'label.sort.desc',
};

export function directionOf(
  direction: SortDirectionName | undefined,
): SortDirectionName {
  return direction === SortDirections.DESC
    ? SortDirections.DESC
    : SortDirections.ASC;
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
 * (`label.sort.button`), which is the visible text with the direction and
 * that word after it — the same string said two ways, never two names (D12).
 *
 * The count stays beside the field, as it is on screen: WCAG 2.5.3 asks the
 * name to hold the visible text as one run of words, and 「发运日期 降序 +2」
 * put the direction between the two (axe 4.14's label-content-name-mismatch).
 */
export function sortButtonName(
  sort: readonly RecordSort[],
  labelOf: (field: string) => string,
  messages: MessageFormatters,
): string | undefined {
  const first = sort[0];
  if (!first) return undefined;
  const more = sort.length - 1;
  return messages.label('label.sort.button', {
    field:
      more === 0
        ? labelOf(first.field)
        : `${labelOf(first.field)} ${messages.label('label.sort.more', { count: more })}`,
    direction: messages.label(DIRECTION_LABEL[directionOf(first.direction)]),
  });
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
export function SortSummary({
  sort,
  labelOf,
  messages,
  none,
}: {
  sort: readonly RecordSort[];
  labelOf(field: string): string;
  messages: MessageFormatters;
  /** What the button says while nothing is sorted. */
  none: string;
}) {
  const first = sort[0];
  if (!first)
    return (
      <>
        {none}
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
export function DirectionMark({
  direction,
  icon,
}: {
  direction: SortDirectionName;
  icon?: 'inline-start' | 'inline-end';
}) {
  const Arrow = direction === SortDirections.ASC ? ArrowUpIcon : ArrowDownIcon;
  return <Arrow data-slot="sort-direction" data-icon={icon} />;
}
