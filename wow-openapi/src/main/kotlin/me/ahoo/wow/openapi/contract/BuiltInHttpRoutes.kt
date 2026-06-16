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
    private const val OPENAPI = "wow.openapi"
    private const val GLOBAL = "$OPENAPI.global"
    private const val AGGREGATE_COMMAND = "$OPENAPI.aggregate.command"
    private const val AGGREGATE_STATE = "$OPENAPI.aggregate.state"
    private const val AGGREGATE_SNAPSHOT = "$OPENAPI.aggregate.snapshot"
    private const val AGGREGATE_EVENT = "$OPENAPI.aggregate.event"

    object Global {
        const val COMMAND_WAIT = "$GLOBAL.command.wait"
        const val COMMAND_FACADE = "$GLOBAL.command.facade"
        const val METADATA = "$GLOBAL.metadata.get"
        const val GLOBAL_ID = "$GLOBAL.id.generate"
        const val BI_SCRIPT = "$GLOBAL.bi.script.generate"
    }

    object Command {
        const val COMMAND = "$AGGREGATE_COMMAND.dispatch"
    }

    object State {
        const val AGGREGATE_TRACING = "$AGGREGATE_STATE.tracing"
        const val LOAD_AGGREGATE = "$AGGREGATE_STATE.load"
        const val LOAD_VERSIONED_AGGREGATE = "$AGGREGATE_STATE.load.versioned"
        const val LOAD_TIME_BASED_AGGREGATE = "$AGGREGATE_STATE.load.time-based"
    }

    object Snapshot {
        const val COUNT = "$AGGREGATE_SNAPSHOT.count"
        const val LIST_QUERY = "$AGGREGATE_SNAPSHOT.list-query"
        const val LIST_QUERY_STATE = "$AGGREGATE_SNAPSHOT.list-query-state"
        const val PAGED_QUERY = "$AGGREGATE_SNAPSHOT.paged-query"
        const val PAGED_QUERY_STATE = "$AGGREGATE_SNAPSHOT.paged-query-state"
        const val SINGLE = "$AGGREGATE_SNAPSHOT.single"
        const val SINGLE_STATE = "$AGGREGATE_SNAPSHOT.single-state"
        const val LOAD = "$AGGREGATE_SNAPSHOT.load"
        const val REGENERATE = "$AGGREGATE_SNAPSHOT.regenerate"
        const val BATCH_REGENERATE = "$AGGREGATE_SNAPSHOT.batch-regenerate"
    }

    object Event {
        const val COUNT = "$AGGREGATE_EVENT.count"
        const val LIST_QUERY = "$AGGREGATE_EVENT.list-query"
        const val PAGED_QUERY = "$AGGREGATE_EVENT.paged-query"
        const val LOAD = "$AGGREGATE_EVENT.load"
        const val COMPENSATE = "$AGGREGATE_EVENT.compensate"
        const val RESEND_STATE = "$AGGREGATE_EVENT.state.resend"
    }
}
