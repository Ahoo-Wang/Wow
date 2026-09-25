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

import type { ErrorInfo } from '../../error/index.js';
import type {
  ApplyAbacTags,
  FunctionInfoCapable,
  Identifier,
} from '../../model/index.js';
import type { PartialBy, RemoveReadonlyFields } from '@ahoo-wang/fetcher';

/**
 * Represents a target for compensation operations.
 *
 * This interface extends Identifier (with optional id) and FunctionInfoCapable to define
 * the structure for objects that can be targeted for compensation operations. Compensation
 * targets typically represent entities that can have operations reversed or corrected.
 */
export interface CompensationTarget
  extends PartialBy<Identifier, 'id'>, FunctionInfoCapable {}

/**
 * Represents a command to delete an aggregate.
 *
 * This interface defines the structure for commands that request the deletion of an aggregate.
 * It is typically used in conjunction with other command interfaces to provide a complete
 * command processing workflow.
 */
// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface DeleteAggregate {}

/**
 * The body a client sends for command `C`: its writable fields, with the
 * `readonly` ones left out. `CommandRequest` and the generated command
 * clients type their bodies with it.
 */
export type CommandBody<C> = RemoveReadonlyFields<C>;

/**
 * The body of Wow's built-in delete command, sent with `DELETE` to the
 * aggregate's route.
 */
export type DeleteAggregateCommand = CommandBody<DeleteAggregate>;

/**
 * Represents a command to recover an aggregate.
 *
 * This interface defines the structure for commands that request the recovery of an aggregate,
 * typically used in scenarios where an aggregate needs to be restored to a previous state
 * or recovered from an error condition.
 */
// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface RecoverAggregate {}

/**
 * The body of Wow's built-in recover command, which restores a deleted
 * aggregate; sent with `PUT` to the aggregate's `recover` route.
 */
export type RecoverAggregateCommand = CommandBody<RecoverAggregate>;

/**
 * Wow's built-in command that applies ABAC tags to an aggregate, sent with
 * `PUT` to the aggregate's `tags` route; the server refuses a blank tag key.
 */
// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface ApplyResourceTags extends ApplyAbacTags {}

/** The body of {@link ApplyResourceTags}. */
export type ApplyResourceTagsCommand = CommandBody<ApplyResourceTags>;

/**
 * Represents the result of a batch operation, containing information about
 * the pagination and error status of the operation.
 *
 * Extends ErrorInfo to include error details if the batch operation failed.
 */
export interface BatchResult extends ErrorInfo {
  /**
   * The cursor or identifier for the next item after the current batch.
   * Used for pagination to continue fetching the next batch of items.
   */
  after: string;

  /**
   * The number of items in the current batch.
   */
  size: number;
}
