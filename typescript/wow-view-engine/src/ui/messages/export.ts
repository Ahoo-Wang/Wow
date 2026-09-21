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
 * The export menu: its three scopes, what it says while it runs, and the one
 * question it asks first.
 *
 * Every scope carries its count, because the three of them differ by nothing
 * else — "this page" and "everything" are the same words about different
 * numbers, and the number is what makes the choice. "All" has a second
 * wording for a source that reports no total: a count nobody can compute is
 * left unsaid rather than guessed at from the page on screen.
 */
export const exportMessages = {
  'label.export.title': 'Export',
  'label.export.selected': 'Export selected ({count})',
  'label.export.page': 'Export this page ({count})',
  'label.export.all': 'Export all ({count}, under the current conditions)',
  'label.export.all-unknown': 'Export all (under the current conditions)',
  'label.export.running': 'Exporting',
  'label.export.progress': '{fetched} of {total} fetched',
  'label.export.progress-unknown': '{fetched} fetched',
  'label.export.cancel': 'Cancel',
  // The question, asked before anything is fetched: the count is what makes
  // "this is a lot" a decision rather than a wait nobody agreed to. It says
  // what the file will hold, because the ceiling still applies afterwards.
  'label.export.over-limit': 'Export {count} records?',
  'label.export.over-limit-body':
    'The conditions match {count} records, more than the {max} one export carries. The file will hold the first {max}.',
  'label.export.over-limit-confirm': 'Export the first {max}',

  // What an export has to report about itself.
  'export.failed': 'The export failed. {reason}',
  'export.capped': 'The file stops at the first {count} records.',
} as const satisfies Record<string, string>;
