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

package me.ahoo.wow.api.subscribe

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * An event of another context's published stream that the subscriber has no class for (decision D259, DX-1c): its
 * type [name] (`context.aggregate.Event`), the [revision] of its schema and its [payload] as JSON text.
 *
 * A handler of another context's stream (`@OnEvent(aggregates = ["ctx.agg"])`) gets each published event as the
 * class of that context's api module when the api is on its class path and the event's revision is the one its
 * contract declares; otherwise as a PublishedEvent. A handler of each event of a type (decision D264) is not called
 * for a PublishedEvent: an event of another revision than the api's is not an `E`.
 *
 * Fields may be added (decision D59): the runtime builds it, a test with [of].
 */
class PublishedEvent @InternalWowApi constructor(val name: String, val revision: Int, val payload: String) {
    init {
        require(name.isNotBlank()) { "An event needs a name." }
        require(revision >= 1) { "An event revision starts at 1." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is PublishedEvent && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "PublishedEvent(name=$name, revision=$revision, payload=$payload)"

    private fun valueFields(): List<Any?> = listOf(name, revision, payload)

    companion object {
        /** A PublishedEvent of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(name: String, revision: Int, payload: String): PublishedEvent = PublishedEvent(name, revision, payload)
    }
}
