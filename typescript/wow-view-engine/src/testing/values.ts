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

import dayjs from 'dayjs';
import type { RecordData } from '../model/record.js';

/** A field of a document by its dotted path; `undefined` where the path ends early. */
export function valueAt(document: unknown, path: string): unknown {
  return path
    .split('.')
    .reduce<unknown>(
      (held, segment) =>
        held !== null && typeof held === 'object'
          ? (held as RecordData)[segment]
          : undefined,
      document,
    );
}

/** A time as epoch milliseconds, or `null` for what names no instant. */
export function instantOf(at: unknown): number | null {
  if (typeof at === 'number') return Number.isFinite(at) ? at : null;
  if (typeof at !== 'string') return null;
  const parsed = dayjs(at).valueOf();
  return Number.isFinite(parsed) ? parsed : null;
}

/**
 * The number a metric reads off one value, as `wow-mongo` reads it: a finite
 * number, or the one number of an array that holds exactly one — an array of
 * several is no number; null for anything else.
 */
export function numberOf(value: unknown): number | null {
  if (Array.isArray(value))
    return value.length === 1 ? numberOf(value[0]) : null;
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** MongoDB's order of the BSON types a JSON document holds: null first. */
function typeRank(value: unknown): number {
  if (value === null || value === undefined) return 0;
  if (typeof value === 'number') return 1;
  if (typeof value === 'string') return 2;
  if (Array.isArray(value)) return 4;
  if (typeof value === 'object') return 3;
  return 5;
}

/** Two values in MongoDB's order, which is the one `ANY`'s `$max` reads. */
export function compareValues(left: unknown, right: unknown): number {
  const ranks = typeRank(left) - typeRank(right);
  if (ranks !== 0) return ranks;
  const [a, b] =
    typeof left === 'number' ||
    typeof left === 'string' ||
    typeof left === 'boolean'
      ? [left, right as typeof left]
      : [JSON.stringify(left), JSON.stringify(right)];
  return a < b ? -1 : a > b ? 1 : 0;
}

export function mod(value: number, by: number): number {
  return ((value % by) + by) % by;
}
