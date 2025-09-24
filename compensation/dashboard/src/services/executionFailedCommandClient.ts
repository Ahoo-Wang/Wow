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
  CommandClient,
  type CommandRequest,
  type CommandResult,
  type FunctionInfo,
  type RecoverableType,
} from "@ahoo-wang/fetcher-wow";
import { compensationFetcher } from "./compensationFetcher";
import type { ApiMetadata } from "@ahoo-wang/fetcher-decorator";
import { HttpMethod } from "@ahoo-wang/fetcher";

export const executionFailedCommandClientOptions: ApiMetadata = {
  fetcher: compensationFetcher,
  basePath: "execution_failed/{id}",
};

export interface ApplyRetrySpec {
  maxRetries: number;
  minBackoff: number;
  executionTimeout: number;
}

export interface MarkRecoverable {
  recoverable: RecoverableType;
}

export interface ChangeFunction extends FunctionInfo {}

export class ExecutionFailedCommandClient extends CommandClient {
  constructor(apiMetadata?: ApiMetadata) {
    super(apiMetadata);
  }

  prepare(id: string): Promise<CommandResult> {
    const commandRequest: CommandRequest = {
      path: "prepare_compensation",
      method: HttpMethod.PUT,
      urlParams: {
        path: { id },
      },
      body: {},
    };
    return this.send(commandRequest);
  }

  forcePrepare(id: string): Promise<CommandResult> {
    const commandRequest: CommandRequest = {
      path: "force_prepare_compensation",
      method: HttpMethod.PUT,
      urlParams: {
        path: { id },
      },
      body: {},
    };
    return this.send(commandRequest);
  }

  applyRetrySpec(
    id: string,
    appRetrySpec: ApplyRetrySpec,
  ): Promise<CommandResult> {
    const commandRequest: CommandRequest<ApplyRetrySpec> = {
      path: "apply_retry_spec",
      method: HttpMethod.PUT,
      urlParams: {
        path: { id },
      },
      body: appRetrySpec,
    };
    return this.send(commandRequest);
  }

  markRecoverable(
    id: string,
    markRecoverable: MarkRecoverable,
  ): Promise<CommandResult> {
    const commandRequest: CommandRequest<MarkRecoverable> = {
      path: "mark_recoverable",
      method: HttpMethod.PUT,
      urlParams: {
        path: { id },
      },
      body: markRecoverable,
    };
    return this.send(commandRequest);
  }

  changeFunction(
    id: string,
    changeFunction: ChangeFunction,
  ): Promise<CommandResult> {
    const commandRequest: CommandRequest<ChangeFunction> = {
      path: "change_function",
      method: HttpMethod.PUT,
      urlParams: {
        path: { id },
      },
      body: changeFunction,
    };
    return this.send(commandRequest);
  }
}

export const executionFailedCommandClient = new ExecutionFailedCommandClient(
  executionFailedCommandClientOptions,
);
