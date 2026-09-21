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

  // The record toolbar. The bar itself has a name because it is one stop
  // with the arrows inside it: a reader that lands there is told what it
  // has landed in before it starts moving along it.
  'label.toolbar.title': 'Result toolbar',
  'label.toolbar.layout': 'Layout',
  'label.toolbar.columns': 'Columns',
  'label.toolbar.refresh': 'Refresh',
  // The two groups on the right of the toolbar, named so a screen reader
  // hears what a group is for rather than "group, group".
  'label.toolbar.arrange': 'Table settings',
  'label.toolbar.freshness': 'Freshness',
  // Where the bulk actions will appear, said before anything is selected:
  // the left of the toolbar is theirs, and an empty left says nothing.
  'label.toolbar.hint': 'Select rows to act on them',
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
  'label.pagination.total': '{total} records in all',
  'label.pagination.on-page': '{count} on this page',
  // The words before the size control and the sizes it offers. The unit
  // rides with the number, which is what a Chinese measure word needs —
  // `每页` + `20 条`, never `每页 20`.
  'label.pagination.page-size': 'Per page',
  'label.pagination.page-size-option': '{size} per page',

  // The record view itself.
  'label.record.empty': 'Nothing to show',
  'label.record.empty-hint': 'No record matches the current conditions.',
  // The one way out of an empty result, which of the two depending on
  // whether conditions are what emptied it.
  'label.record.empty-clear': 'Clear the conditions',
  'label.record.empty-add': 'Add a condition',
  'label.record.select-all': 'Select all rows',
  'label.record.select': 'Select {key}',
  'label.summary.of': '{fn} of {field}',
  'label.value.yes': 'Yes',
  'label.value.no': 'No',

  // Sorting from the headers. The name says what a click does rather than
  // what the column is called, because that is what the button is for; the
  // column's own label is inside the sentence, so what is heard still
  // contains what is seen. `label.sort.at` is appended to it while several
  // columns are sorted, which is the only time a position means anything.
  'label.sort.ascending': 'Sort by {field}, ascending',
  'label.sort.descending': 'Sort by {field}, descending',
  'label.sort.none': 'Stop sorting by {field}',
  'label.sort.at': 'sort {position} of {count}',
  // Said as the header button's description: the plain click sorts by this
  // column alone, and the way to add a column is otherwise invisible.
  'label.sort.additive': 'Hold Shift to add to the sort',

  // A summary's scope belongs on screen: `all` comes from its own query over
  // everything the conditions match, `page` only from the rows in front of
  // you, and the two are not interchangeable. `label.summary.total` is the
  // analysis table's totals row and stays its own wording.
  'label.summary.total': 'Total',
  'label.summary.scope.page': 'This page',
  'label.summary.scope.total': 'All records',
  'label.summary.fn.none': 'No summary',
  'label.summary.fn.SUM': 'Sum',
  'label.summary.fn.AVG': 'Average',
  'label.summary.fn.MIN': 'Min',
  'label.summary.fn.MAX': 'Max',
  'label.summary.fn.COUNT': 'Count',
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
  'label.columns.instructions':
    'Press the arrow keys to move a column one place. Press space to pick it up, the arrow keys to move it, space again to drop it and escape to cancel.',
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
  'label.columns.pin': 'Pinning of {field}: {state}',
  'label.columns.pin.none': 'Not pinned',
  'label.columns.pin.left': 'Pinned left',
  'label.columns.pin.right': 'Pinned right',
  // Said after the pin state while the cap has let this pin go (D17-4).
  'label.columns.pin-released': 'let go while the area is too narrow',

  // The sort editor behind the toolbar's button. It says the direction in
  // one word, because it labels a row of a list rather than a button whose
  // whole job is one column; taking a field out of the sort is the header's
  // own `label.sort.none`, which already says exactly that.
  'label.sort.title': 'Sort',
  'label.sort.hint': 'Rows are ordered by the first field, then by the next.',
  'label.sort.unsorted': 'These rows are in no particular order.',
  'label.sort.asc': 'Ascending',
  'label.sort.desc': 'Descending',
  'label.sort.more': '+{count}',
  'label.sort.direction': 'Direction of {field}',
  'label.sort.add': 'Sort by a field',
  'label.sort.full': 'These rows can be ordered by at most {max} fields.',
  // Which field comes first is the whole of what this list says, so the
  // order is something to be taken hold of rather than something rebuilt by
  // removing an entry and adding it again at the end.
  'label.sort.instructions':
    'Press the arrow keys to move a sort field one place. Press space to pick it up, the arrow keys to move it, space again to drop it and escape to cancel.',
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
    'The column {field} is held to {pinned}, which is neither side.',
  'record.column.width-invalid':
    'The column {field} is {width} wide, which is not a number of pixels.',
  'record.field.not-a-column':
    '{field} is a search or metadata handle, not something a row holds.',
  'record.field.unknown': 'The column {field} no longer exists.',
  'record.layout.unsupported': 'The {layout} layout is not available here.',
  'record.pageSize.not-positive': 'The page size must be a positive number.',
  'record.pageSize.too-large': 'The page size cannot exceed {max}.',
  'record.sort.direction-invalid':
    'The sort on {field} reads neither ascending nor descending.',
  'record.sort.duplicate': 'The sort already orders by {field}.',
  'record.sort.invalid': 'The sort settings could not be read.',
  'record.sort.not-sortable': '{field} cannot be sorted on.',
  'record.sort.too-many': 'A cursor view sorts on at most {max} fields.',
  'record.summaries.invalid': 'The summary settings could not be read.',
  'record.summary.duplicate': 'The {fn} summary of {field} is listed twice.',
  'record.summary.unsupported': '{field} does not offer the {fn} summary.',
  'record.table.invalid': 'The table settings could not be read.',
} as const satisfies Record<string, string>;
