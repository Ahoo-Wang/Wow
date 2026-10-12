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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.decide.Decision

/**
 * What an annotated command function returned, as a [Decision]: the conversion code that `wow-compiler` generates
 * calls, so annotated functions keep returning what they return in 9.x (decision D37).
 *
 * - a [Decision] stays as it is;
 * - nothing (`Unit`, `null`, an empty collection or array) changes nothing;
 * - a collection or array is its events, in order;
 * - anything else is one event.
 */
fun decisionOf(result: Any?): Decision = when (result) {
    is Decision -> result
    else -> eventsOf(result).let { if (it.isEmpty()) Decision.NoOp else Decision.Events(it) }
}

/** What an annotated after hook returned, as events: like [decisionOf], but a hook cannot reject or decide. */
fun eventsOf(result: Any?): List<Any> = when (result) {
    null, Unit -> emptyList()
    is Decision -> error("An after hook returns events, not a decision: $result")
    is Iterable<*> -> result.map { requireNotNull(it) { "An event list holds a null." } }
    is Array<*> -> result.map { requireNotNull(it) { "An event array holds a null." } }
    else -> listOf(result)
}
