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

import {
  CommandStage,
  ErrorCodes,
  waitStrategy,
  type CommandResult,
  type RecoverableType,
} from "@ahoo-wang/wow-client";
import type {
  ApplyRetrySpec,
  ChangeFunction,
  ExecutionFailedCommandClient,
} from "@/generated";
import { executionFailedCommandClient } from "@/services";

/**
 * The compensation commands the failed-executions workbench sends, one
 * execution at a time; `useBulkCommand` runs them over a selection.
 *
 * Each resolves once the execution's snapshot reflects the command, so the
 * refresh that follows shows the new state rather than the old one, and
 * each throws when the service refused it. A refusal is a 4xx whose body is
 * the command result; the workbench reads the service's own words out of it
 * (`sourceReason`), so the error is passed on as it came.
 */
export interface ExecutionCommands {
  /** `prepare_compensation`: retry now, within the retry spec. */
  prepare(id: string): Promise<void>;
  /** `force_prepare_compensation`: retry even past the retry limit. */
  forcePrepare(id: string): Promise<void>;
  /** `mark_recoverable`. */
  markRecoverable(id: string, recoverable: RecoverableType): Promise<void>;
  /** `apply_retry_spec`: the retry limit, backoff and timeout. */
  applyRetrySpec(id: string, spec: ApplyRetrySpec): Promise<void>;
  /** `change_function`: the handler the next retry runs. */
  changeFunction(id: string, target: ChangeFunction): Promise<void>;
}

/** Answer once the snapshot is written: the next query then sees it. */
const SNAPSHOT_WRITTEN = waitStrategy({ stage: CommandStage.SNAPSHOT });

/**
 * A result that came back refused without an error status still refuses, in
 * its own words.
 */
function taken(result: CommandResult): void {
  if (result.errorCode && result.errorCode !== ErrorCodes.SUCCEEDED)
    throw new Error(result.errorMsg || result.errorCode);
}

/** The commands over the generated client, on the console's fetcher. */
export function executionCommands(
  client: ExecutionFailedCommandClient = executionFailedCommandClient,
): ExecutionCommands {
  return {
    prepare: async (id) =>
      taken(
        await client.prepareCompensation(id, { headers: SNAPSHOT_WRITTEN }),
      ),
    forcePrepare: async (id) =>
      taken(
        await client.forcePrepareCompensation(id, {
          headers: SNAPSHOT_WRITTEN,
        }),
      ),
    markRecoverable: async (id, recoverable) =>
      taken(
        await client.markRecoverable(id, {
          headers: SNAPSHOT_WRITTEN,
          body: { recoverable },
        }),
      ),
    applyRetrySpec: async (id, spec) =>
      taken(
        await client.applyRetrySpec(id, {
          headers: SNAPSHOT_WRITTEN,
          body: spec,
        }),
      ),
    changeFunction: async (id, target) =>
      taken(
        await client.changeFunction(id, {
          headers: SNAPSHOT_WRITTEN,
          body: target,
        }),
      ),
  };
}
