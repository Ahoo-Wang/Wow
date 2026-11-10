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
 * `filter` is composed of its groups by spreading them into one object, so a
 * member two groups both held would be shadowed by the order of the spreads.
 * No member is: the groups are disjoint, which `pnpm test:type` checks at
 * compile time, and their members together are `filter`'s.
 */

import { describe, expect, expectTypeOf, it } from 'vitest';
import { filter } from '../../src/dsl.js';
import { expressionFilters } from '../../src/dsl/filter/expressionFilter.js';
import { fieldFilters } from '../../src/dsl/filter/fieldFilters.js';
import { logicalFilters } from '../../src/dsl/filter/logicalFilters.js';
import { metadataFilters } from '../../src/dsl/filter/metadataFilters.js';
import { searchFilters } from '../../src/dsl/filter/searchFilter.js';
import { timeFilters } from '../../src/dsl/filter/timeFilters.js';

const GROUPS = {
  metadataFilters,
  logicalFilters,
  fieldFilters,
  searchFilters,
  timeFilters,
  expressionFilters,
};

type Groups = typeof GROUPS;

/** The members group `G` shares with any other group. */
type SharedWithOthers<G extends keyof Groups> = {
  [Other in Exclude<keyof Groups, G>]: keyof Groups[G] & keyof Groups[Other];
}[Exclude<keyof Groups, G>];

/** Every member some two groups share. */
type Shared = { [G in keyof Groups]: SharedWithOthers<G> }[keyof Groups];

/** Every member of every group. */
type Members = { [G in keyof Groups]: keyof Groups[G] }[keyof Groups];

describe('the groups of `filter`', () => {
  it('share no member, so no spread shadows another', () => {
    expectTypeOf<Shared>().toEqualTypeOf<never>();
    const seen = new Map<string, string>();
    for (const [group, members] of Object.entries(GROUPS)) {
      for (const member of Object.keys(members)) {
        expect(
          seen.get(member),
          `${member} is in ${seen.get(member)} and ${group}`,
        ).toBeUndefined();
        seen.set(member, group);
      }
    }
  });

  it('hold together every member of `filter`, and nothing else', () => {
    expectTypeOf<keyof typeof filter>().toEqualTypeOf<Members>();
    expect(
      Object.values(GROUPS)
        .flatMap(members => Object.keys(members))
        .sort(),
    ).toEqual(Object.keys(filter).sort());
  });
});
