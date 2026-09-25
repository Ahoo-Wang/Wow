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
 * Auto refresh: the interval menu behind the refresh button's `▾`, and the
 * cadence the button wears while one is in force.
 *
 * The three units are three keys rather than one sentence with a unit in it:
 * a catalogue that named only "minute" would leave every other language
 * writing "5 minute" or reordering the number by hand.
 */
export const refreshMessages = {
  'label.refresh.auto': 'Auto refresh',
  'label.refresh.off': 'Off',
  'label.refresh.seconds': '{count}s',
  'label.refresh.minutes': '{count} min',
  'label.refresh.hours': '{count} h',
  // The cadence on the button as a sentence, for a screen reader: what is
  // drawn there is a fragment, and "Refresh 30s" is not a thing to say.
  'label.refresh.on': 'This view refreshes itself every {interval}.',
  // A dashboard times every panel at once, and the interval a panel's own
  // view was saved with is ignored inside it, so the menu says which one it
  // is setting — in the reader's words, not the runtime's.
  'label.refresh.panels':
    'Every panel on this dashboard refreshes together, on this interval; an interval saved with the view a panel shows does not apply here.',
  // When the numbers on an embedded board were read (`withRefresh`): the
  // earliest answer on screen, as a time of day — with the date when that
  // was another day.
  'label.refresh.read-at': 'Updated {time}',
} as const satisfies Record<string, string>;
