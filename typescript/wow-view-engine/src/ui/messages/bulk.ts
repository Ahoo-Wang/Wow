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
  'label.bulk.failed': '{failed} failed',
  // Sent, with no answer back: it may have taken. Checked, not run again.
  'label.bulk.unknown':
    '{unknown} with outcome unknown, refresh to check first',
  // Not sent: refused when its turn came, or never started after a stop.
  'label.bulk.skipped': '{skipped} not run',
  // One record's command names the record rather than counting it.
  'label.bulk.one-done': '{record} done',
  'label.bulk.one-failed': '{record} failed: {reason}',
  'label.bulk.one-refused': '{record} not run: {reason}',
  'label.bulk.one-unknown': '{record}: outcome unknown, refresh to check first',
  'label.bulk.one-skipped': '{record} not run',
  // One of the source's reasons, and how many records gave it.
  'label.bulk.reason': '{reason} ({count})',
  'label.bulk.more-reasons': '{count} more reasons',
  'label.bulk.more-reasons-one': 'one more reason',
  // What was not done stays selected, ready to be dealt with.
  'label.bulk.left': 'the failed and the not run stay selected',
  'label.bulk.running': 'Running {done} of {total}',
  'label.bulk.running-failed': 'Running {done} of {total}, {failed} failed',
  // Stopping starts nothing more; what is in flight still lands — unless
  // the reader stops waiting for it, and its outcome is then unknown.
  'label.bulk.stop': 'Stop',
  'label.bulk.stopping': 'Stopping…',
  'label.bulk.stop-waiting': 'Stop waiting',
  // Nothing here expires on its own, so every outcome carries its own way
  // out — the same one a refused write offers, in the same words.
  'label.bulk.dismiss': 'Dismiss',
} as const;
