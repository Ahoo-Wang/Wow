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

package me.ahoo.wow.openapi.contract

import me.ahoo.wow.api.Wow

object BuiltInHttpRoutePaths {
    object Global {
        const val COMMAND_WAIT = "/${Wow.WOW}/command/wait"
        const val COMMAND_SEND = "/${Wow.WOW}/command/send"
        const val METADATA = "/${Wow.WOW}/metadata"
        const val GLOBAL_ID = "/${Wow.WOW}/id/global"
        const val BI_SCRIPT = "/${Wow.WOW}/bi/script"
    }
}

object BuiltInHttpRouteHandlerKeys {
    private const val OPENAPI = "me.ahoo.wow.openapi"
    private const val GLOBAL = "$OPENAPI.global"
    private const val AGGREGATE_COMMAND = "$OPENAPI.aggregate.command"
    private const val AGGREGATE_STATE = "$OPENAPI.aggregate.state"
    private const val AGGREGATE_SNAPSHOT = "$OPENAPI.aggregate.snapshot"
    private const val AGGREGATE_EVENT = "$OPENAPI.aggregate.event"

    object Global {
        const val COMMAND_WAIT = "$GLOBAL.CommandWaitRouteSpec"
        const val COMMAND_FACADE = "$AGGREGATE_COMMAND.CommandFacadeRouteSpec"
        const val METADATA = "$GLOBAL.GetWowMetadataRouteSpec"
        const val GLOBAL_ID = "$GLOBAL.GenerateGlobalIdRouteSpec"
        const val BI_SCRIPT = "$GLOBAL.GenerateBIScriptRouteSpec"
    }

    object Command {
        const val COMMAND = "$AGGREGATE_COMMAND.CommandRouteSpec"
    }

    object State {
        const val AGGREGATE_TRACING = "$AGGREGATE_STATE.AggregateTracingRouteSpec"
        const val LOAD_AGGREGATE = "$AGGREGATE_STATE.LoadAggregateRouteSpec"
        const val LOAD_VERSIONED_AGGREGATE = "$AGGREGATE_STATE.LoadVersionedAggregateRouteSpec"
        const val LOAD_TIME_BASED_AGGREGATE = "$AGGREGATE_STATE.LoadTimeBasedAggregateRouteSpec"
    }

    object Snapshot {
        const val COUNT = "$AGGREGATE_SNAPSHOT.CountSnapshotRouteSpec"
        const val LIST_QUERY = "$AGGREGATE_SNAPSHOT.ListQuerySnapshotRouteSpec"
        const val LIST_QUERY_STATE = "$AGGREGATE_SNAPSHOT.ListQuerySnapshotStateRouteSpec"
        const val PAGED_QUERY = "$AGGREGATE_SNAPSHOT.PagedQuerySnapshotRouteSpec"
        const val PAGED_QUERY_STATE = "$AGGREGATE_SNAPSHOT.PagedQuerySnapshotStateRouteSpec"
        const val SINGLE = "$AGGREGATE_SNAPSHOT.SingleSnapshotRouteSpec"
        const val SINGLE_STATE = "$AGGREGATE_SNAPSHOT.SingleSnapshotStateRouteSpec"
        const val LOAD = "$AGGREGATE_SNAPSHOT.LoadSnapshotRouteSpec"
        const val REGENERATE = "$AGGREGATE_SNAPSHOT.RegenerateSnapshotRouteSpec"
        const val BATCH_REGENERATE = "$AGGREGATE_SNAPSHOT.BatchRegenerateSnapshotRouteSpec"
    }

    object Event {
        const val COUNT = "$AGGREGATE_EVENT.CountEventStreamRouteSpec"
        const val LIST_QUERY = "$AGGREGATE_EVENT.ListQueryEventStreamRouteSpec"
        const val PAGED_QUERY = "$AGGREGATE_EVENT.PagedQueryEventStreamRouteSpec"
        const val LOAD = "$AGGREGATE_EVENT.LoadEventStreamRouteSpec"
        const val COMPENSATE = "$AGGREGATE_EVENT.EventCompensateRouteSpec"
        const val RESEND_STATE = "$AGGREGATE_EVENT.state.ResendStateEventRouteSpec"
    }
}
