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
import {
  ABANDONED,
  ActionRefusedError,
  FAILED,
  TIMED_OUT,
  UNAVAILABLE,
} from '../runtime/actions.js';
import { isCalledOff } from '../runtime/failures.js';
import { sourceFailure, type SourceFailure } from '../runtime/sourceReason.js';

/**
 * One command over records, as the surface's runner takes it — a declared
 * action's, or a host's own from a slot (`run` on a slot's context): what it
 * is called, and what it does to one record. `each` resolves when the record
 * took the command and throws when it did not; what it throws is read for
 * the source's own reason (`sourceReason`), so a host passes the error on
 * rather than wording it — and an action's own refusal keeps its reason as
 * written, a key said where it is shown.
 */
export interface BulkRun {
  /**
   * Said before the counts: a host with several commands names which ran.
   * A key or words, said where it is shown.
   */
  title: string;
  /** Words said into `title`'s `{name}`s, each itself said first. */
  values?: Readonly<Record<string, string>>;
  each(key: RecordKey): Promise<unknown>;
  /**
   * What the host's `onError` is told the command was
   * (`context.operation`): a declared action's id; `title` by default.
   */
  operation?: string;
  /**
   * How long one record is waited for, in milliseconds; past it the
   * record's outcome is unknown and the run moves on. No deadline by
   * default.
   */
  timeout?: number;
}

/**
 * One record the command did not take, and the reason why: the source's
 * words, or the action's own refusal as written (a key stays a key).
 */
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
  values?: Readonly<Record<string, string>>;
  succeeded: readonly RecordKey[];
  /** Sent, and refused by the source: the source's reason for each. */
  failed: readonly BulkFailure[];
  /**
   * Not sent: the action refused the record when its turn came
   * (`ActionRefusedError`), with the action's reason as written.
   */
  refused: readonly BulkFailure[];
  /**
   * Sent, and nobody knows whether it took: the request timed out, the
   * network went, the action's `timeout` passed, or the reader stopped
   * waiting. Let go of rather than left selected — sent again blind, a
   * refund could be paid twice — to be checked against the refreshed rows.
   */
  unknown: readonly BulkFailure[];
  /** Never started, because the command was stopped. */
  skipped: readonly RecordKey[];
  /**
   * What the run left selected — the failed, the refused and the never
   * started, in the order picked; none for a record's own command.
   */
  kept: readonly RecordKey[];
}

/**
 * The records a command runs over, and what a command does to them
 * afterwards: the ones still to be dealt with stay selected (`select`), the
 * rest are let go, and the view is read again.
 */
export interface BulkSelection {
  keys: readonly RecordKey[];
  /** Absent for a record's own command, which leaves the selection be. */
  select?(keys: readonly RecordKey[]): void;
  refresh(): void;
}

/** The command in flight on a surface. */
export interface BulkRunning {
  title: string;
  values?: Readonly<Record<string, string>>;
  progress: BulkProgress;
  /**
   * Stop was pressed: nothing more starts, and a second press stops waiting
   * for what is under way.
   */
  stopping: boolean;
}

/** A surface's one runner: its command in flight, and what the last came to. */
export interface ActionRunner {
  run(selection: BulkSelection, command: BulkRun): void;
  /**
   * Starts nothing more; what is already in flight settles. Pressed again
   * while stopping, it stops waiting: what is still in flight is reported
   * with an unknown outcome and the run settles at once, so a `run` that
   * never answers cannot hold the surface.
   */
  stop(): void;
  /** The command in flight, or `null`. */
  running: BulkRunning | null;
  outcome: BulkOutcome | null;
  dismiss(): void;
}

/** Where a failure the runner reports happened. */
export interface ActionFailureContext {
  /** The record the command failed, or went unanswered, for. */
  key: RecordKey;
  /** `BulkRun.operation`, else its title. */
  operation: string;
}

export interface ActionRunnerOptions {
  /**
   * How many records are in flight at once. A command writes, and a
   * selection of a hundred sent in one burst is a hundred writes landing on
   * one service at once; a handful at a time is quick and polite.
   */
  concurrency?: number;
  /**
   * Told of what a record's command threw — a failure, or a timeout or a
   * lost connection whose outcome is unknown — as it was thrown. Not of an
   * action's own refusal (`ActionRefusedError`) nor of an abort: neither is a
   * failure.
   */
  onError?(error: unknown, context: ActionFailureContext): void;
}

const CONCURRENCY = 4;

/** How one record came out. */
type Settled =
  { kind: 'done' } | { kind: 'failed' | 'refused' | 'unknown'; reason: string };

/** The names a timeout or a cancelled request is thrown under. */
const UNANSWERED = new Set(['AbortError', 'TimeoutError', 'FetchTimeoutError']);

/**
 * Whether a failure leaves the outcome unknown: the request may have
 * reached the service, and no answer came back — a timeout or an abort (by
 * name, on the error or what caused it), a request the network dropped
 * (fetch's `TypeError`), a gateway that gave up waiting (504).
 */
function unanswered(error: unknown, failure: SourceFailure): boolean {
  if (failure.unreachable === true || failure.status === 504) return true;
  let cause: unknown = error;
  for (let depth = 0; depth < 4 && typeof cause === 'object'; depth += 1) {
    if (cause === null) return false;
    const { name } = cause as { name?: unknown };
    if (typeof name === 'string' && UNANSWERED.has(name)) return true;
    cause = (cause as { cause?: unknown }).cause;
  }
  return false;
}

/** What the source said, never rejecting: a failure read badly says nothing. */
async function readSafely(error: unknown): Promise<SourceFailure> {
  try {
    return await sourceFailure(error);
  } catch {
    return { reason: '' };
  }
}

/**
 * Everything of a bulk command that is not the command itself: records a
 * handful at a time, progress while it runs, a stop that starts nothing
 * more (and, pressed again, stops waiting), each record's deadline, the
 * source's reason for every record that refused, and afterwards a refresh
 * of the page the reader is on with the failed and the unsent rows left
 * selected — they are the rows still to be dealt with. A record whose
 * outcome is unknown is let go: it is checked, not sent again.
 *
 * One command at a time: commands write, so a second press over the same
 * rows is not a newer read that can supersede the first but a second write.
 *
 * It is the engine's, one per record surface (a workbench's record view, a
 * board's record panel): the declared actions run through it
 * (`useRecordActions`), and so does a host's own command from a slot
 * (`run` on the slot's context), so every command on a surface reports on
 * its one line.
 */
export function useActionRunner(
  options: ActionRunnerOptions = {},
): ActionRunner {
  const concurrency = Math.max(
    1,
    Math.floor(options.concurrency ?? CONCURRENCY),
  );
  // The latest handler, read when a failure lands rather than when the run
  // began: a host that re-renders keeps one runner.
  const onError = useRef(options.onError);
  useEffect(() => {
    onError.current = options.onError;
  });
  const [running, setRunning] = useState<BulkRunning | null>(null);
  const [outcome, setOutcome] = useState<BulkOutcome | null>(null);
  // Refs as well as state: two presses in one frame both see the old state,
  // and the workers read the stop between records, not between renders.
  const busy = useRef(false);
  const stopping = useRef(false);
  const abandon = useRef<(() => void) | null>(null);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      // The host that asked is gone, and with it the line that said how far
      // the run had come and the way to stop it: nothing more is started.
      // What is under way lands; the service has it already.
      stopping.current = true;
    };
  }, []);

  const run = useCallback(
    (selection: BulkSelection, command: BulkRun) => {
      // A run asked for once the host has gone — a handler that outlived
      // it — would have nobody to show it or stop it: nothing is started.
      if (!alive.current) return;
      if (busy.current || selection.keys.length === 0) return;
      const keys = [...selection.keys];
      const succeeded: RecordKey[] = [];
      const failed: BulkFailure[] = [];
      const refused: BulkFailure[] = [];
      const unknown: BulkFailure[] = [];
      const operation = command.operation ?? command.title;
      const timeout = command.timeout;
      let next = 0;
      busy.current = true;
      stopping.current = false;
      // The second Stop: every record still awaited settles as unknown.
      const given = new Promise<Settled>(resolve => {
        abandon.current = () => resolve({ kind: 'unknown', reason: ABANDONED });
      });
      const report = () => {
        if (!alive.current) return;
        setRunning({
          title: command.title,
          ...(command.values ? { values: command.values } : {}),
          progress: {
            total: keys.length,
            done:
              succeeded.length +
              failed.length +
              refused.length +
              unknown.length,
            failed: failed.length,
          },
          stopping: stopping.current,
        });
      };
      const judge = async (
        error: unknown,
        key: RecordKey,
      ): Promise<Settled> => {
        if (error instanceof ActionRefusedError)
          return { kind: 'refused', reason: error.reason || UNAVAILABLE };
        const failure = await readSafely(error);
        if (!isCalledOff(error))
          try {
            onError.current?.(error, { key, operation });
          } catch {
            // The host's handler is the host's: the run carries on.
          }
        return {
          kind: unanswered(error, failure) ? 'unknown' : 'failed',
          reason: failure.reason.trim() === '' ? FAILED : failure.reason,
        };
      };
      const settle = async (key: RecordKey): Promise<Settled> => {
        const sent = (async (): Promise<Settled> => {
          try {
            await command.each(key);
            return { kind: 'done' };
          } catch (error) {
            return judge(error, key);
          }
        })();
        let timer: ReturnType<typeof setTimeout> | undefined;
        const racers = [sent, given];
        if (timeout !== undefined && Number.isFinite(timeout) && timeout > 0)
          racers.push(
            new Promise<Settled>(resolve => {
              timer = setTimeout(
                () => resolve({ kind: 'unknown', reason: TIMED_OUT }),
                timeout,
              );
            }),
          );
        try {
          return await Promise.race(racers);
        } finally {
          clearTimeout(timer);
        }
      };
      const worker = async (): Promise<void> => {
        while (next < keys.length && !stopping.current) {
          const key = keys[next];
          next += 1;
          const settled = await settle(key);
          if (settled.kind === 'done') succeeded.push(key);
          else
            ({ failed, refused, unknown })[settled.kind].push({
              key,
              reason: settled.reason,
            });
          report();
        }
      };
      const finish = () => {
        // Settled whatever came of the rest: the surface is free again.
        busy.current = false;
        abandon.current = null;
        // The host may have gone while the command ran: the records under
        // way took it, and the report simply has nobody to reach.
        if (!alive.current) return;
        const settled = new Set<RecordKey>([
          ...succeeded,
          ...[...failed, ...refused, ...unknown].map(each => each.key),
        ]);
        const skipped = keys.filter(key => !settled.has(key));
        // What is left to deal with stays picked: the refused, the failed
        // and the never started, in the order they were picked. A record
        // whose outcome is unknown is let go — checked, not sent again.
        const left = new Set<RecordKey>([
          ...[...failed, ...refused].map(each => each.key),
          ...skipped,
        ]);
        const kept = selection.select ? keys.filter(key => left.has(key)) : [];
        setRunning(null);
        setOutcome({
          title: command.title,
          ...(command.values ? { values: command.values } : {}),
          succeeded,
          failed,
          refused,
          unknown,
          skipped,
          kept,
        });
        try {
          selection.select?.(kept);
        } finally {
          selection.refresh();
        }
      };
      setOutcome(null);
      report();
      const workers = Array.from(
        { length: Math.min(concurrency, keys.length) },
        worker,
      );
      void Promise.all(workers).finally(finish);
    },
    [concurrency],
  );

  const stop = useCallback(() => {
    if (!busy.current) return;
    if (stopping.current) {
      abandon.current?.();
      return;
    }
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
