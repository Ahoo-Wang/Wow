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
  DeletedCapable,
  EventIdCapable,
  EventTimeCapable,
  FirstEventTimeCapable,
  FirstOperatorCapable,
  NamedAggregate,
  OperatorCapable,
  OwnerId,
  SnapshotTimeCapable,
  StateCapable,
  TenantId,
  Version,
} from '../types';

/**
 * Interface for materialized snapshots with full capabilities.
 */
export interface MaterializedSnapshot<S>
  extends StateCapable<S>,
    NamedAggregate,
    TenantId,
    OwnerId,
    Version,
    EventIdCapable,
    FirstOperatorCapable,
    OperatorCapable,
    FirstEventTimeCapable,
    EventTimeCapable,
    SnapshotTimeCapable,
    DeletedCapable {}

/**
 * Interface for materialized snapshots with medium capabilities.
 *
 * Represents a materialized snapshot for medium data, implementing multiple capabilities through inheritance.
 * This interface is designed to be generic, capable of holding state data of any type.
 * Each snapshot corresponds to a specific version of the state within a tenant and owner context,
 * and records information such as event IDs and operation times to support tracing and auditing.
 */
export interface MaterializedSnapshot<S>
  extends StateCapable<S>,
    NamedAggregate,
    TenantId,
    OwnerId,
    Version,
    EventIdCapable,
    FirstOperatorCapable,
    OperatorCapable,
    FirstEventTimeCapable,
    EventTimeCapable {}

/**
 * Interface for simplified materialized snapshots with generic state.
 *
 * This interface implements multiple interfaces to provide version, materialization, first event time, and state information.
 */
export interface SmallMaterializedSnapshot<S>
  extends StateCapable<S>,
    NamedAggregate,
    Version,
    FirstEventTimeCapable {}
