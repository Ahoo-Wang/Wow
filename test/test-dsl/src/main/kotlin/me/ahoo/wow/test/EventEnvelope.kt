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

package me.ahoo.wow.test

import me.ahoo.wow.api.caller.Operator

/**
 * An event a command committed, with its stream's envelope: the aggregate [aggregateId] of
 * [tenantId] at [version], the [commandId] and [requestId] of the command that committed it, the request facts
 * ([headers]) it carried, the [operator] it was sent in the name of (none for an anonymous caller) and its [commitTime]
 * in epoch milliseconds, the time of the specification's clock. [body] is the event itself.
 */
class EventEnvelope<E : Any> internal constructor(
    val body: E,
    val aggregateId: String,
    val tenantId: String,
    val version: Int,
    val commandId: String,
    val requestId: String,
    val headers: Map<String, String>,
    val operator: Operator?,
    val commitTime: Long,
) {
    override fun toString(): String =
        "EventEnvelope(body=$body, aggregateId=$aggregateId, tenantId=$tenantId, version=$version, " +
            "commandId=$commandId, requestId=$requestId, headers=$headers, operator=$operator, commitTime=$commitTime)"
}
