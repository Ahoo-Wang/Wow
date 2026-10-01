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

import { readdirSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/*
 * A scene's docs page says what its data shows — 「发货及时率掉到 81.6%」,
 * 「明细里 11 张超时单」. Written by hand, such a number is wrong the day the
 * seed or the generator moves, and nothing notices. So a description states
 * a reading of the data only through an interpolation of what a test holds
 * to the data: the goldens (`goldens.ts`, computed again from the data set by
 * `goldens.test.ts`) or the guide's answers and counts (`guide.ts`). A
 * reading a scene cannot get without running a query is left out.
 *
 * This reads each scene's `description` as written, drops its
 * interpolations and code spans, and fails on a number said the way a
 * reading is said: 「约 N」, a count of orders, rows, parcels, members,
 * questions or chart types, or a share that is not a target or a red line
 * (those are settings, as are windows like 「近 30 天」 and a top N).
 * Readings said in Chinese numerals (「一两单」) are not caught; a reviewer
 * keeps those out.
 */

const DIRECTORY = new URL('../', import.meta.url);

/** Settings that read like a count, and where they are set. */
const SETTINGS = [
  // The parallel-coordinates chart's own threshold for one colour (D41).
  '多于 8 条',
];

const READINGS: RegExp[] = [
  /约\s*\d/g,
  /\d[\d.,]*\s*万?\s*(?:张|单|条|个包裹|个会员|个问题|种图)/g,
];

const SHARE = /\d[\d.]*\s*%/g;

function scenes(): { file: string; description: string }[] {
  return readdirSync(DIRECTORY)
    .filter(file => file.endsWith('.stories.tsx'))
    .filter(file => !file.endsWith('.test.stories.tsx'))
    .map(file => ({
      file,
      source: readFileSync(new URL(file, DIRECTORY), 'utf8'),
    }))
    .filter(({ source }) =>
      /title: 'View Engine\/(?:业务场景\/|首页')/.test(source),
    )
    .map(({ file, source }) => {
      const found = /const description = `([\s\S]*?)`;/.exec(source);
      if (!found) throw new Error(`${file} has no description.`);
      return { file, description: found[1] };
    });
}

/** What the description says in its own words. */
function written(description: string): string {
  let text = description
    .replace(/\$\{[^}]*\}/g, '⟨·⟩')
    .replace(/\\`[^`]*?\\`/g, '⟨code⟩');
  for (const setting of SETTINGS) text = text.replaceAll(setting, '⟨setting⟩');
  return text;
}

function readingsIn(description: string): string[] {
  const text = written(description);
  const found = READINGS.flatMap(pattern =>
    [...text.matchAll(pattern)].map(match => match[0]),
  );
  for (const match of text.matchAll(SHARE)) {
    const at = match.index ?? 0;
    const around = text.slice(Math.max(0, at - 6), at + match[0].length + 4);
    if (!/目标|红线/.test(around)) found.push(match[0]);
  }
  return found;
}

describe('the scenes’ descriptions', () => {
  const all = scenes();

  it('are found, one per scene', () => {
    expect(all.map(({ file }) => file)).toContain('OpsDaily.stories.tsx');
    expect(all.map(({ file }) => file)).toContain('Home.stories.tsx');
    expect(all.length).toBeGreaterThanOrEqual(12);
  });

  it.each(all)(
    '$file writes no reading of the data by hand',
    ({ description }) => {
      expect(readingsIn(description)).toEqual([]);
    },
  );
});

describe('what counts as a reading', () => {
  it('catches 「约 N」, counts and bare shares', () => {
    expect(readingsIn('掉到约 82%，明细里 11 张超时单')).toEqual([
      '约 8',
      '11 张',
      '82%',
    ]);
    expect(readingsIn('约 1.9 万个包裹，88 单')).toEqual([
      '约 1',
      '1.9 万个包裹',
      '88 单',
    ]);
  });

  it('leaves interpolations, code, targets, red lines and settings', () => {
    expect(
      readingsIn(
        '掉到 ${golden}，${count} 张；目标 95%；红线 5%；5% 红线；' +
          '低于 95% 的目标；\\`TO2026091900005\\`；多于 8 条时',
      ),
    ).toEqual([]);
  });
});
