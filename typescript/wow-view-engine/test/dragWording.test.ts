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
import { SERIES_DRAG_WORDING } from '../src/ui/analysis/drag.js';
import { COLUMN_DRAG_WORDING } from '../src/ui/columns/drag.js';
import { dragWording } from '../src/ui/dragWording.js';
import { MANAGE_DRAG_WORDING } from '../src/ui/manage/drag.js';
import { defaultMessages } from '../src/ui/messages.js';
import { zhCN } from '../src/ui/messages/zh-CN.js';
import { SORT_DRAG_WORDING } from '../src/ui/sort/drag.js';
import { formattersFor } from './fixtures/columns.js';

/**
 * The four sortable lists say the same three sentences and differ only in
 * where the catalogue keeps them and which placeholder names the row, so
 * every key set is read through the one `dragWording` and must come out as
 * whole sentences in both shipped languages — the carried row named, no
 * placeholder left standing.
 */
describe('dragWording', () => {
  const lists = Object.entries({
    columns: COLUMN_DRAG_WORDING,
    sort: SORT_DRAG_WORDING,
    manage: MANAGE_DRAG_WORDING,
    series: SERIES_DRAG_WORDING,
  });

  it.each(lists)('words the %s drag in the English catalogue', (_, keys) => {
    const say = dragWording(formattersFor(defaultMessages), keys);
    expect(say.instructions).toBe(defaultMessages[keys.instructions]);
    expect(say.picked('Amount')).toBe('Amount picked up');
    expect(say.cancelled('Amount')).toBe(
      'Move cancelled; Amount stayed where it was',
    );
  });

  it.each(lists)(
    'names the row in every %s sentence of the Chinese catalogue',
    (_, keys) => {
      const say = dragWording(
        formattersFor({ ...defaultMessages, ...zhCN }),
        keys,
      );
      expect(say.instructions).toBe(zhCN[keys.instructions]);
      for (const sentence of [say.picked('金额'), say.cancelled('金额')]) {
        expect(sentence).toContain('金额');
        expect(sentence).not.toMatch(/[{}]/);
      }
    },
  );
});
