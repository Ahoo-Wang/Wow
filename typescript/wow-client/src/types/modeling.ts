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

import type { AliasBoundedContext, NamedBoundedContext } from './naming.ts';

/**
 * Interface for classes that have a creation time.
 */
export interface CreateTimeCapable {
  /**
   * Gets the creation time in milliseconds since the Unix epoch.
   */
  createTime: number;
}

/**
 * Interface for objects that track deletion status.
 */
export interface DeletedCapable {
  /**
   * Whether the aggregate is deleted.
   */
  deleted: boolean;
}

/**
 * Interface for objects that track event IDs.
 */
export interface EventIdCapable {
  /**
   * The event id of the aggregate.
   */
  eventId: string;
}

/**
 * Interface for objects that track event times.
 */
export interface EventTimeCapable {
  /**
   * The last event time of the aggregate, represented as a Unix timestamp in milliseconds.
   */
  eventTime: number;
}

/**
 * Interface for objects that track the first event time.
 */
export interface FirstEventTimeCapable {
  /**
   * The first event time of the aggregate, represented as a Unix timestamp in milliseconds.
   */
  firstEventTime: number;
}

/**
 * Interface for objects that track the first operator.
 */
export interface FirstOperatorCapable {
  /**
   * The first operator of the aggregate.
   */
  firstOperator: string;
}

/**
 * Interface for objects that have an aggregate name.
 */
export interface AggregateNameCapable {
  /**
   * The name of the aggregate.
   */
  aggregateName: string;
}

/**
 * Interface for named aggregates that belong to a bounded context.
 */
export interface NamedAggregate
  extends NamedBoundedContext,
    AggregateNameCapable {
}

export interface AliasAggregate
  extends AliasBoundedContext,
    AggregateNameCapable {
}

/**
 * Interface for aggregate IDs that combine tenant and named aggregate information.
 */
export interface AggregateId extends TenantId, NamedAggregate {
  aggregateId: string;
}

/**
 * Interface that provides the capability to contain an aggregate identifier.
 *
 * This interface is implemented by objects that need to reference an aggregate instance
 * through its unique identifier, which includes tenant, context, aggregate name and the
 * actual aggregate ID.
 */
export interface AggregateIdCapable {
  /**
   * The unique identifier of the aggregate instance.
   *
   * Contains information about the tenant, bounded context, aggregate name and the
   * actual aggregate ID string.
   */
  aggregateId: AggregateId;
}

/**
 * Interface for objects that track the last operator.
 */
export interface OperatorCapable {
  /**
   * The last operator of the aggregate.
   */
  operator: string;
}

export const DEFAULT_OWNER_ID = '';

/**
 * Interface for identifying resource owners.
 */
export interface OwnerId {
  /**
   * Unique identifier of the resource owner.
   */
  ownerId: string;
}

/**
 * Interface for objects that track snapshot times.
 */
export interface SnapshotTimeCapable {
  /**
   * The snapshot time of the aggregate, represented as a Unix timestamp in milliseconds.
   */
  snapshotTime: number;
}

/**
 * Interface for objects that have a tenant ID.
 */
export interface TenantId {
  tenantId: string;
}

/**
 * Interface for objects that hold state.
 */
export interface StateCapable<S> {
  state: S;
}
