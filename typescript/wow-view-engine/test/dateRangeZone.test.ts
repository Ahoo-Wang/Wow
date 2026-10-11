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
import { readBound } from '../src/ui/filter/inputs/daterange.js';

describe('a stored instant in the date editor (R2-103)', () => {
  it('is read on the surface’s clock, the one its trigger reads it on', () => {
    // 16:00 in London, midnight the next day in Shanghai.
    expect(readBound('2026-01-31T16:00:00Z', true, 'Asia/Shanghai')).toEqual({
      day: '2026-02-01',
      time: '00:00',
    });
    expect(readBound('2026-01-31T16:00:00Z', true, 'Europe/London')).toEqual({
      day: '2026-01-31',
      time: '16:00',
    });
    // A field with no time of day keeps the day alone.
    expect(readBound('2026-01-31T16:00:00Z', false, 'Asia/Shanghai')).toEqual({
      day: '2026-02-01',
      time: '',
    });
  });

  it('leaves a wall clock as written, whatever the zone', () => {
    expect(readBound('2026-01-31T15:30', true, 'Asia/Shanghai')).toEqual({
      day: '2026-01-31',
      time: '15:30',
    });
    expect(readBound('2026-01-31', true, 'Asia/Shanghai')).toEqual({
      day: '2026-01-31',
      time: '',
    });
  });

  it('reads nothing from nothing, or from what is no instant', () => {
    expect(readBound(undefined, true, 'Asia/Shanghai')).toEqual({
      day: '',
      time: '',
    });
    expect(readBound('soon', true, 'Asia/Shanghai')).toEqual({
      day: '',
      time: '',
    });
  });
});
