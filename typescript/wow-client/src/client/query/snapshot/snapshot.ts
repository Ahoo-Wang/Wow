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

import type {
  AbacTaggable,
  AggregateId,
  DeletedCapable,
  EventIdCapable,
  EventTimeCapable,
  FirstEventTimeCapable,
  FirstOperatorCapable,
  OperatorCapable,
  OwnerId,
  SnapshotTimeCapable,
  SpaceIdCapable,
  StateCapable,
  TenantId,
  Version,
} from '../../../model/index.js';

/**
 * Interface for materialized snapshots with full capabilities.
 */
export interface MaterializedSnapshot<S>
  extends
    StateCapable<S>,
    AggregateId,
    TenantId,
    OwnerId,
    SpaceIdCapable,
    Version,
    EventIdCapable,
    FirstOperatorCapable,
    OperatorCapable,
    FirstEventTimeCapable,
    EventTimeCapable,
    SnapshotTimeCapable,
    AbacTaggable,
    DeletedCapable {}

/**
 * Interface for materialized snapshots with medium capabilities.
 *
 * Represents a materialized snapshot for medium data, implementing multiple capabilities through inheritance.
 * This interface is designed to be generic, capable of holding state data of any type.
 * Each snapshot corresponds to a specific version of the state within a tenant and owner context,
 * and records information such as event IDs and operation times to support tracing and auditing.
 *
 * Mirrors `MediumMaterializedSnapshot` in
 * `wow-api/src/main/kotlin/me/ahoo/wow/api/query/MediumMaterializedSnapshot.kt`.
 */
export interface MediumMaterializedSnapshot<S>
  extends
    StateCapable<S>,
    TenantId,
    OwnerId,
    SpaceIdCapable,
    Version,
    EventIdCapable,
    FirstOperatorCapable,
    OperatorCapable,
    FirstEventTimeCapable,
    EventTimeCapable,
    AbacTaggable {}

/**
 * Interface for simplified materialized snapshots with generic state.
 *
 * This interface implements multiple interfaces to provide version, materialization, first event time, and state information.
 *
 * Mirrors `SmallMaterializedSnapshot` in
 * `wow-api/src/main/kotlin/me/ahoo/wow/api/query/SmallMaterializedSnapshot.kt`.
 */
export interface SmallMaterializedSnapshot<S>
  extends StateCapable<S>, Version, FirstEventTimeCapable {}

/**
 * The field names of a materialized snapshot, for filters, sorts and
 * projections over snapshots (`tenantId`, `state`, `deleted`, …).
 *
 * A frozen name table: read `SnapshotMetadataFields.TENANT_ID`; each value is
 * a string literal type.
 */
export const SnapshotMetadataFields = Object.freeze({
  VERSION: 'version',
  TENANT_ID: 'tenantId',
  OWNER_ID: 'ownerId',
  SPACE_ID: 'spaceId',
  EVENT_ID: 'eventId',
  FIRST_EVENT_TIME: 'firstEventTime',
  EVENT_TIME: 'eventTime',
  FIRST_OPERATOR: 'firstOperator',
  OPERATOR: 'operator',
  SNAPSHOT_TIME: 'snapshotTime',
  TAGS: 'tags',
  DELETED: 'deleted',
  STATE: 'state',
} as const);
