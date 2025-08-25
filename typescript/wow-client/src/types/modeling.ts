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

import type { NamedBoundedContext } from './naming.ts';

/**
 * Interface for classes that have a creation time.
 */
export interface CreateTimeCapable {
  /**
   * Gets the creation time in milliseconds since the Unix epoch.
   */
  createTime: number;
}

export interface DeletedCapable {
  /**
   * Whether the aggregate is deleted.
   */
  deleted: boolean;
}

export interface EventIdCapable {
  /**
   * The event id of the aggregate.
   */
  eventId: string;
}

export interface EventTimeCapable {
  /**
   * The last event time of the aggregate, represented as a Unix timestamp in milliseconds.
   */
  eventTime: number;
}

export interface FirstEventTimeCapable {
  /**
   * The first event time of the aggregate, represented as a Unix timestamp in milliseconds.
   */
  firstEventTime: number;
}

export interface FirstOperatorCapable {
  /**
   * The first operator of the aggregate.
   */
  firstOperator: string;
}

export interface AggregateNameCapable {
  /**
   * The name of the aggregate.
   */
  aggregateName: string;
}

export interface NamedAggregate extends NamedBoundedContext, AggregateNameCapable {
}

export interface AggregateId extends TenantId, NamedAggregate {
  aggregateId: string;
}

export interface OperatorCapable {
  /**
   * The last operator of the aggregate.
   */
  operator: string;
}

export const DEFAULT_OWNER_ID = '';

/**
 * 用于标识资源的拥有者
 */
export interface OwnerId {
  /**
   * 资源拥有者的唯一标识符
   */
  ownerId: string;
}

export interface SnapshotTimeCapable {
  /**
   * The snapshot time of the aggregate, represented as a Unix timestamp in milliseconds.
   */
  snapshotTime: number;
}

export const DEFAULT_TENANT_ID = '(0)';

export interface TenantId {
  tenantId: string;
}

export interface StateCapable<S> {
  state: S;
}




