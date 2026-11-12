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
 * One stream [EventLog.appendAll] appends, with the [transport] section stored beside it, if any: the bytes
 * [TransportCodec] wrote, which a backend stores as they are, as [EventLog.append] takes them.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class Append @InternalWowApi constructor(val stream: EventStreamRecord, val transport: ByteArray? = null) {
    override fun equals(other: Any?): Boolean =
        this === other || other is Append && stream == other.stream && transport.contentEquals(other.transport)

    override fun hashCode(): Int = 31 * stream.hashCode() + transport.contentHashCode()

    override fun toString(): String = "Append(stream=$stream, transport=${transport?.decodeToString()})"

    companion object {
        /** An Append of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(stream: EventStreamRecord, transport: ByteArray? = null): Append = Append(stream, transport)
    }
}
