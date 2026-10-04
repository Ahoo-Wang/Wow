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
 * The declared actions' machinery (host-integration.md 5): the words the
 * engine says around a host's commands — the row's menu, the selection's
 * button, the question before a command goes to many, the records that
 * will not take it and why, a form's fields. The commands' own names and
 * reasons are the host's (`text(key)`).
 */
export const actionsMessages = {
  // The row's overflow menu, named by the record it acts on.
  'label.action.more': 'Actions for {record}',
  // The selection's primary action counts what it is for.
  'label.action.bulk': '{action} {count}',
  // …and how many of them take it, where not all do.
  'label.action.bulk-able': '{action} {able}/{count}',
  // The question a selection asks when the host words none of its own.
  'label.action.confirm': 'Run “{action}” on {count} records?',
  // One record is named, not counted: the reader confirms the one they meant.
  'label.action.confirm-record': 'Run “{action}” on {record}?',
  'label.action.on-record': '{action}: {record}',
  'label.action.record': 'Record {record}',
  // Said under the question when a selection is asked: nothing is lost.
  'label.action.left': 'The ones refused stay selected, with the reason.',
  'label.action.able': '{able} of {count} can take it.',
  'label.action.none-able': 'None of the {count} can take it now.',
  'label.action.refused': 'Not sent, and left selected:',
  // A few of the refused records by key, and how many more.
  'label.action.refused-keys-more': '{keys} and {count} more',
  // Narrows the selection to the ones that can: one press, not a hunt.
  'label.action.only-able': 'Only the {count} that can',
  'label.action.only-able-one': 'Only the one that can',
  'label.action.cancel': 'Cancel',
  'label.action.required': 'Required',
  // Why a record is not sent, where the host said nothing of its own.
  'label.action.unseen': 'Not on the page in view',
  'label.action.not-offered': 'Not offered for this record',
  'label.action.unavailable': 'Not available now',
  // A failure whose error carries no words.
  'label.action.failed': 'Failed, with no reason given',
  // Why a record's outcome is unknown.
  'label.action.timed-out': 'No answer in time',
  'label.action.abandoned': 'Stopped waiting',
  // Told to the host in development, never shown to a reader: a rule read
  // a field the rows do not fetch, so it reads as empty on every row.
  'record.action.unfetched':
    'Action “{action}” reads {field}, which the rows of this view do not fetch: it reads as empty on every row. List it in the definition’s record.rowFields.',
} as const;
