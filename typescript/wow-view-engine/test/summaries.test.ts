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

import { describe, expect, it } from 'vitest';
import { toSummary } from '../src/index.js';
import { SummaryCache } from '../src/runtime/summaries.js';
import { savedInstance } from './fixtures.js';

/**
 * The cache the engine consults before the store for a command it was given
 * only an id of. Its rules used to be four lines spread over `ViewEngine`;
 * they are one module and this suite now (A8).
 */
describe('SummaryCache', () => {
  it('knows nothing it was never told', () => {
    expect(new SummaryCache().get('missing')).toBeUndefined();
  });

  it('remembers a whole listing at once', () => {
    const cache = new SummaryCache();
    const one = toSummary(savedInstance({ id: 'one', title: 'One' }));
    const two = toSummary(savedInstance({ id: 'two', title: 'Two' }));

    cache.noteAll([one, two]);

    expect(cache.get('one')).toEqual(one);
    expect(cache.get('two')).toEqual(two);
  });

  /**
   * A write confirms an instance, not a summary, and what the next command
   * needs off it is the revision it must write against. Keeping the one the
   * listing carried would send every save after the first against a revision
   * the store has already moved past.
   */
  it('takes the summary of a confirmed write, revision and all', () => {
    const cache = new SummaryCache();
    cache.noteAll([toSummary(savedInstance({ id: 'one', revision: '1' }))]);

    cache.note(savedInstance({ id: 'one', title: 'Renamed', revision: '2' }));

    expect(cache.get('one')).toMatchObject({ title: 'Renamed', revision: '2' });
  });

  it('forgets a view that was deleted', () => {
    const cache = new SummaryCache();
    cache.noteAll([toSummary(savedInstance({ id: 'one' }))]);

    cache.drop('one');

    expect(cache.get('one')).toBeUndefined();
  });

  it('is unmoved by a delete of something it never held', () => {
    const cache = new SummaryCache();
    const kept = toSummary(savedInstance({ id: 'one' }));
    cache.noteAll([kept]);

    cache.drop('another');

    expect(cache.get('one')).toEqual(kept);
  });
});
