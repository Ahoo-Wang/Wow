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

import me.ahoo.wow.api.aggregate.AggregateRef
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The requests one execution of a saga's handler derives, keyed by what each asks: its target (type, tenant, and id,
 * or `unassigned` for a creation that leaves it to admission) and its command type, then its occurrence among the
 * requests of that execution with the same target and type. The key does not depend on the order the handler builds
 * its commands in: a retry whose reads answer in another order derives the same id for each request it derives again,
 * so the kernel answers each as its own duplicate, never one target's request as another's. A creation without an id
 * takes the same request id again, so admission gives it the aggregate its first attempt created.
 */
internal class DerivedRequests {
    private val occurrences = ConcurrentHashMap<String, AtomicInteger>()

    /** The occurrence, from 0, of the next request to [ref] of [commandType] in this execution. */
    fun next(ref: AggregateRef, commandType: Class<*>): Int =
        occurrences.computeIfAbsent(keyOf(ref, commandType)) { AtomicInteger() }.getAndIncrement()

    companion object {
        private const val UNASSIGNED = "unassigned"

        /** What a request asks: its target and its command type. */
        fun keyOf(ref: AggregateRef, commandType: Class<*>): String =
            "${ref.type}/${ref.tenantId}/${if (ref.isAssigned) ref.id else UNASSIGNED}#${commandType.name}"

        /** A name-based UUID of [name]. */
        fun idOf(name: String): String = UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString()
    }
}
