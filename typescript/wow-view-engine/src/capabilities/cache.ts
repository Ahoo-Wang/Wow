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

import type {
  QueryDescriptorResult,
  QueryModelDescriptor,
} from '@ahoo-wang/wow-client';

/**
 * Reads one source's descriptor, sending the version already held so an
 * unchanged one answers `notModified` without a body — the shape of
 * wow-client's `describeSnapshot` and `describeEventStream`.
 */
export type Describe = (previous?: string) => Promise<QueryDescriptorResult>;

/**
 * How old a descriptor may be before a trigger checks it again: the
 * service's own default revalidation interval
 * (`wow.query.schema.revalidate-interval`), so a check never runs more
 * often than the answer can change.
 */
export const DESCRIPTOR_MAX_AGE_MS = 5 * 60 * 1000;

export interface DescriptorCacheOptions {
  /** Milliseconds since the epoch; the runtime environment's clock. */
  now(): number;
  /** Told when reading a source's descriptor failed; what was held is kept. */
  failed?(key: string, error: unknown): void;
  maxAge?: number;
}

interface Entry {
  describe: Describe;
  descriptor: QueryModelDescriptor | null;
  /** When the last read settled, answered or failed; `null` before one has. */
  checkedAt: number | null;
  inflight: Promise<void> | null;
}

/**
 * One descriptor per source key (capabilities.md 6): a board of ten panels
 * over one source reads it once, and reads asked for at the same moment
 * share one request. It is never persisted — a descriptor belongs to the
 * deployment, not to anyone's saved work — and a read that fails keeps
 * whatever was held and is tried again at the next trigger past the
 * maximum age.
 *
 * The last answer wins. Replicas may briefly disagree on the version; the
 * cache does not compare them, and the worst case is one extra narrowing.
 */
export class DescriptorCache {
  private readonly options: DescriptorCacheOptions;
  private readonly entries = new Map<string, Entry>();

  constructor(options: DescriptorCacheOptions) {
    this.options = options;
  }

  /** The descriptor held for a source, or `null` when none has been read. */
  current(key: string): QueryModelDescriptor | null {
    return this.entries.get(key)?.descriptor ?? null;
  }

  /**
   * The source's descriptor. The first time, it is read and waited for, so
   * the first query already goes out narrowed; after that the one held is
   * answered at once, and checked again in the background once it is older
   * than the maximum age. `null` while none could be read.
   */
  async load(
    key: string,
    describe: Describe,
  ): Promise<QueryModelDescriptor | null> {
    let entry = this.entries.get(key);
    if (!entry) {
      entry = { describe, descriptor: null, checkedAt: null, inflight: null };
      this.entries.set(key, entry);
    }
    if (entry.checkedAt === null) await this.check(key, entry);
    else void this.revalidate(key);
    return entry.descriptor;
  }

  /**
   * Checks a source's descriptor again when it is older than the maximum
   * age, or at once when `force`d. A check already in flight is joined,
   * never doubled.
   */
  revalidate(key: string, force = false): Promise<void> {
    const entry = this.entries.get(key);
    if (!entry) return Promise.resolve();
    if (!force && !this.stale(entry))
      return entry.inflight ?? Promise.resolve();
    return this.check(key, entry);
  }

  /** Every source whose descriptor is older than the maximum age, checked again. */
  revalidateStale(): void {
    for (const key of this.entries.keys()) void this.revalidate(key);
  }

  private stale(entry: Entry): boolean {
    if (entry.checkedAt === null) return true;
    const maxAge = this.options.maxAge ?? DESCRIPTOR_MAX_AGE_MS;
    return this.options.now() - entry.checkedAt >= maxAge;
  }

  private check(key: string, entry: Entry): Promise<void> {
    if (entry.inflight) return entry.inflight;
    const read = async () => {
      try {
        const answer = await entry.describe(entry.descriptor?.version);
        if (!answer.notModified) entry.descriptor = answer.descriptor;
      } catch (error) {
        this.options.failed?.(key, error);
      } finally {
        entry.checkedAt = this.options.now();
        entry.inflight = null;
      }
    };
    entry.inflight = read();
    return entry.inflight;
  }
}
