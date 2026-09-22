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
import { sourceReason } from '../runtime/index.js';
import type { RecordBulkActionContext } from './actions.js';

/**
 * What one business command did to the records it was handed.
 *
 * Keys rather than a count, because a partial outcome is only useful if the
 * host can say *which* ones are still waiting — and because the two lists
 * add up to the selection, so nothing can go quietly missing between them.
 */
export interface BulkOutcome {
  /** The records the command reports as done. */
  succeeded: readonly RecordKey[];
  /** The ones it could not do; a non-empty list is what makes it partial. */
  failed: readonly RecordKey[];
  /**
   * Why, in the service's own words, when it can say. One line: it is shown
   * after the counts, on the same line, and a stack trace is not wording.
   */
  reason?: string;
}

/**
 * What the toolbar's bulk slot hands a host, narrowed to what a command
 * needs: which records, and the two ways of showing what it did to them.
 *
 * `run` takes it rather than the hook, because the hook is called *above*
 * the workbench — an outcome outlives the selection it acted on, and a slot
 * that unmounts with the selection cannot report one.
 */
export type BulkSelection = Pick<
  RecordBulkActionContext,
  'keys' | 'clearSelection' | 'refresh'
>;

/** One command over a selection: press it, wait, read what it came to. */
export interface BulkCommand {
  /** Runs the command over `selection.keys`; ignored while one is running. */
  run(selection: BulkSelection): void;
  /** True from the click until the command settles; disables the control. */
  pending: boolean;
  /** The last outcome, until it is dismissed or another run replaces it. */
  outcome: BulkOutcome | null;
  /** Takes the outcome down; nothing expires on its own. */
  dismiss(): void;
}

/**
 * The half of a business bulk action that is the same in every host: the
 * in-flight flag, what the command came to, and what to do with the
 * selection afterwards.
 *
 * What is left for the host is the command itself and the button that calls
 * it — the two things that really are its own. Pair it with `/ui`'s
 * `BulkOutcomeStrip`, which draws the outcome in this package's own voice.
 *
 * Two rules the hosts kept rewriting differently:
 *
 * - **A refresh always follows.** The command changed records, so what is on
 *   screen is a moment out of date whether every record took it or only some.
 * - **The selection is cleared only when nothing failed.** A run with
 *   failures leaves it exactly as it was, because the next thing the user
 *   does is act on those same rows again, and re-picking them by hand out of
 *   a page that has just refreshed is the worst moment to ask for.
 */
export function useBulkCommand(
  command: (keys: readonly RecordKey[]) => Promise<BulkOutcome>,
): BulkCommand {
  const [pending, setPending] = useState(false);
  const [outcome, setOutcome] = useState<BulkOutcome | null>(null);
  // Commands write, so a second one over the same rows is not a second read
  // that can be superseded — it is a second write. The flag is a ref as well
  // as state because two clicks in one frame both see the old state.
  const running = useRef(false);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  const run = useCallback(
    (selection: BulkSelection) => {
      if (running.current || selection.keys.length === 0) return;
      const keys = [...selection.keys];
      const finish = (settled: BulkOutcome): void => {
        running.current = false;
        // The host may have navigated away while the command was in flight.
        // The records still took it — the report simply has nobody to reach.
        if (!alive.current) return;
        setPending(false);
        setOutcome(settled);
        selection.refresh();
        if (settled.failed.length === 0) selection.clearSelection();
      };

      running.current = true;
      setPending(true);
      setOutcome(null);
      void command(keys).then(finish, async (error: unknown) =>
        finish({
          succeeded: [],
          failed: keys,
          reason: await sourceReason(error),
        }),
      );
    },
    [command],
  );

  const dismiss = useCallback(() => setOutcome(null), []);
  return { run, pending, outcome, dismiss };
}

/**
 * A thrown command is every record failing, and the throw's own words are
 * the reason: a command that cannot report per record has still reported.
 */
