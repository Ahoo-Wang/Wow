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
 * D23 Q15: the title bar says 「只改了展示」 when the draft differs from what
 * was saved in how the result is drawn and in nothing else.
 */

import { describe, expect, it } from 'vitest';
import { presentationOnlyEdits } from '../src/index.js';
import { analysisConfig, recordConfig } from './fixtures.js';

describe('presentationOnlyEdits', () => {
  it('is true for a record view switched to cards', () => {
    const saved = recordConfig();
    expect(presentationOnlyEdits({ ...saved, layout: 'card' }, saved)).toBe(
      true,
    );
  });

  it('is true for an analysis switched to its chart, or its chart changed', () => {
    const saved = analysisConfig({ layout: 'table' });
    expect(presentationOnlyEdits({ ...saved, layout: 'chart' }, saved)).toBe(
      true,
    );
    expect(
      presentationOnlyEdits(
        { ...saved, chart: { ...saved.chart, type: 'line' } },
        saved,
      ),
    ).toBe(true);
  });

  it('is false once anything the question holds differs too', () => {
    const saved = recordConfig();
    expect(
      presentationOnlyEdits({ ...saved, layout: 'card', pageSize: 50 }, saved),
    ).toBe(false);
    expect(presentationOnlyEdits({ ...saved, pageSize: 50 }, saved)).toBe(
      false,
    );
  });

  it('is false when nothing differs', () => {
    const saved = recordConfig();
    expect(presentationOnlyEdits({ ...saved }, saved)).toBe(false);
  });
});
