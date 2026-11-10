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
import { aggregationRules } from './conformance/aggregationRules';
import { filterRules } from './conformance/filterRules';
import { pagingRules } from './conformance/pagingRules';
import type { ConformanceRule } from './conformance/register';
import { serverRules } from './conformance/serverRules';

/**
 * Wow's own query rules, each mirrored here, satisfied by the shape of the
 * builders, or left to the server: the register is in `conformance/`, by
 * subject, in this order.
 */
const RULES: ConformanceRule[] = [
  ...filterRules,
  ...aggregationRules,
  ...pagingRules,
  ...serverRules,
];

describe('Wow query conformance', () => {
  const mirrored = RULES.filter(rule => rule.violate);
  const declared = RULES.filter(rule => !rule.violate);

  // A rule is what Wow states *somewhere*: `Query field is invalid` is stated
  // by QueryField, by Sort and by Projection, and the register carries one
  // entry each. Keying on the message alone would fold them into one and lose
  // two of the three without anything noticing.
  const identity = (rule: ConformanceRule) => `${rule.source} :: ${rule.wow}`;

  it('names every rule exactly once', () => {
    const names = RULES.map(identity);
    expect(new Set(names).size).toBe(names.length);
  });

  it('cites a distinct source for rules that share a message', () => {
    const byMessage = new Map<string, string[]>();
    for (const rule of RULES)
      byMessage.set(rule.wow, [
        ...(byMessage.get(rule.wow) ?? []),
        rule.source,
      ]);
    for (const [, sources] of byMessage)
      expect(new Set(sources).size).toBe(sources.length);
  });

  it('gives a reason for every rule it does not exercise', () => {
    expect(
      declared
        .filter(rule => !rule.byConstruction && !rule.serverOnly)
        .map(rule => rule.wow),
    ).toEqual([]);
  });

  it.each(mirrored.map(rule => [rule.wow, rule] as const))(
    'refuses what Wow refuses: %s',
    (_name, rule) => {
      expect(rule.violate).toThrow(rule.throws ?? rule.wow);
    },
  );

  it('covers the rules this package is answerable for', () => {
    // A floor, so deleting a rule from the register is visible in the diff
    // rather than silently reducing what is checked.
    expect(mirrored.length).toBeGreaterThanOrEqual(50);
  });
});
