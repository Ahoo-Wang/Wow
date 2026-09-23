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
import { legendAt } from '../src/ui/charts/legend.js';

describe('legendAt', () => {
  it('leaves the family to decide while nobody has said where', () => {
    // No setting is `auto`, and `auto` is the family's own answer: on top
    // once there is a second series to tell apart, and none before that.
    expect(legendAt(undefined, true)).toBe('top');
    expect(legendAt(undefined, false)).toBeUndefined();
    expect(legendAt('auto', true)).toBe('top');
    expect(legendAt('auto', false)).toBeUndefined();
    // A pie keeps its key beside it.
    expect(legendAt('auto', true, 'right')).toBe('right');
  });

  it('takes the analyst’s word over the family’s', () => {
    // A side the analyst chose stands even where the family would have
    // drawn none, and `none` removes one the family would have drawn.
    expect(legendAt('top', false)).toBe('top');
    expect(legendAt('bottom', false)).toBe('bottom');
    expect(legendAt('right', false)).toBe('right');
    expect(legendAt('none', true)).toBeUndefined();
  });
});
