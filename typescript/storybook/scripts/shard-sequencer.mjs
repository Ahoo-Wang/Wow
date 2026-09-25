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

// Shards the suite by measured duration instead of Vitest's path hash. The
// browser project runs one file at a time (`fileParallelism: false`), so a
// shard lasts as long as the sum of its files, and hashing paths into equal
// file counts left one shard a minute and a half longer than the other.
//
// Every shard job computes the same partition: the weights come from the
// committed test-durations.json, ties break on the path, and a file missing
// from it (a new story) weighs the median. Stale weights only make the shards
// less even; every file still lands in exactly one shard.
import { readFileSync } from 'node:fs';
import { relative, sep } from 'node:path';
import { BaseSequencer } from 'vitest/node';

// What a file costs beyond its tests: its setup file, imports and page reload.
const PER_FILE_MS = 800;

export const DURATIONS_FILE = new URL(
  '../test-durations.json',
  import.meta.url,
);

/**
 * Splits weighted items into `count` bins, heaviest first into the lightest
 * bin (longest-processing-time first). Deterministic: equal weights order by key.
 *
 * @template T
 * @param {{ item: T, key: string, weight: number }[]} items
 * @param {number} count
 * @returns {T[][]}
 */
export function partition(items, count) {
  const sorted = [...items].sort(
    (a, b) =>
      b.weight - a.weight || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0),
  );
  const loads = new Array(count).fill(0);
  const bins = Array.from({ length: count }, () => []);
  for (const { item, weight } of sorted) {
    let lightest = 0;
    for (let bin = 1; bin < count; bin++)
      if (loads[bin] < loads[lightest]) lightest = bin;
    loads[lightest] += weight;
    bins[lightest].push(item);
  }
  return bins;
}

/**
 * Weights of the files, in milliseconds, keyed by their path relative to the
 * package; a path missing from `durations` weighs the median.
 *
 * @param {string[]} paths
 * @param {Record<string, number>} durations
 * @returns {number[]}
 */
export function weights(paths, durations) {
  const known = Object.values(durations).sort((a, b) => a - b);
  const median = known.length ? known[Math.floor(known.length / 2)] : 1000;
  return paths.map(path => (durations[path] ?? median) + PER_FILE_MS);
}

export class DurationShardSequencer extends BaseSequencer {
  async shard(files) {
    const { root, shard } = this.ctx.config;
    const durations = JSON.parse(readFileSync(DURATIONS_FILE, 'utf8'));
    const paths = files.map(spec =>
      relative(root, spec.moduleId).split(sep).join('/'),
    );
    const weighted = weights(paths, durations).map((weight, index) => ({
      item: files[index],
      // The project name keeps one file's browser instances apart.
      key: `${files[index].project.name}:${paths[index]}`,
      weight,
    }));
    return partition(weighted, shard.count)[shard.index - 1];
  }
}
