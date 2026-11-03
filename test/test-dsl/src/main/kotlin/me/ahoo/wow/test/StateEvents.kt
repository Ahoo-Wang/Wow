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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.subscribe.PublishedEvent
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.spi.json.WowJson

/**
 * The commit of [events] at [version] of [ref], leaving it in [state], as a processor's handler receives it: the
 * events of its own context as their classes, those of a stream it reads from another context as the classes of
 * that context's api, or [publishedEvent]s for those it has no class of at their revision. The command's ids default to `command-<version>` and `request-<version>`, and the commit time
 * to [DEFAULT_START].
 */
@Suppress("LongParameterList")
fun <S> stateEvent(
    ref: AggregateRef,
    state: S,
    vararg events: Any,
    version: Int = 1,
    commandType: String = "${ref.type}.Command",
    commandId: String = "command-$version",
    requestId: String = "request-$version",
    commitTime: Long = DEFAULT_START.toEpochMilli(),
    scope: Scope = Scope.NONE,
    causation: Causation? = null,
    headers: Map<String, String> = emptyMap(),
    operator: Operator? = null,
): StateEvent<S> = StateEvent(
    ref = ref,
    version = version,
    commandId = commandId,
    requestId = requestId,
    commandType = commandType,
    commitTime = commitTime,
    events = events.toList(),
    state = state,
    scope = scope,
    causation = causation,
    headers = headers,
    operator = operator,
)

/**
 * An event of a stream another context publishes that the handler has no api class of at its revision, as it receives
 * it: its full [name] (`<context>.<aggregate>.<event>`), [revision] and [body] as JSON text, an empty object
 * by default.
 */
fun publishedEvent(name: String, body: Any = emptyMap<String, Any>(), revision: Int = 1): PublishedEvent =
    PublishedEvent.of(name, revision, WowJson.mapper.writeValueAsString(body))
