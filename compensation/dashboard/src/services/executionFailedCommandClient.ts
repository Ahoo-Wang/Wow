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
  type ClientOptions,
  CommandClient,
  type CommandRequest,
  type CommandResult,
  type FunctionInfo,
  type RecoverableType,
} from "@ahoo-wang/fetcher-wow";
import {
  compensationFetcher,
} from "./compensationFetcher";
import { HttpMethod } from "@ahoo-wang/fetcher";

export const executionFailedCommandClientOptions: ClientOptions = {
  fetcher: compensationFetcher,
  basePath: "execution_failed/{id}",
};
export const executionFailedCommandClient = new CommandClient(
  executionFailedCommandClientOptions,
);

export interface ApplyRetrySpec {
  maxRetries: number;
  minBackoff: number;
  executionTimeout: number;
}

export interface MarkRecoverable {
  recoverable: RecoverableType;
}

export interface ChangeFunction extends FunctionInfo {}

export class ExecutionFailedCommandService {
  private sendCommand(
    path: string,
    id: string,
    commandBody: any = {},
    method: HttpMethod = HttpMethod.PUT,
  ): Promise<CommandResult> {
    const commandRequest: CommandRequest = {
      method: method,
      urlParams: {
        path: { id },
      },
      body: commandBody,
    };
    return executionFailedCommandClient.send(path, commandRequest);
  }
  prepare(id: string): Promise<CommandResult> {
    return this.sendCommand("prepare_compensation", id);
  }

  forcePrepare(id: string): Promise<CommandResult> {
    return this.sendCommand("force_prepare_compensation", id);
  }

  applyRetrySpec(
    id: string,
    appRetrySpec: ApplyRetrySpec,
  ): Promise<CommandResult> {
    return this.sendCommand("apply_retry_spec", id, appRetrySpec);
  }

  markRecoverable(
    id: string,
    markRecoverable: MarkRecoverable,
  ): Promise<CommandResult> {
    return this.sendCommand("mark_recoverable", id, markRecoverable);
  }

  changeFunction(
    id: string,
    changeFunction: ChangeFunction,
  ): Promise<CommandResult> {
    return this.sendCommand("change_function", id, changeFunction);
  }
}

export const executionFailedCommandService =
  new ExecutionFailedCommandService();
