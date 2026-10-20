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

package me.ahoo.wow.spi.log

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The request key of one committed stream (decision D261): the aggregate's [version] it committed, its [requestId] and
 * its [commandType], the command's fingerprint (D55). The kernel builds an aggregate's recent requests from these.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class CommittedRequest @InternalWowApi constructor(
    val version: Int,
    val requestId: String,
    val commandType: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is CommittedRequest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "CommittedRequest(version=$version, requestId=$requestId, commandType=$commandType)"

    private fun valueFields(): List<Any> = listOf(version, requestId, commandType)

    companion object {
        /** A CommittedRequest of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(version: Int, requestId: String, commandType: String): CommittedRequest =
            CommittedRequest(version, requestId, commandType)

        /** The request key of [stream]. */
        @JvmStatic
        fun of(stream: EventStreamRecord): CommittedRequest =
            CommittedRequest(stream.version, stream.requestId, stream.commandType)
    }
}

/**
 * What [EventLog.readTail] reads of one aggregate in one round trip (decision D261): its [streams] from a version on,
 * in version order, and the request keys of its streams before that version, the latest first ([earlier]).
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class AggregateTail @InternalWowApi constructor(
    val streams: List<EventStreamRecord>,
    val earlier: List<CommittedRequest>,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is AggregateTail && streams == other.streams && earlier == other.earlier

    override fun hashCode(): Int = 31 * streams.hashCode() + earlier.hashCode()

    override fun toString(): String = "AggregateTail(streams=$streams, earlier=$earlier)"

    companion object {
        /** An AggregateTail of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(streams: List<EventStreamRecord>, earlier: List<CommittedRequest>): AggregateTail =
            AggregateTail(streams, earlier)
    }
}
