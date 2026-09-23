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

/** The condition builder, the applied-condition bar, and the filter kernel. */
export const filterMessages = {
  // Mode switches.
  'label.filter.simple': 'Simple',
  'label.filter.advanced': 'Advanced',
  'label.filter.all-of': 'All of',
  'label.filter.any-of': 'Any of',
  'label.filter.none-of': 'None of',

  // The condition builder. The names ending in `-of` are accessible names,
  // which is why they read as a field followed by what the control does: a
  // screen reader announces them one after another and nothing else tells
  // the two `warehouse` selects apart.
  'label.filter.panel': 'Filter',
  'label.filter.mode': 'Filter mode',
  'label.filter.clear': 'Clear',
  'label.filter.apply': 'Apply',
  // Not "revert": that one is about the saved config, and this one is about
  // the draft filter alone — it puts the conditions back to what the rows on
  // screen were fetched under and leaves everything else where it is.
  'label.filter.discard': 'Discard edits',
  // The same two actions under an analysis's range (`FilterActions`'s
  // `words`): the tray's conditions are the range, and 'Discard edits'
  // there read as undoing the whole tray (the 2026-09-23 audit, P2-4).
  'label.filter.range.discard': 'Discard range edits',
  'label.filter.range.clear': 'Clear range',
  // A group's operator, read as the sentence it makes of the conditions
  // under it rather than as the boolean it compiles to. "All of" was the
  // word for a toggle in a row of three; a select shows one at a time, and
  // one at a time it has to say what it means on its own.
  'label.filter.all-conditions': 'All conditions',
  'label.filter.any-condition': 'Any condition',
  'label.filter.no-condition': 'No condition',
  // The field picker, which stays open while several fields are chosen.
  // The same picker adds to a record filter, an analysis's range and a
  // metric's own condition, so its title names none of them.
  'label.filter.pick-fields': 'Choose fields',
  'label.filter.pick-done': 'Done',
  'label.filter.add-group': 'Add a group',
  // A condition with no value yet. It is a normal editing state and not an
  // error, so the pill says what is missing rather than that anything is
  // wrong.
  'label.filter.not-set': 'Not set',
  // An edited condition changes nothing until it is applied, and a condition
  // with an error stops the whole submission; both are marked where they are.
  'label.filter.pending': 'Not applied yet',
  'label.filter.blocked': '{count} to fix',
  // A condition nothing can edit: the field's kind is not in the registry,
  // or the kind asked for an editor this engine does not have. The pill says
  // it where the editor would have been, because that is where the absence
  // is; the strip above is for findings no pill can carry.
  'label.filter.kind-unregistered':
    "This field's kind ({kind}) has no editor registered.",
  // Simple mode's negation (D18-7): a switch on the pill wraps the condition
  // in a `nor` group of its own rather than every kind growing a negated
  // operator. The switch is named after its field like the row's other
  // controls; the word is what the pill then reads in its sentence, and the
  // bar says the whole condition under it.
  'label.filter.negate-of': 'Negate the {field} condition',
  'label.filter.negated': 'not',
  'label.filter.not-of': 'not {condition}',
  'label.filter.group-operator': 'Group operator',
  'label.filter.remove-group': 'Remove group',
  'label.filter.add': 'Add',
  'label.filter.add-condition': 'Add condition',
  'label.filter.condition-of': '{field} condition',
  'label.field.none': 'No field matches',
  'label.field.search': 'Search fields',
  'label.filter.operator-of': '{field} operator',
  'label.filter.value-of': '{field} value',
  'label.filter.remove-of': 'Remove {field}',
  'label.filter.unset-of': 'Unset {condition}',
  // What separates the values of one condition, and the conditions of one
  // group, in an applied badge. Punctuation is wording too: a Chinese list
  // is not separated by a comma and a space.
  // A condition that keeps one value, said in a summary: 「状态 是 待出库」
  // whether it was stored as `EQ` or as an `IN` of one (the 2026-09-23
  // audit, P2-2 — 「等于」 and 「属于」 read as two different relations).
  'label.relation.is': 'is',
  'label.relation.is-not': 'is not',
  // What an operator means for the kind that named it: an array's `IN` asks
  // whether the array holds any of the candidates, which "is any of" would
  // say the other way round.
  'label.relation.has-any': 'has any of',
  'label.relation.has-none': 'has none of',
  'label.relation.has-all': 'has all of',
  'label.filter.join': ', ',
  // An element predicate that asks nothing of an entry still asks for one.
  'label.filter.any-entry': 'has any entry',
  'label.filter.range-from': '{field} from',
  'label.filter.range-to': '{field} to',
  // What stands between the two ends of a range, in the editor and in the
  // applied summary alike — one condition, punctuated the same way wherever
  // it is read. It is drawn `aria-hidden`, because each end is already named
  // by the two keys above.
  'label.filter.range-join': '~',
  // A list of values that grows: what is being typed, the popup that offers
  // it back as the one thing to add, and one remove button per value. Every
  // pill on the panel has all three, so the first two are named after their
  // field the way the range's two ends are. The remove buttons stand side by
  // side and are told apart by nothing but the value they carry, so the value
  // is in the name and not only on the chip around it.
  'label.filter.new-value-of': 'New {field}',
  'label.filter.add-value-of': 'Add {field}',
  'label.filter.add-value': 'Add {value}',
  'label.filter.remove-value': 'Remove {value}',
  // A reference field's candidates come from the host's source, searched as
  // the user types (F-04): the box says what to do, the list says whether a
  // page is on its way, came back empty, or did not come back, and offers
  // the next page where the source has one.
  'label.filter.search-candidates': 'Type to search',
  'label.filter.candidates-loading': 'Searching…',
  'label.filter.no-candidate': 'No match',
  'label.filter.more-candidates': 'More',
  'label.filter.candidates-failed': 'The candidates could not be loaded',
  'label.filter.candidates-retry': 'Try again',
  // A text field's own values, counted from the data: the box takes a value
  // typed or picked, each listed value says how many records hold it (read
  // out whole, since a bare number beside a name says nothing), and the list
  // says when it is only the most frequent — the one wanted may be rarer.
  'label.filter.pick-or-type': 'Pick or type a value',
  // The view's search box on the title bar.
  'label.search.hint': 'Press Enter to search.',
  'label.search.clear': 'Clear the search',
  'label.filter.value-count': '{value} ({count} records)',
  'label.filter.values-loading': 'Reading the values…',
  'label.filter.values-failed': 'The values could not be read: {reason}',
  'label.filter.values-top':
    'Only the most frequent values are listed; type to narrow',
  // What an empty list's entry box says: the whole gesture, because this is
  // the one control on the panel where a key commits the value and Enter is
  // written nowhere else on screen.
  'label.filter.type-to-add': 'Type a value and press Enter',
  // Why what is typed is not offered: the popup says so rather than staying
  // silently empty.
  'label.filter.already-listed': 'Already in the list',
  'label.filter.not-a-number': 'Not a number',
  // The three answers of a deletion condition; the stored value is Wow's
  // `DeletionState`, and these are its words.
  'label.deletion.active': 'Not deleted',
  'label.deletion.deleted': 'Deleted only',
  'label.deletion.all': 'Deleted included',
  'label.boolean.true': 'True',
  'label.boolean.false': 'False',
  'label.filter.too-large': 'This filter is too large to edit here.',

  // The conditions the result in front of you actually ran with, which is
  // not the draft above it.
  'label.applied.title': 'Showing',
  'label.applied.all': 'All records',
  // A condition the host put in force, which the reader cannot take out:
  // it is not in the draft, and no path of this editor addresses it.
  'label.applied.scoped': 'Set by the page',
  // A reading the source applies when nothing was written: said, since the
  // rows on screen obey it (D17-2).
  'label.applied.implied': 'By default',

  // The three date shapes, the side of now a relative window lies on, and
  // the controls that edit them.
  // Absolute dates are one date or two (`BETWEEN`), so the name holds both;
  // 「某一天」 sat over a month-long range (the 2026-09-23 dashboard walk).
  'label.date.absolute': 'Specific dates',
  'label.date.relative': 'Relative',
  'label.date.preset': 'A period',
  'label.date.past': 'In the last',
  'label.date.future': 'In the next',
  'label.date.pick': 'Pick a date',
  'label.date.shape-of': '{field} kind',
  'label.date.direction-of': '{field} direction',
  'label.date.amount-of': '{field} amount',
  'label.date.unit-of': '{field} unit',
  'label.date.period-of': '{field} period',

  // The time of day beside the calendar, on a field that carries one. The
  // hint is the control's whole point: an empty box is the day itself, read
  // as an interval, and nobody can guess that from an empty box.
  'label.date.time': 'Time',
  'label.date.time-from': 'From time',
  'label.date.time-to': 'To time',
  'label.date.time-hint':
    'Left empty, a day runs from 00:00:00 through 23:59:59.999.',

  // The calendar's own chrome. Everything it draws that is a date — the
  // caption, the weekday heads, the day numbers, the name a day button
  // answers to — is formatted through `Intl` in the surface's language
  // (`ui/filter/inputs/calendar.tsx`); these four are the words around them,
  // which no formatter can produce. `react-day-picker` says them in English
  // whatever locale it is given, so leaving them to it left `Go to the Next
  // Month` on a 简体中文 surface.
  'label.date.calendar-previous': 'Go to the previous month',
  'label.date.calendar-next': 'Go to the next month',
  'label.date.calendar-today': 'Today, {date}',
  'label.date.calendar-selected': '{date}, selected',

  // The two closed sets the relative-date control offers. Both used to be
  // rendered as the identifier itself — `hour`, `thisWeek` — so the control
  // stayed in English however the catalogue was replaced, exactly as the
  // operator select did before `label.operator.*` covered its enum. The
  // units keep the spelling the control already showed; the periods keep the
  // spelling the summary bar already read them out by, which is the same
  // word spaced out. `test/messages.test.tsx` walks both sets.
  // A distance from now is two different conditions, and the badge has to
  // tell them apart: `BETWEEN` asks for the span between now and there,
  // `GTE`/`LTE` compare against the moment at the far end of it. "In the
  // last 7 days" where "7 days ago" was meant names a span the query never
  // ran over. These are the phrases the summary line always read.
  'label.relative.window.past': 'last {amount} {unit}',
  'label.relative.window.future': 'next {amount} {unit}',
  'label.relative.instant.past': '{amount} {unit} ago',
  'label.relative.instant.future': '{amount} {unit} ahead',
  'label.relative.unit.hour': 'hour',
  'label.relative.unit.day': 'day',
  'label.relative.unit.week': 'week',
  'label.relative.unit.month': 'month',
  'label.relative.unit.quarter': 'quarter',
  'label.relative.unit.year': 'year',
  'label.relative.preset.today': 'today',
  'label.relative.preset.yesterday': 'yesterday',
  'label.relative.preset.tomorrow': 'tomorrow',
  'label.relative.preset.thisWeek': 'this week',
  'label.relative.preset.lastWeek': 'last week',
  'label.relative.preset.nextWeek': 'next week',
  'label.relative.preset.thisMonth': 'this month',
  'label.relative.preset.lastMonth': 'last month',
  'label.relative.preset.nextMonth': 'next month',
  'label.relative.preset.thisQuarter': 'this quarter',
  'label.relative.preset.lastQuarter': 'last quarter',
  'label.relative.preset.nextQuarter': 'next quarter',
  'label.relative.preset.thisYear': 'this year',
  'label.relative.preset.lastYear': 'last year',
  'label.relative.preset.nextYear': 'next year',

  // Every `FilterOperator`, in the order the enum declares them.
  //
  // Half of them were left out on the grounds that `EQ` and `BETWEEN` read
  // acceptably derived from the enum name. They do — in English. A catalogue
  // that names only part of a closed set cannot be translated at all, which
  // is what `messages={zhCN}` showed: the operator select still offered `eq`
  // and `between`, because no key existed for a Chinese word to hang on. So
  // the set is named in full.
  //
  // The derived spelling was then kept for the ones that had it, and `eq`,
  // `ne`, `gte` and `lte` are not English — they are the enum in lower case.
  // A condition reads `Amount eq Not set`, and the date select offered
  // `between / gte / lte / is empty`: half sentence, half code, where the
  // Chinese catalogue had said 「等于」／「大于等于」 all along. Every one of
  // these is now the phrase the row reads as — field, operator, value — so
  // `Amount at least 100` is a sentence in both catalogues.
  //
  // `test/messages.test.tsx` walks `FilterOperator` and fails on a gap, so
  // this list cannot drift behind the wow enum.
  'label.operator.MATCH_ALL': 'matches every record',
  'label.operator.MATCH_NONE': 'matches no record',
  'label.operator.ID': 'is',
  'label.operator.IDS': 'is any of',
  'label.operator.AGGREGATE_ID': 'is',
  'label.operator.AGGREGATE_IDS': 'is any of',
  'label.operator.TENANT_ID': 'is',
  'label.operator.OWNER_ID': 'is',
  'label.operator.SPACE_ID': 'is',
  'label.operator.AND': 'and',
  'label.operator.OR': 'or',
  'label.operator.NOR': 'nor',
  'label.operator.EQ': 'is',
  'label.operator.NE': 'is not',
  // A comparison is read off the value beside it, so these say which side of
  // it the answer lies on rather than naming the symbol: "at least" and "at
  // most" carry the inclusive edge that `gte`/`lte` only spelled.
  'label.operator.GT': 'more than',
  'label.operator.GTE': 'at least',
  'label.operator.LT': 'less than',
  'label.operator.LTE': 'at most',
  'label.operator.CONTAINS': 'contains',
  'label.operator.STARTS_WITH': 'starts with',
  'label.operator.ENDS_WITH': 'ends with',
  'label.operator.IN': 'is any of',
  'label.operator.NOT_IN': 'is none of',
  'label.operator.BETWEEN': 'between',
  'label.operator.CONTAINS_ALL': 'has all of',
  'label.operator.IS_EMPTY': 'has no entries',
  'label.operator.IS_EMPTY_STRING': 'is blank',
  'label.operator.IS_NOT_EMPTY_STRING': 'is not blank',
  'label.operator.IS_NULL': 'is empty',
  'label.operator.IS_NOT_NULL': 'is not empty',
  'label.operator.EXISTS': 'exists',
  'label.operator.NOT_EXISTS': 'does not exist',
  'label.operator.DELETION': 'is',
  'label.operator.ELEMENT_MATCH': 'has an entry where',
  'label.operator.SEARCH': 'contains',
  'label.operator.TODAY': 'today',
  'label.operator.BEFORE_TODAY': 'before today',
  'label.operator.TOMORROW': 'tomorrow',
  'label.operator.THIS_WEEK': 'this week',
  'label.operator.NEXT_WEEK': 'next week',
  'label.operator.LAST_WEEK': 'last week',
  'label.operator.THIS_MONTH': 'this month',
  'label.operator.LAST_MONTH': 'last month',
  'label.operator.YESTERDAY': 'yesterday',
  'label.operator.NEXT_MONTH': 'next month',
  'label.operator.LAST_YEAR': 'last year',
  'label.operator.THIS_YEAR': 'this year',
  'label.operator.NEXT_YEAR': 'next year',
  'label.operator.RECENT_DAYS': 'recent days',
  'label.operator.EARLIER_DAYS': 'earlier days',

  // Filter kernel.
  'filter.field.reference-without-source':
    '{field} is a reference field with no candidate source declared.',
  'filter.field.duplicate-in-group':
    '{field} is already a condition in this group. Nest a group to ask it something else.',
  'filter.field.unknown': 'The field {field} no longer exists.',
  'filter.group.unknown-operator': 'A condition group must be AND or OR.',
  'filter.kind.unregistered': 'No editor is registered for the {kind} type.',
  'filter.kind.unknown-editor':
    'The {kind} type asks for a {input} editor, which this engine does not have.',
  'filter.node.invalid': 'This condition could not be read.',
  'filter.operator.unsupported': '{field} does not support {operator}.',
  'filter.tree.too-deep': 'The conditions nest deeper than {max} levels.',
  'filter.tree.too-many-nodes': 'The conditions exceed {max} entries.',
  'filter.value.expected-boolean': 'Choose yes or no.',
  'filter.value.expected-deletion-state':
    'Choose which records to show: not deleted, deleted only, or both.',
  'filter.value.expected-date': 'Enter a date.',
  'filter.value.expected-id': 'Enter an id, or pick a candidate.',
  'filter.value.expected-id-list': 'Enter one or more ids.',
  'filter.value.expects-one': 'This condition takes a single value.',
  'filter.value.expected-text': 'Type what to search for.',
  'filter.element.root-filter':
    '{field} asks about the whole record, so it cannot be asked of one entry.',
  'filter.value.expected-predicate': 'Describe what an entry must match.',
  'filter.field.holds-no-elements':
    '{field} holds no entries to match against.',
  'filter.value.expected-entry-list': 'Choose or enter one or more entries.',
  'filter.value.expected-number': 'Enter a number.',
  'filter.value.expected-number-list': 'Enter one or more numbers.',
  'filter.value.expected-number-range': 'Enter a range of two numbers.',
  'filter.value.expected-option-list': 'Choose one or more options.',
  'filter.value.expected-reference-list': 'Choose one or more records.',
  'filter.value.expected-string': 'Enter a value.',
  'filter.value.expected-string-list': 'Enter one or more values.',
  'filter.value.inverted-range': 'The range starts after it ends.',
  'filter.value.relative-too-large':
    'A relative window can reach at most {max} units.',
  'filter.value.required': 'This condition needs a value.',
  'filter.value.unknown-option': '{values} is no longer an option.',
  'filter.value.unknown-time-zone':
    '{timeZone} is not a time zone this browser knows.',
  'filter.value.unparsable-date': 'That date cannot be read.',
} as const satisfies Record<string, string>;
