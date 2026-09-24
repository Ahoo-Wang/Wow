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
 * The export window: the two scopes it offers, what the file will hold, how
 * far it has got, and how it ended (D14).
 *
 * Each scope carries its count, because the two of them differ by nothing
 * else — "the ones I picked" and "everything that matches" are the same words
 * about different numbers, and the number is what makes the choice. "All" has
 * a second wording for a source that reports no total: a count nobody can
 * compute is left unsaid rather than guessed at from the page on screen.
 */
export const exportMessages = {
  'label.export.title': 'Export',
  'label.export.description': 'The result leaves as a CSV file.',

  // The choice, and the two readings of it.
  'label.export.scope': 'What to export',
  'label.export.selected': 'Selected ({count})',
  'label.export.all': 'All ({count}, under the current conditions)',
  'label.export.all-unknown': 'All (under the current conditions)',

  // What the file will hold, said before it is made rather than found out
  // afterwards by opening it. The conditions are named the way the applied
  // band names them, and the columns are the ones the table is drawing —
  // both are already on screen, and repeating them here is what makes the
  // file the thing in front of you rather than a guess about it.
  'label.export.rows': '{count} records',
  'label.export.rows-unknown': 'Whatever the current conditions match',
  'label.export.conditions': 'Conditions: {conditions}',
  'label.export.columns': '{count} columns: {names}',
  'label.export.file': 'File: {name}',
  // The ceiling, put before the button rather than after the download:
  // pressing Export is the consent, so what is consented to has to be on
  // screen. It replaces the separate question the menu used to ask.
  'label.export.over-limit':
    'That is more than the {max} one export carries; the file will hold the first {max}.',
  'label.export.confirm': 'Export',

  // While it runs. The bar is the `progressbar`, and the count beside it is
  // what is announced as it changes.
  'label.export.running': 'Exporting',
  'label.export.progress': '{fetched} of {total} fetched',
  'label.export.progress-unknown': '{fetched} fetched',

  // How it ended. A file the ceiling cut short is not a failure — it is the
  // file that was agreed to — so it is said here rather than as a warning.
  'label.export.done': '{count} records exported',
  'label.export.done-capped':
    'The file holds the first {max} of the {total} records that match.',
  'label.export.done-capped-unknown':
    'The file holds the first {max} records; more match than that.',
  'label.export.retry': 'Try again',

  // An analysis's file (D25 Q28): its rows are groups, in hand, so there is
  // no scope to pick and the window says what the file holds instead — the
  // first N groups where more exist, and the totals row where it is shown.
  'label.export.groups': '{count} groups',
  'label.export.groups-one': '1 group',
  'label.export.groups-first':
    'The first {count} groups (there are more; the file leaves them out)',
  'label.export.whole': '1 row: every record in the range',
  'label.export.and-totals': '{rows}, then a totals row',
  'label.export.done-analysis': 'Exported: {rows}',

  // What a failed export has to say, wherever it is said.
  'export.failed': 'The export failed. {reason}',
} as const satisfies Record<string, string>;
