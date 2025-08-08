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

import type { Identifier } from "./common.ts";
import type { AggregateId } from "./modeling.ts";
import type { FunctionInfo } from "./function.ts";
import type { ErrorInfo } from "./error.ts";

export interface CommandId {
  commandId: string;
}

export interface RequestId {
  requestId: string;
}

export type CommandStage =
  | "SENT"
  | "PROCESSED"
  | "SNAPSHOT"
  | "PROJECTED"
  | "EVENT_HANDLED"
  | "SAGA_HANDLED";

export interface CommandResult
  extends Identifier,
    AggregateId,
    ErrorInfo,
    CommandId,
    RequestId {
  waitCommandId: string;
  aggregateVersion?: number;
  stage: CommandStage;
  function: FunctionInfo;
  signalTime: number;
  result: Map<string, any>;
}
