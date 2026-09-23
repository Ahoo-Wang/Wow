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
 * What a host's own bulk command came to. The unit is the one the selection
 * is counted in (`label.toolbar.selected`), because this line is about the
 * records that selection held.
 */
export const bulkMessages = {
  'label.bulk.done': '{done} done',
  'label.bulk.partial': '{done} done, {failed} failed',
  'label.bulk.failed': '{failed} failed',
  'label.bulk.skipped': '{skipped} not run',
  // One of the source's reasons, and how many records gave it.
  'label.bulk.reason': '{reason} ({count})',
  'label.bulk.more-reasons': '{count} more reasons',
  'label.bulk.more-reasons-one': 'one more reason',
  // What was not done stays selected, ready to be dealt with.
  'label.bulk.left': 'the rest stay selected',
  'label.bulk.running': 'Running {done} of {total}',
  'label.bulk.running-failed': 'Running {done} of {total}, {failed} failed',
  // Stopping starts nothing more; what is in flight still lands.
  'label.bulk.stop': 'Stop',
  'label.bulk.stopping': 'Stopping…',
  // Nothing here expires on its own, so every outcome carries its own way
  // out — the same one a refused write offers, in the same words.
  'label.bulk.dismiss': 'Dismiss',
} as const;
