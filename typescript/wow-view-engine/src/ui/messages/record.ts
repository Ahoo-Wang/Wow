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

/** The record view: its layouts, toolbar, paging, rows and summaries. */
export const recordMessages = {
  // Layout switches.
  'label.layout.table': 'Table',
  'label.layout.chart': 'Chart',
  'label.layout.cards': 'Cards',

  // The card settings, behind the same button as the column settings once
  // the cards are showing. A card is a row folded out, and its four
  // questions are said in the order a card is read: what names it, what it
  // shows, its picture, how many stand in a row.
  'label.card.title': 'Card settings',
  'label.card.hint': 'Cards take their title, body and image from here.',
  'label.card.title-field': 'Title',
  'label.card.image-field': 'Image',
  'label.card.no-image': 'No image',
  'label.card.fields': 'Body',
  'label.card.per-row': 'Cards per row',
  'label.card.per-row-option': '{count} per row',

  // The record toolbar. The bar itself has a name because it is one stop
  // with the arrows inside it: a reader that lands there is told what it
  // has landed in before it starts moving along it.
  'label.toolbar.title': 'Result toolbar',
  'label.toolbar.layout': 'Layout',
  'label.toolbar.columns': 'Columns',
  // The same button under the card layout: what a card shows is the
  // question the column settings answer for a row (D18 VI).
  'label.toolbar.card': 'Card settings',
  'label.toolbar.refresh': 'Refresh',
  // The two groups on the right of the toolbar, named so a screen reader
  // hears what a group is for rather than "group, group".
  'label.toolbar.arrange': 'Table settings',
  'label.toolbar.freshness': 'Freshness',
  'label.toolbar.selected': '{count} selected',
  'label.toolbar.clear-selection': 'Clear selection',
  'label.toolbar.actions': 'Actions',

  // The pagination bar under the rows. `label.toolbar.page` is the
  // count-less form: a total the source never gave cannot be divided into
  // pages, so the page reached is said on its own.
  'label.toolbar.page': 'Page {index}',
  'label.toolbar.page-of': 'Page {index} of {pages}',
  'label.toolbar.previous': 'Previous page',
  'label.toolbar.next': 'Next page',

  // The bar itself, which is a landmark: it is the one group of controls
  // that moves a reader through a result, so it says what it is before it
  // says anything else.
  'label.pagination.nav': 'Pagination',
  // The count the bar opens with. A total comes from the query and a cursor
  // view has none, so the two are worded apart: with a total the bar says how
  // many there are, and without one it says only what it can see.
  'label.pagination.total': '{count} records in all',
  'label.pagination.total-one': '1 record in all',
  'label.pagination.on-page': '{count} on this page',
  // The words before the size control and the sizes it offers. The unit
  // rides with the number, which is what a Chinese measure word needs —
  // `每页` + `20 条`, never `每页 20`.
  'label.pagination.page-size': 'Per page',
  'label.pagination.page-size-option': '{size} per page',
  // The box beside the page sentence, where the total is known (ruling Ⅷ).
  // It names what typing in it does rather than what it holds: the sentence
  // next to it already says which page the rows came from.
  'label.pagination.go-to': 'Go to page',
  // Said when the source's window (`RecordCapability.maxWindow`) stops the
  // pages short of the total: how far paging goes, and the way to the rest,
  // which is the conditions and not this bar.
  'label.pagination.window':
    'Only the first {count} records can be paged through; narrow the conditions to see the rest',

  // The record view itself.
  'label.record.empty': 'Nothing to show',
  'label.record.empty-hint': 'No record matches the current conditions.',
  // The one way out of an empty result, which of the two depending on
  // whether conditions are what emptied it.
  'label.record.empty-clear': 'Clear the conditions',
  'label.record.empty-add': 'Add a condition',
  'label.record.empty-restore': 'Back to the saved conditions',
  'label.record.empty-edit': 'Change the conditions',
  'label.record.empty-view': 'This view has no records right now.',
  'label.record.empty-none': 'There are no records yet.',
  // The source lists records only under a condition (Q3).
  'label.record.filter-required': 'Add a condition first',
  'label.record.filter-required-hint':
    'This data source does not list every record at once. Add a condition to narrow what it lists.',
  'label.record.select-all': 'Select all rows',
  'label.record.select': 'Select {key}',
  // Said as every row checkbox's description, once per surface: the range
  // a Shift+press makes is otherwise invisible until it has been made.
  'label.record.select.hint':
    'Hold Shift to select or clear every record from the last one you picked to this one.',
  'label.record.detail': 'Record detail',
  'label.record.detail.hint':
    'Enter or Space opens the record; the arrow keys move between records.',
  'label.record.detail.loading': 'Reading the whole record…',
  'label.record.detail.missing':
    'This record is no longer there — it may have been deleted, or it is outside what you can see.',
  'label.record.detail.partial': 'Showing the fields the list had.',
  'label.record.detail.other': 'Other',
  'label.record.detail.element': 'Item {index}',
  'label.record.detail.retry': 'Try again',
  // A detail opened from a list inside another one: the way back to the
  // record underneath, named by its key.
  'label.record.detail.back': 'Back to {key}',

  // A copyable cell's own button, and the two words a press can come back
  // with. The name carries the value because a table full of these buttons
  // is a table full of one name otherwise: what a reader needs to hear is
  // which document number this one would take away.
  'label.copy-of': 'Copy {value}',
  'label.copied': 'Copied',
  // Said rather than swallowed: when neither the Clipboard API nor the
  // `copy` command takes the value, a button that goes quiet reads as one
  // that worked. The value stays selectable text either way.
  'label.copy-failed': 'Could not copy — select the value and copy it',

  'label.summary.of': '{fn} of {field}',
  'label.value.yes': 'Yes',
  'label.value.no': 'No',
  // What an array of objects or an object holds, when the definition names
  // no element title to read it by: counted, never written out as JSON.
  // The catalogue has no plural rule, so the one is its own key.
  'label.value.items': '{count} items',
  'label.value.items-one': '1 item',
  'label.value.fields': '{count} fields',
  'label.value.fields-one': '1 field',
  // An aggregate of money whose records are in several currencies: no
  // amount, so it says what it is instead of a number.
  'label.value.mixed-currencies': 'Mixed currencies',
  // An element whose title field is empty: still an element, still counted.
  'label.value.untitled': 'Untitled',
  // How many elements a table cell leaves to its title and the card.
  'label.value.more': '+{count}',

  // Sorting from the headers. The name says what a click does rather than
  // what the column is called, because that is what the button is for; the
  // column's own label is inside the sentence, so what is heard still
  // contains what is seen. `label.sort.at` is appended to it while several
  // columns are sorted, which is the only time a position means anything.
  'label.sort.ascending': 'Sort by {field}, ascending',
  'label.sort.descending': 'Sort by {field}, descending',
  'label.sort.none': 'Stop sorting by {field}',
  // An analysis's third press: back to the order the presses began from.
  'label.sort.restore': 'Back to the order before sorting by {field}',
  // Said after the action while the header's column waits for Apply in
  // another direction than the arrow, which says what ran.
  'label.sort.waiting.asc': 'ascending waits for Apply',
  'label.sort.waiting.desc': 'descending waits for Apply',
  'label.sort.waiting.none': 'unsorted waits for Apply',
  'label.sort.at': 'sort {position} of {count}',
  // Said as the header button's description: the plain click sorts by this
  // column alone, and the way to add a column is otherwise invisible.
  'label.sort.additive': 'Hold Shift to add to the sort',

  // A summary's scope belongs on screen: `all` comes from its own query over
  // everything the conditions match, `page` only from the rows in front of
  // you, and the two are not interchangeable. `label.summary.total` is the
  // analysis table's totals row and stays its own wording.
  //
  // The scope is the other label's twin — "This page" against everything —
  // so it says rows, where `label.applied.all` says records: one names the
  // two halves of a footer, the other says what the conditions let through
  // (user ruling 2026-09-22, Ⅱ).
  'label.summary.total': 'Total',
  'label.summary.scope.page': 'This page',
  'label.summary.scope.total': 'All rows',
  'label.summary.fn.none': 'No summary',
  'label.summary.fn.SUM': 'Sum',
  'label.summary.fn.AVG': 'Average',
  'label.summary.fn.MIN': 'Min',
  'label.summary.fn.MAX': 'Max',
  'label.summary.fn.COUNT': 'Count',
  // The same two functions over a column of moments. A date has no smallest
  // and no largest: it has a first and a last, and «Min of Created» reads as
  // a number that column never held. One word per meaning, so the number
  // keeps Min and Max and the moment gets its own pair; which of the two a
  // column speaks is decided by how it reads its cells (`display.ts`).
  'label.summary.fn.date.MIN': 'Earliest',
  'label.summary.fn.date.MAX': 'Latest',
  // The analysis view's summaries are this same set, not a second one
  // (D20: one meaning per word). A record column offers the first five; an
  // analysis metric offers these as well, and both compose their header with
  // `label.summary.of`, so "Average of Amount" says one thing on either
  // screen. They live here because `label.summary.*` is one prefix family.
  'label.summary.fn.STDDEV': 'Standard deviation',
  'label.summary.fn.VARIANCE': 'Variance',
  'label.summary.fn.DISTINCT_COUNT': 'Distinct count',
  'label.summary.fn.PERCENTILE': 'Percentile',
  // "Any one of them", and the stress is on *any*: the backend returns some
  // value from the group and does not promise the same one twice.
  //
  // Two wordings, because the two places have different room. A column header
  // composes this one through `label.summary.of` — «Any value of Amount» —
  // and a parenthesis there would be read on every row of the table; the
  // summary menu picks one choice out of six and is exactly where the caveat
  // belongs, so the item carries it and the card at rest spells it out
  // (`label.analysis.any-note`).
  // The value on a group's earliest and latest record (FIRST / LAST): an
  // opening and a closing value, as a period's are named.
  'label.summary.fn.FIRST': 'Opening value',
  'label.summary.fn.LAST': 'Closing value',
  'label.summary.fn.ANY': 'Any value',
  'label.summary.fn.ANY.item': 'Any value (not stable)',
  'label.summary.unavailable': '—',

  // The column settings. The two areas a column cannot leave are named by
  // the same words as the pin states, because they are the same fact: a
  // column is in the left area precisely because it is held on the left.
  //
  // The three sentences below each belong to one row and are drawn on it —
  // which column is gone, which checkbox is refused, which controls wait for
  // the column to be shown — rather than collected into a paragraph at the
  // top, where a reader has to work out which of them is about the row in
  // front of them. What is left at the top is the one thing no row can say:
  // that this list *is* the table's column order.
  'label.columns.title': 'Column settings',
  'label.columns.hint': 'The table draws its columns in the order listed here.',
  'label.columns.filtered': 'Clear the search to reorder columns.',
  'label.columns.drag': 'Reorder {field}',
  'label.columns.moved': '{field} moved to position {index} of {total}',
  'label.columns.picked': '{field} picked up',
  'label.columns.cancelled': 'Move cancelled; {field} stayed where it was',
  'label.columns.show': 'Show {field}',
  'label.columns.primary-required':
    'The row key is always shown: it says which record a row is.',
  // Ordering is no longer on the list: a switched-off column keeps its
  // place in the table's order, so its handle works like any other row's.
  // What it has no place for is a pin (it is held nowhere) or a summary
  // (there is no cell under a column the table does not draw).
  'label.columns.hidden': 'Show this column before pinning or summarising it.',
  // Said on the row itself, next to a marked icon: naming a colour would
  // make the colour the index, which is the very thing a reader who cannot
  // tell these two greys apart has no access to.
  'label.columns.unknown':
    'This column is not in the data any more; switch it off to take it out.',
  // A summary left behind on a field that is not a column: the config is
  // refused over it, and this row is the only place it can be taken back.
  'label.columns.summary-unknown':
    'Only a summary refers to this field, which is not in the data any more; switch it off to take the summary out.',
  'label.columns.keep-summary': 'Keep the summary of {field}',
  'label.columns.summary': 'Summary under {field}',
  // The header's own edge, which is a control rather than a line: it is
  // named by the column it sizes, because that is the only thing about it a
  // reader who cannot see where the pointer is could use.
  'label.columns.resize': 'Resize {field}',
  // The pin toggle's name — what it holds, not whether it is on, which is
  // `aria-pressed` now that there are two states rather than three (D19).
  'label.columns.pin': 'Pin {field}',
  // The two areas' headings, which are the two pin states said as the words
  // the toggle's announcement uses.
  'label.columns.pin.none': 'Not pinned',
  'label.columns.pin.left': 'Pinned left',
  // Said after the toggle's name while the cap has let this pin go (D17-4).
  'label.columns.pin-released': 'let go while the area is too narrow',
  // What the press did, said out loud after it: "pressed" names neither the
  // column nor the edge it is now held against. Both states are named — a
  // half-named set cannot be translated.
  'label.columns.pinned.none': '{field} is no longer pinned',
  'label.columns.pinned.left': '{field} is now pinned left',

  // The sort editor behind the toolbar's button. It says the direction in
  // one word, because it labels a row of a list rather than a button whose
  // whole job is one column; taking a field out of the sort is the header's
  // own `label.sort.none`, which already says exactly that.
  'label.sort.title': 'Sort',
  // The name of the toolbar's button once something is sorted, where its
  // words are the sort itself and nothing on it says what it is. Visible
  // text unchanged: this is the same string said the other way (D12).
  'label.sort.button': 'Sort: {field} {direction}',
  'label.sort.hint': 'Rows are ordered by the first field, then by the next.',
  'label.sort.unsorted': 'These rows are in no particular order.',
  'label.sort.asc': 'Ascending',
  'label.sort.desc': 'Descending',
  'label.sort.more': '+{count}',
  'label.sort.direction': 'Direction of {field}',
  'label.sort.add': 'Sort by a field',
  'label.sort.full': 'These rows can be ordered by at most {max} fields.',
  // The same editor over an analysis's groups (`SortSettings`'s `of`): it
  // sits under its own 'Sort' title, so the button says the state, and it
  // orders by dimensions and metrics rather than by fields.
  'label.sort.groups.none': 'Not sorted',
  'label.sort.groups.hint':
    'Groups are ordered by the first, then by the next where they tie.',
  'label.sort.groups.unsorted': 'These groups are in no particular order.',
  'label.sort.groups.add': 'Sort by a dimension or metric',
  'label.sort.groups.full':
    'Groups can be ordered by at most {max} dimensions and metrics.',
  // Which field comes first is the whole of what this list says, so the
  // order is something to be taken hold of rather than something rebuilt by
  // removing an entry and adding it again at the end.
  'label.sort.drag': 'Reorder {field}',
  'label.sort.moved': '{field} moved to position {index} of {total}',
  'label.sort.picked': '{field} picked up',
  'label.sort.cancelled': 'Move cancelled; {field} stayed where it was',

  // Record kernel.
  'record.capability.missing':
    '{definition} does not offer a record view any more.',
  'record.card.invalid': 'The card settings could not be read.',
  'record.column.duplicate': 'The column {field} is listed twice.',
  'record.column.hidden-invalid':
    'The column {field} is switched off as {hidden}, which is not how a column is switched off.',
  'record.column.pin-invalid':
    'The column {field} is pinned as {pinned}, which is not how a column is pinned.',
  'record.column.width-invalid':
    'The column {field} is {width} wide, which is not a number of pixels.',
  'record.field.not-a-column':
    '{field} is a search or metadata handle, not something a row holds.',
  'record.field.unknown': 'The column {field} no longer exists.',
  'record.layout.unsupported': 'The {layout} layout is not available here.',
  'record.filter.required':
    'This data source lists records only under a condition: add one first.',
  'record.pageSize.not-positive': 'The page size must be a positive number.',
  'record.pageSize.too-large': 'The page size cannot exceed {max}.',
  'record.sort.direction-invalid':
    'The sort on {field} reads neither ascending nor descending.',
  'record.sort.duplicate': 'The sort already orders by {field}.',
  'record.sort.parallel-arrays':
    'The source cannot sort by {fields} together: keep one of them.',
  'record.sort.invalid': 'The sort settings could not be read.',
  'record.sort.not-sortable': '{field} cannot be sorted on.',
  'record.sort.too-many': 'A cursor view sorts on at most {max} fields.',
  'record.summaries.invalid': 'The summary settings could not be read.',
  'record.summary.duplicate': 'The {fn} summary of {field} is listed twice.',
  'record.summary.unsupported': '{field} does not offer the {fn} summary.',
  'record.table.invalid': 'The table settings could not be read.',
} as const satisfies Record<string, string>;
