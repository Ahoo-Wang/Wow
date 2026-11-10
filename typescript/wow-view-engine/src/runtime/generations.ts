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
 * The one rule for an asynchronous answer: **it is applied only against the
 * state it was issued for.** Every place that issues a read or starts an
 * operation and later applies what came back asks it here, so the engine
 * has one notion of "still current" rather than a counter or a ref kept by
 * hand in each.
 *
 * What it decides by is issue order, not the store's revisions: the
 * `ViewStore` port and the dataset wording store declare revisions opaque
 * (a host's store may answer an ETag), so which of two revisions is newer
 * cannot be read off them. Issue order is what this engine knows: when it
 * sent each operation, and when a write of its own was confirmed.
 *
 * Two questions, for two kinds of state:
 *
 * - **Server knowledge** (a cached summary, preferences, a dataset's
 *   wording): `admit` — the answer of a read may replace what is held only
 *   if no write about the key was confirmed after the read was issued, and
 *   no read issued after it has already been applied. Two reads of one key
 *   in flight each get their own stamp; when the later-issued one lands
 *   first it is applied, and the earlier one, landing after, is not: it
 *   would put back an older state.
 * - **Local intent** (the export a dialog runs, the tab a reader switched
 *   to): `latest` — the operation is still the last one started for the
 *   key; a later one, or `supersede` (an intent of nothing, such as a
 *   cancel), replaced it.
 *
 * `confirm` is called when the store confirms a write, never when it is
 * dispatched: a write that fails changes nothing a read could be older than.
 *
 * It holds only what an operation in flight can still be judged against:
 * a ticket is `release`d when its operation is over, landed or not, and a
 * key is forgotten once no ticket in flight was issued before its last
 * mark. With nothing in flight it holds nothing, however many keys it has
 * seen.
 */
export class Generations<K> {
  /** One sequence for every mark, so marks of any key compare. */
  private sequence = 0;
  /** Per key: the last ticket issued, the last applied, the last write confirmed. */
  private readonly marks = new Map<K, Marks>();
  /** The stamps of the tickets not yet released. */
  private readonly inFlight = new Set<number>();

  /** An operation about `key` leaves now; its answer is judged by this ticket. */
  issue(key: K): Ticket<K> {
    const stamp = ++this.sequence;
    this.marksOf(key).issued = stamp;
    this.inFlight.add(stamp);
    return { key, stamp };
  }

  /**
   * Whether the answer of `ticket`'s read about `key` — its own key, or one
   * of the many a listing answers for — may replace what is held: no write
   * about that key confirmed since the read was issued, and no answer about
   * it issued later applied already. Admitting it records it applied.
   */
  admit(ticket: Ticket<K>, key: K = ticket.key): boolean {
    const marks = this.marks.get(key);
    if (
      marks &&
      (ticket.stamp < marks.confirmed || ticket.stamp < marks.applied)
    )
      return false;
    this.marksOf(key).applied = ticket.stamp;
    return true;
  }

  /** Whether `ticket` is still the last operation started for its key. */
  latest(ticket: Ticket<K>): boolean {
    return (
      (this.marks.get(ticket.key)?.issued ?? ticket.stamp) === ticket.stamp
    );
  }

  /**
   * A write about `key` the store has confirmed: every answer issued before
   * now is older than what it put there.
   */
  confirm(key: K): void {
    this.mark(key, 'confirmed');
  }

  /** An intent of nothing for `key` — a cancel: no operation started before it is the latest. */
  supersede(key: K): void {
    this.mark(key, 'issued');
  }

  /** `ticket`'s operation is over, whether its answer landed, failed or was dropped. */
  release(ticket: Ticket<K>): void {
    this.inFlight.delete(ticket.stamp);
    this.prune();
  }

  /** How many keys it holds marks for; only what tickets in flight are judged against. */
  get size(): number {
    return this.marks.size;
  }

  private mark(key: K, which: 'issued' | 'confirmed'): void {
    // Nothing in flight can be older than this mark: there is nothing to keep.
    if (this.inFlight.size === 0) {
      this.marks.delete(key);
      return;
    }
    this.marksOf(key)[which] = ++this.sequence;
  }

  private marksOf(key: K): Marks {
    let marks = this.marks.get(key);
    if (!marks) {
      marks = { issued: 0, applied: 0, confirmed: 0 };
      this.marks.set(key, marks);
    }
    return marks;
  }

  /**
   * Forgets every key whose marks are all older than the oldest ticket in
   * flight: each ticket left is judged the same with them or without them.
   */
  private prune(): void {
    let oldest = Infinity;
    for (const stamp of this.inFlight) oldest = Math.min(oldest, stamp);
    for (const [key, { issued, applied, confirmed }] of this.marks)
      if (Math.max(issued, applied, confirmed) < oldest) this.marks.delete(key);
  }
}

/** What an operation carries from its issue to its answer. */
export interface Ticket<K> {
  readonly key: K;
  readonly stamp: number;
}

interface Marks {
  issued: number;
  applied: number;
  confirmed: number;
}
