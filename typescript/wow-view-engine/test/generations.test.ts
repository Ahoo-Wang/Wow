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
 * The one rule for an asynchronous answer (`runtime/generations.ts`): it is
 * applied only against the state it was issued for. Each case runs reads
 * and writes on controlled promises, landing in the order the case says.
 */

import { describe, expect, it } from 'vitest';
import { Generations } from '../src/runtime/generations.js';
import { deferred } from './fixtures.js';

/** A cache of one value per key, kept by the rule. */
function cacheOf() {
  const generations = new Generations<string>();
  const held = new Map<string, string>();
  return {
    generations,
    held,
    /** A read of `key`, answered when the case resolves it. */
    read(key: string) {
      const ticket = generations.issue(key);
      const answer = deferred<string>();
      const landed = answer.promise
        .then(value => {
          if (generations.admit(ticket)) held.set(key, value);
        })
        .finally(() => generations.release(ticket));
      return { answer, landed };
    },
    /** A write of `key` the store confirmed. */
    confirmed(key: string, value: string) {
      held.set(key, value);
      generations.confirm(key);
    },
  };
}

describe('Generations: server knowledge (admit)', () => {
  it('applies a read that nothing overtook', async () => {
    const cache = cacheOf();
    const read = cache.read('a');
    read.answer.resolve('one');
    await read.landed;
    expect(cache.held.get('a')).toBe('one');
  });

  it('drops a read a write confirmed after it was issued', async () => {
    const cache = cacheOf();
    const read = cache.read('a');
    cache.confirmed('a', 'written');
    read.answer.resolve('before the write');
    await read.landed;
    expect(cache.held.get('a')).toBe('written');
  });

  it('applies a read issued after a confirmed write', async () => {
    const cache = cacheOf();
    cache.confirmed('a', 'written');
    const read = cache.read('a');
    read.answer.resolve('after the write');
    await read.landed;
    expect(cache.held.get('a')).toBe('after the write');
  });

  it('gives two reads of one key their own stamps: the later-issued, landing first, wins', async () => {
    const cache = cacheOf();
    const earlier = cache.read('a');
    const later = cache.read('a');
    later.answer.resolve('newer');
    await later.landed;
    earlier.answer.resolve('older');
    await earlier.landed;
    expect(cache.held.get('a')).toBe('newer');
  });

  it('applies both of two reads landing in the order they were issued', async () => {
    const cache = cacheOf();
    const earlier = cache.read('a');
    const later = cache.read('a');
    earlier.answer.resolve('older');
    await earlier.landed;
    expect(cache.held.get('a')).toBe('older');
    later.answer.resolve('newer');
    await later.landed;
    expect(cache.held.get('a')).toBe('newer');
  });

  it('judges each key on its own', async () => {
    const cache = cacheOf();
    const a = cache.read('a');
    const b = cache.read('b');
    cache.confirmed('a', 'written');
    a.answer.resolve('stale');
    b.answer.resolve('fresh');
    await Promise.all([a.landed, b.landed]);
    expect(cache.held.get('a')).toBe('written');
    expect(cache.held.get('b')).toBe('fresh');
  });

  it('confirms a write on confirmation only: a write that failed leaves a read in flight current', async () => {
    const cache = cacheOf();
    const read = cache.read('a');
    // The write was dispatched and failed: nothing was confirmed.
    read.answer.resolve('read');
    await read.landed;
    expect(cache.held.get('a')).toBe('read');
  });
});

describe('Generations: a listing, one read answering for many keys', () => {
  it('judges each key it answers for against that key’s writes and reads', () => {
    const generations = new Generations<string>();
    const listing = generations.issue('list');
    generations.confirm('renamed');
    const read = generations.issue('read-again');
    expect(generations.admit(read)).toBe(true);
    expect(generations.admit(listing, 'renamed')).toBe(false);
    expect(generations.admit(listing, 'read-again')).toBe(false);
    expect(generations.admit(listing, 'untouched')).toBe(true);
    generations.release(listing);
    generations.release(read);
    expect(generations.size).toBe(0);
  });
});

describe('Generations: local intent (latest)', () => {
  it('says the last operation started is the latest, and an earlier one is not', () => {
    const generations = new Generations<string>();
    const first = generations.issue('tab');
    const second = generations.issue('tab');
    expect(generations.latest(first)).toBe(false);
    expect(generations.latest(second)).toBe(true);
  });

  it('takes an intent of nothing as newer than every operation before it', () => {
    const generations = new Generations<string>();
    const run = generations.issue('export');
    generations.supersede('export');
    expect(generations.latest(run)).toBe(false);
    const again = generations.issue('export');
    expect(generations.latest(again)).toBe(true);
  });
});

describe('Generations: a ticket’s life (run, release)', () => {
  it('answers a released ticket as no operation’s latest', () => {
    const generations = new Generations<string>();
    const only = generations.issue('tab');
    expect(generations.latest(only)).toBe(true);
    generations.release(only);
    expect(generations.latest(only)).toBe(false);
  });

  it('runs an operation with a ticket, released when it answers', async () => {
    const generations = new Generations<string>();
    const answer = deferred<string>();
    const seen: boolean[] = [];
    const running = generations.run('a', ticket => {
      seen.push(generations.latest(ticket));
      return answer.promise;
    });
    expect(generations.size).toBe(1);
    answer.resolve('done');
    expect(await running).toBe('done');
    expect(seen).toEqual([true]);
    expect(generations.size).toBe(0);
  });

  it('releases the ticket of an operation that fails, and passes the failure on', async () => {
    const generations = new Generations<string>();
    const answer = deferred<string>();
    const running = generations.run('a', () => answer.promise);
    answer.reject(new Error('down'));
    await expect(running).rejects.toThrow('down');
    expect(generations.size).toBe(0);
  });
});

describe('Generations: bounded', () => {
  it('holds nothing once nothing is in flight', async () => {
    const cache = cacheOf();
    const reads = ['a', 'b', 'c'].map(key => cache.read(key));
    cache.confirmed('a', 'written');
    expect(cache.generations.size).toBe(3);
    for (const read of reads) read.answer.resolve('x');
    await Promise.all(reads.map(read => read.landed));
    expect(cache.generations.size).toBe(0);
    // A write with nothing in flight has nothing to be newer than.
    cache.confirmed('d', 'written');
    expect(cache.generations.size).toBe(0);
  });

  it('keeps what a ticket in flight is judged against, and forgets the rest', async () => {
    const cache = cacheOf();
    const done = cache.read('done');
    const waiting = cache.read('waiting');
    done.answer.resolve('x');
    await done.landed;
    expect(cache.generations.size).toBe(1);
    cache.confirmed('waiting', 'written');
    waiting.answer.resolve('stale');
    await waiting.landed;
    expect(cache.held.get('waiting')).toBe('written');
    expect(cache.generations.size).toBe(0);
  });

  it('judges a ticket the same after older keys were forgotten', async () => {
    const cache = cacheOf();
    const first = cache.read('a');
    first.answer.resolve('one');
    await first.landed;
    const second = cache.read('a');
    second.answer.resolve('two');
    await second.landed;
    expect(cache.held.get('a')).toBe('two');
  });
});
