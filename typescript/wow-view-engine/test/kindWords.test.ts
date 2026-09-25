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
 * What a surface calls the thing it has open (D26 Q34, `ui/kinds.ts`): a
 * dashboard's chrome says 仪表盘 where another view's says 视图 — its
 * labels (`kindWord`) and what the engine reports about it (`kindIssue`).
 * The sweep reads the catalogues themselves, so a sentence added to either
 * family that names a view without a board's own is caught here rather
 * than on a board.
 */

import { describe, expect, it } from 'vitest';
import { VIEW_STORE_ERROR_CODES, type Issue } from '../src/index.js';
import {
  en,
  formatIssue,
  formatMessage,
  kindIssue,
  kindWord,
  zhCN,
  type MessageKey,
  type ViewMessages,
} from '../src/ui/index.js';

const CATALOGUES: readonly [string, ViewMessages, RegExp, RegExp][] = [
  ['en', en, /\bviews?\b/i, /dashboard/i],
  ['zh-CN', zhCN, /视图/, /仪表盘/],
];

const KEYS = Object.keys(en).filter((key): key is MessageKey => key in en);

/**
 * The member a board's config has no part in: its conditions (D27). The
 * config-base rules for them run on record and analysis configs only.
 */
const DATA_ONLY: ReadonlySet<string> = new Set([
  'config.filter.invalid',
  'config.filterMode.unknown',
  'config.filterMode.not-simple',
  // A data view's query naming a deprecated field; a board runs no query.
  'view.field.deprecated',
  'view.field.deprecated-because',
]);

/**
 * Every code the engine can report about a board itself: the commands on
 * an instance (`view.*`), the config part every kind stores (`config.*`),
 * and a definition that offers no board (`runtime.kind.*`) — and each
 * command's failure composed with every store code, which reads the entry
 * it falls back to.
 */
function aboutItself(): string[] {
  const shipped = KEYS.filter(
    key => /^(view|config|runtime\.kind)\./.test(key) && !DATA_ONLY.has(key),
  );
  const composed = shipped
    .filter(key => key.endsWith('.failed'))
    .flatMap(key =>
      VIEW_STORE_ERROR_CODES.map(code => `${key}.${code.toLowerCase()}`),
    );
  return [...shipped, ...composed];
}

function reported(code: string): Issue {
  return { code, severity: 'error', path: [] };
}

describe('what a board calls itself (D26 Q34)', () => {
  it('reports nothing about the board as a view, in either catalogue', () => {
    const said = aboutItself().flatMap(code =>
      CATALOGUES.map(([language, messages, view]) => {
        const sentence = formatIssue(
          messages,
          kindIssue(reported(code), 'dashboard'),
        );
        return view.test(sentence) ? `${language} ${code}: ${sentence}` : '';
      }),
    );

    expect(said.filter(Boolean)).toEqual([]);
  });

  it('keeps the code of a sentence that names no view, and every other kind’s', () => {
    const quiet = aboutItself().filter(
      code => !/\bviews?\b/i.test(formatIssue(en, reported(code))),
    );
    expect(quiet.length).toBeGreaterThan(0);
    for (const code of quiet) {
      const found = reported(code);
      expect(kindIssue(found, 'dashboard')).toBe(found);
    }
    for (const code of aboutItself()) {
      const found = reported(code);
      expect(kindIssue(found, 'record')).toBe(found);
      expect(kindIssue(found, 'analysis')).toBe(found);
      expect(kindIssue(found, undefined)).toBe(found);
    }
  });

  it('keeps what an issue carries besides its words', () => {
    const found: Issue = {
      code: 'view.system.read-only',
      severity: 'warning',
      path: ['title'],
      params: { action: 'rename' },
    };

    expect(kindIssue(found, 'dashboard')).toEqual({
      ...found,
      code: 'label.dashboard.system-read-only',
    });
    expect(formatIssue(zhCN, kindIssue(found, 'dashboard'))).toBe(
      '系统仪表盘不能改（rename）。',
    );
  });

  it('names the board in every sentence it words its own way', () => {
    // What a board's panels show are views, and one sentence says so on
    // purpose: deleting a board leaves the saved views on its panels.
    const onPurpose: ReadonlySet<string> = new Set([
      'label.dashboard.delete-consequence',
    ]);
    const own = KEYS.flatMap(key => {
      const board = kindWord(key, 'dashboard');
      return board === key ? [] : [board];
    });
    const routed = aboutItself().flatMap(code => {
      const board = kindIssue(reported(code), 'dashboard').code;
      return board === code ? [] : [board];
    });
    expect(own.length).toBeGreaterThan(0);
    expect(routed.length).toBeGreaterThan(0);

    const astray = [...new Set([...own, ...routed])].flatMap(key =>
      CATALOGUES.map(([language, messages, view, board]) => {
        const sentence = formatMessage(messages, key);
        const wrong =
          !board.test(sentence) || (view.test(sentence) && !onPurpose.has(key));
        return wrong ? `${language} ${key}: ${sentence}` : '';
      }),
    );

    expect(astray.filter(Boolean)).toEqual([]);
  });
});
