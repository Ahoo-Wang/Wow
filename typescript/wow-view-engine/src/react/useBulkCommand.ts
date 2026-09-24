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

import { useCallback, useEffect, useRef, useState } from 'react';
import type { RecordKey } from '../model/index.js';
import { sourceReason } from '../runtime/sourceReason.js';
import type { RecordBulkActionContext } from './actions.js';

/**
 * One business command over records, as a host hands it to `run`: what it
 * is called, and what it does to one record. `each` resolves when the record
 * took the command and throws when it did not; what it throws is read for
 * the source's own reason (`sourceReason`), so a host passes the error on
 * rather than wording it.
 */
export interface BulkRun {
  /** Said before the counts: a host with several commands names which ran. */
  title: string;
  each(key: RecordKey): Promise<unknown>;
}

/** One record the command did not take, and the source's reason why. */
export interface BulkFailure {
  key: RecordKey;
  reason: string;
}

/** How far a command has come over the records it was handed. */
export interface BulkProgress {
  total: number;
  /** Settled either way. */
  done: number;
  failed: number;
}

/** What one command came to, once every record it started has settled. */
export interface BulkOutcome {
  title: string;
  succeeded: readonly RecordKey[];
  failed: readonly BulkFailure[];
  /** Never started, because the command was stopped. */
  skipped: readonly RecordKey[];
}

/**
 * The selection a command runs over, and what a command does to it
 * afterwards: the rows it failed on stay selected, the rest are let go.
 */
export type BulkSelection = Pick<
  RecordBulkActionContext,
  'keys' | 'select' | 'refresh'
>;

export interface BulkCommand {
  run(selection: BulkSelection, command: BulkRun): void;
  /** Starts nothing more; what is already in flight settles. */
  stop(): void;
  /** The command in flight, or `null`. */
  running: { title: string; progress: BulkProgress; stopping: boolean } | null;
  outcome: BulkOutcome | null;
  dismiss(): void;
}

export interface BulkCommandOptions {
  /**
   * How many records are in flight at once. A command writes, and a
   * selection of a hundred sent in one burst is a hundred writes landing on
   * one service at once; a handful at a time is quick and polite.
   */
  concurrency?: number;
}

const CONCURRENCY = 4;

/**
 * Everything of a bulk command that is not the command itself: records a
 * handful at a time, progress while it runs, a stop that starts nothing
 * more, the source's reason for every record that refused, and afterwards
 * a refresh of the page the reader is on with the failed rows left
 * selected — they are the rows still to be dealt with.
 *
 * One command at a time: commands write, so a second press over the same
 * rows is not a newer read that can supersede the first but a second write.
 */
export function useBulkCommand(options: BulkCommandOptions = {}): BulkCommand {
  const concurrency = Math.max(
    1,
    Math.floor(options.concurrency ?? CONCURRENCY),
  );
  const [running, setRunning] = useState<BulkCommand['running']>(null);
  const [outcome, setOutcome] = useState<BulkOutcome | null>(null);
  // Refs as well as state: two presses in one frame both see the old state,
  // and the workers read the stop between records, not between renders.
  const busy = useRef(false);
  const stopping = useRef(false);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  const run = useCallback(
    (selection: BulkSelection, command: BulkRun) => {
      if (busy.current || selection.keys.length === 0) return;
      const keys = [...selection.keys];
      const succeeded: RecordKey[] = [];
      const failed: BulkFailure[] = [];
      let next = 0;
      busy.current = true;
      stopping.current = false;
      const report = () => {
        if (!alive.current) return;
        setRunning({
          title: command.title,
          progress: {
            total: keys.length,
            done: succeeded.length + failed.length,
            failed: failed.length,
          },
          stopping: stopping.current,
        });
      };
      const worker = async (): Promise<void> => {
        while (next < keys.length && !stopping.current) {
          const key = keys[next];
          next += 1;
          try {
            await command.each(key);
            succeeded.push(key);
          } catch (error) {
            failed.push({ key, reason: await sourceReason(error) });
          }
          report();
        }
      };
      setOutcome(null);
      report();
      const workers = Array.from(
        { length: Math.min(concurrency, keys.length) },
        worker,
      );
      void Promise.all(workers).then(() => {
        busy.current = false;
        // The host may have navigated away while the command ran. The
        // records still took it; the report simply has nobody to reach.
        if (!alive.current) return;
        const settled = new Set<RecordKey>([
          ...succeeded,
          ...failed.map(failure => failure.key),
        ]);
        const skipped = keys.filter(key => !settled.has(key));
        setRunning(null);
        setOutcome({ title: command.title, succeeded, failed, skipped });
        // What is left to deal with stays picked: the refused and the never
        // started, in the order they were picked.
        const left = new Set<RecordKey>([
          ...failed.map(failure => failure.key),
          ...skipped,
        ]);
        selection.select(keys.filter(key => left.has(key)));
        selection.refresh();
      });
    },
    [concurrency],
  );

  const stop = useCallback(() => {
    if (!busy.current) return;
    stopping.current = true;
    setRunning(current => (current ? { ...current, stopping: true } : current));
  }, []);
  const dismiss = useCallback(() => setOutcome(null), []);
  return { run, stop, running, outcome, dismiss };
}

/**
 * The reasons a command's failures give, most common first — the strip says
 * a few of them rather than one reason for all, since records refuse for
 * different reasons and the most common is the one to fix first.
 */
export function failureReasons(
  failed: readonly BulkFailure[],
): { reason: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const { reason } of failed)
    counts.set(reason, (counts.get(reason) ?? 0) + 1);
  return [...counts]
    .map(([reason, count]) => ({ reason, count }))
    .sort((left, right) => right.count - left.count);
}
