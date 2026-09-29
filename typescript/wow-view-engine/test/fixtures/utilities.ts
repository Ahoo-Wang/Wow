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

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { __unstable__loadDesignSystem } from '@tailwindcss/node';

/**
 * Whether a class name is one of the engine's Tailwind utilities, asked of
 * the engine's own design system — `src/styles.css`, `prefix(fve)` and every
 * `@import`, `@theme` and `@custom-variant` it carries (D66) — so the answer
 * is the build's: `fve:truncate` is a utility, `fve:truncat` and `truncate`
 * are not. The design system loads once per module (~60 ms) and every
 * verdict is cached, so a suite asks as often as it likes.
 */
const BASE = join(import.meta.dirname, '..', '..', 'src');
const STYLES = join(BASE, 'styles.css');

let designSystem:
  Promise<Awaited<ReturnType<typeof __unstable__loadDesignSystem>>> | undefined;
const verdicts = new Map<string, boolean>();

export interface UtilityCheck {
  /** Whether `candidate`, written as it is, generates CSS. */
  (candidate: string): boolean;
}

/** The engine's utility check, its design system loaded on first use. */
export async function utilityCheck(): Promise<UtilityCheck> {
  designSystem ??= __unstable__loadDesignSystem(readFileSync(STYLES, 'utf8'), {
    base: BASE,
  });
  const system = await designSystem;
  return candidate => {
    let verdict = verdicts.get(candidate);
    if (verdict === undefined) {
      verdict = system.candidatesToCss([candidate])[0] != null;
      verdicts.set(candidate, verdict);
    }
    return verdict;
  };
}

/**
 * A class that marks an element for `fve:group-*` / `fve:peer-*` variants,
 * named or not: it writes no CSS of its own, and is meant not to.
 */
export function isVariantMarker(token: string): boolean {
  return /^fve:(group|peer)(\/[\w-]+)?$/.test(token);
}

/**
 * Classes known to be written without the prefix, each dated with the fix
 * that removes it. `prefixedClasses.test.ts` passes such a word in its file
 * and fails once the word is gone from there (a stale entry), and the
 * runtime check in `setup.ts` passes the word on any element meanwhile — so
 * an entry is deleted the day its fix lands, and nothing else hides here.
 */
export const KNOWN_MISSES: readonly {
  file: string;
  token: string;
  reason: string;
}[] = [
  {
    file: 'ui/record/columns.ts',
    token: 'truncate',
    reason:
      '2026-09-29: `CLIPPED_CELL` is written without the prefix, so a clipped cell is not clipped. #3792 fixes it; delete this entry when it lands.',
  },
];
