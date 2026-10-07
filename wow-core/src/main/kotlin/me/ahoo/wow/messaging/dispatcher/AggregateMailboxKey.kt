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

package me.ahoo.wow.messaging.dispatcher

import me.ahoo.wow.api.modeling.AggregateId

/**
 * The mailbox key of an aggregate's messages: bounded context, aggregate name and ID. The tenant is left out, as in
 * 9.2, where an aggregate's messages were grouped by the ID alone: messages of one aggregate ID run one at a time even
 * when they carry different tenants, so a message whose tenant differs (or is still unresolved) cannot overtake or run
 * beside the others of that aggregate.
 */
internal class AggregateMailboxKey(private val aggregateId: AggregateId) {
    private val hash: Int = (
        HASH_MAGIC * (HASH_MAGIC * aggregateId.contextName.hashCode() + aggregateId.aggregateName.hashCode()) +
            aggregateId.id.hashCode()
        )

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is AggregateMailboxKey) {
            return false
        }
        val that = other.aggregateId
        return hash == other.hash &&
            aggregateId.id == that.id &&
            aggregateId.aggregateName == that.aggregateName &&
            aggregateId.contextName == that.contextName
    }

    override fun hashCode(): Int = hash

    override fun toString(): String =
        "${aggregateId.contextName}.${aggregateId.aggregateName}@${aggregateId.id}"

    private companion object {
        const val HASH_MAGIC = 31
    }
}

/** This aggregate ID's [AggregateMailboxKey]: one mailbox per aggregate ID, whatever its tenant. */
internal fun AggregateId.toMailboxKey(): Any = AggregateMailboxKey(this)
