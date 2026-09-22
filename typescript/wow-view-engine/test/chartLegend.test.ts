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
import { legendPlacement } from '../src/ui/charts/legend.js';

describe('legendPlacement', () => {
  it('leaves the family to decide while nobody has said where', () => {
    // No setting is `auto`, and `auto` is the family's own answer: a pie
    // always has one, a cartesian chart only once there is a second series
    // to tell apart.
    expect(legendPlacement(undefined, true)).toEqual({
      props: { verticalAlign: 'bottom' },
    });
    expect(legendPlacement(undefined, false)).toBeUndefined();
    expect(legendPlacement('auto', true)).toEqual({
      props: { verticalAlign: 'bottom' },
    });
    expect(legendPlacement('auto', false)).toBeUndefined();
  });

  it('takes the analyst’s word over the family’s', () => {
    // A side the analyst chose stands even where the family would have
    // drawn none, and `none` removes one the family would have drawn.
    expect(legendPlacement('top', false)).toEqual({
      props: { verticalAlign: 'top' },
    });
    expect(legendPlacement('bottom', false)).toEqual({
      props: { verticalAlign: 'bottom' },
    });
    expect(legendPlacement('none', true)).toBeUndefined();
  });

  it('stands a legend on the right as a column', () => {
    // The chart library puts a legend in a row by default, so the one side
    // that is not a row has to say so twice: to the library, and to the
    // markup the entries are drawn in.
    const right = legendPlacement('right', false);
    expect(right?.props).toEqual({
      verticalAlign: 'middle',
      align: 'right',
      layout: 'vertical',
    });
    expect(right?.className).toContain('flex-col');
  });
});
