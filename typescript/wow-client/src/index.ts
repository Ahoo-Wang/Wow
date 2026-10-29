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
 * `@ahoo-wang/wow-client`: the client of a Wow service's public
 * routes — commands with `Wow-Wait` and their event streams, state reads and
 * traces, the snapshot, event and dataset query routes, the Manifest, and
 * RFC 9457 problems as {@link WowProblemError}s with retry hints. Code
 * `wow-generator` generates from a Manifest builds on it.
 *
 * Build queries with the DSL of `@ahoo-wang/wow-client/dsl`.
 */
export {
  type ApplyTags,
  CommandClient,
  type CommandClientOptions,
  type CommandOptions,
  type CommandRequest,
  type CreateCommandOptions,
  type LifecycleCommand,
  LifecycleCommands,
  newRequestId,
  type TransferOwner,
  type TransferSpace,
} from './commands.js';
export {
  parseJson,
  stringifyJson,
  type TextNumbers,
  textNumbersBy,
  type TextNumbersByName,
} from './exactJson.js';
export {
  type DevelopmentCaller,
  developmentCaller,
  developmentCallerHeaders,
  DevelopmentCallerHeaders,
} from './developmentCaller.js';
export {
  formatScope,
  type RequestOptions,
  type TargetScope,
  type WowClientOptions,
  WowHeaders,
} from './http.js';
export {
  checkManifest,
  type Creation,
  fetchManifest,
  MANIFEST_FEATURES,
  MANIFEST_FORMAT,
  type RoutingCommand,
  type RoutingManifest,
  type RoutingServiceRoute,
} from './manifest.js';
export type * from './manifestTypes.js';
export * from './wire.js';
export {
  CommandRejectedError,
  DEFAULT_RETRY,
  FRAMEWORK_ERROR_PREFIX,
  FRAMEWORK_RETRIES,
  type FrameworkRetry,
  type FrameworkErrorCode,
  FrameworkErrorCodes,
  isProblem,
  parseRetryAfter,
  type Problem,
  PROBLEM_TYPE_PREFIX,
  readProblem,
  requestIdOf,
  type Retry,
  retryOf,
  toWowProblemError,
  WowProblemError,
} from './problem.js';
export {
  type CountQuery,
  type CountResult,
  DatasetClient,
  EventQueryClient,
  type StreamOptions,
  type Including,
  QueryModelClient,
  queryPath,
  SnapshotQueryClient,
} from './queries.js';
export {
  OwnStateClient,
  StateClient,
  type StateReadOptions,
  type StateReadResult,
  type StateTraceOptions,
} from './state.js';
export {
  type CommandResultStream,
  type Positioned,
  type StreamEnd,
  type StreamEndReason,
  type StreamResumeOptions,
} from './streams.js';
export {
  formatHop,
  formatWaitPlan,
  formatWaitTimeout,
  parseHop,
  parseWaitPlan,
  SNAPSHOT_SUBSCRIPTION,
  wait,
  WaitPlan,
} from './wait.js';
