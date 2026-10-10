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

package me.ahoo.wow.rest

import me.ahoo.wow.rest.RouteVariables.BATCH_AFTER_ID
import me.ahoo.wow.rest.RouteVariables.BATCH_LIMIT
import me.ahoo.wow.rest.RouteVariables.CREATE_TIME
import me.ahoo.wow.rest.RouteVariables.HEAD_VERSION
import me.ahoo.wow.rest.RouteVariables.TAIL_VERSION
import me.ahoo.wow.rest.RouteVariables.VERSION

/**
 * The path suffixes of Wow's built-in aggregate routes: what follows `/{aggregate}`, or `/{aggregate}/{id}` for the
 * routes of one aggregate, after the tenant and owner segments a route may have.
 *
 * One definition for every side that names these paths: wow-openapi's route contributors that build the routes, and
 * wow-apiclient's `SNAPSHOT_*_RESOURCE_NAME` constants. They are part of the REST contract, frozen within a major
 * version. The TypeScript client keeps its own copy (`endpointPaths.ts` in `@ahoo-wang/wow-client`); its tests check
 * that copy against the committed contract snapshot (`wow-openapi/src/test/resources/openapi/
 * example-domain-contract.snapshot.json`), which these values produce.
 */
object RouteSuffixes {
    const val SNAPSHOT = "snapshot"
    const val SNAPSHOT_SCHEMA = "$SNAPSHOT/schema"
    const val SNAPSHOT_SCHEMA_REFRESH = "$SNAPSHOT_SCHEMA/refresh"
    const val SNAPSHOT_COUNT = "$SNAPSHOT/count"
    const val SNAPSHOT_AGGREGATION = "$SNAPSHOT/aggregation"
    const val SNAPSHOT_LIST = "$SNAPSHOT/list"
    const val SNAPSHOT_LIST_STATE = "$SNAPSHOT_LIST/state"
    const val SNAPSHOT_PAGED = "$SNAPSHOT/paged"
    const val SNAPSHOT_PAGED_STATE = "$SNAPSHOT_PAGED/state"
    const val SNAPSHOT_CURSOR = "$SNAPSHOT/cursor"
    const val SNAPSHOT_CURSOR_STATE = "$SNAPSHOT_CURSOR/state"
    const val SNAPSHOT_SINGLE = "$SNAPSHOT/single"
    const val SNAPSHOT_SINGLE_STATE = "$SNAPSHOT_SINGLE/state"

    /** Regenerates the snapshots of a batch of aggregates. */
    const val SNAPSHOT_BATCH = "$SNAPSHOT/{$BATCH_AFTER_ID}/{$BATCH_LIMIT}"

    const val EVENT = "event"
    const val EVENT_SCHEMA = "$EVENT/schema"
    const val EVENT_SCHEMA_REFRESH = "$EVENT_SCHEMA/refresh"
    const val EVENT_COUNT = "$EVENT/count"
    const val EVENT_AGGREGATION = "$EVENT/aggregation"
    const val EVENT_LIST = "$EVENT/list"
    const val EVENT_PAGED = "$EVENT/paged"
    const val EVENT_CURSOR = "$EVENT/cursor"

    /** The event stream of one aggregate between two versions, after `/{aggregate}/{id}`. */
    const val EVENT_RANGE = "$EVENT/{$HEAD_VERSION}/{$TAIL_VERSION}"

    /** Compensates one event stream of an aggregate, after `/{aggregate}/{id}`. */
    const val EVENT_COMPENSATE = "{$VERSION}/compensate"

    const val STATE = "state"

    /** Resends the state events of a batch of aggregates. */
    const val STATE_BATCH = "$STATE/{$BATCH_AFTER_ID}/{$BATCH_LIMIT}"
    const val STATE_TRACING = "$STATE/tracing"
    const val STATE_VERSIONED = "$STATE/{$VERSION}"
    const val STATE_TIME_BASED = "$STATE/time/{$CREATE_TIME}"
}
