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

import type { RecordDetailSection } from '../../react/index.js';
import type { DetailSection } from '../../record/index.js';

/** One section of a record's detail, the engine's or the host's. */
export type PlacedSection =
  | { host: false; section: DetailSection }
  | { host: true; section: RecordDetailSection };

/**
 * The engine's field sections and the host's, in reading order.
 *
 * The engine's keep the definition's order; each host section goes where its
 * `placement` says — before them all, after one field group by its id, or
 * (by default, and for a group the definition does not have) after them all
 * — and host sections that land in one place keep the order they were given.
 */
export function placeSections(
  fields: readonly DetailSection[],
  hosts: readonly RecordDetailSection[],
): PlacedSection[] {
  const groups = new Set(fields.map(section => section.id));
  const spots = hosts.map(section => spot(section, groups));
  const at = (where: Spot) =>
    hosts
      .filter((_, index) => spots[index] === where)
      .map(section => ({ host: true as const, section }));
  return [
    ...at(START),
    ...fields.flatMap(section => [
      { host: false as const, section },
      ...(section.id === null ? [] : at(section.id)),
    ]),
    ...at(END),
  ];
}

/** Before every section, after them all, or after the group of that id. */
type Spot = typeof START | typeof END | string;
const START = Symbol('start');
const END = Symbol('end');

/** Where one host section goes; a group the definition lacks is the end. */
function spot(
  section: RecordDetailSection,
  groups: ReadonlySet<string | null>,
): Spot {
  const placement = section.placement ?? 'end';
  if (placement === 'start') return START;
  if (placement === 'end') return END;
  return groups.has(placement.after) ? placement.after : END;
}
