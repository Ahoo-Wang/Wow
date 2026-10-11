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
import { saidOnce } from '../src/ui/analysis/optionControls.js';

describe('saidOnce', () => {
  it('says a reason the boxes either side share once, under the last of them', () => {
    expect(saidOnce(['a', 'a', undefined, 'b', 'c', 'c', 'c'], 'g')).toEqual([
      { describedBy: 'g-0' },
      { describedBy: 'g-0', under: { id: 'g-0', text: 'a' } },
      {},
      { hint: 'b' },
      { describedBy: 'g-4' },
      { describedBy: 'g-4' },
      { describedBy: 'g-4', under: { id: 'g-4', text: 'c' } },
    ]);
  });

  it('leaves a reason no neighbour shares as the box’s own', () => {
    expect(saidOnce(['a', 'b', 'a'], 'g')).toEqual([
      { hint: 'a' },
      { hint: 'b' },
      { hint: 'a' },
    ]);
  });
});
