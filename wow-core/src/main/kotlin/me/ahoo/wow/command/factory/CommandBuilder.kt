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

package me.ahoo.wow.command.factory

import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.withLocalFirst

interface CommandBuilder : Identifier {
    val body: Any
    val requestId: String?
    val aggregateId: String?
    val tenantId: String?
    val ownerId: String?
    val aggregateVersion: Int?
    val namedAggregate: NamedAggregate?
    val header: Header
    val createTime: Long
    val upstream: DomainEvent<*>?
    val ownerIdSameAsAggregateId: Boolean

    /**
     * Command Body
     */
    fun body(body: Any): CommandBuilder

    fun <C> bodyAs(): C {
        @Suppress("UNCHECKED_CAST")
        return body as C
    }

    /**
     * Command Message ID
     *
     * @see me.ahoo.wow.api.command.CommandMessage.id
     */
    fun id(id: String): CommandBuilder

    /**
     * Request Id
     * @see me.ahoo.wow.api.command.CommandMessage.requestId
     */
    fun requestId(requestId: String?): CommandBuilder

    fun requestIdIfAbsent(requestId: String): CommandBuilder {
        if (this.requestId == null) {
            requestId(requestId)
        }
        return this
    }

    /**
     * Aggregate Id
     * @see me.ahoo.wow.api.command.CommandMessage.aggregateId
     * @see me.ahoo.wow.api.modeling.AggregateId.id
     */
    fun aggregateId(aggregateId: String?): CommandBuilder

    /**
     * Tenant Id
     * @see me.ahoo.wow.api.command.CommandMessage.aggregateId
     * @see me.ahoo.wow.api.modeling.AggregateId.tenantId
     */
    fun tenantId(tenantId: String?): CommandBuilder

    /**
     * Owner Id
     *
     * @see me.ahoo.wow.api.modeling.AggregateId.ownerId
     */
    fun ownerId(ownerId: String?): CommandBuilder
    fun tenantIdIfAbsent(tenantId: String): CommandBuilder {
        if (this.tenantId == null) {
            tenantId(tenantId)
        }
        return this
    }

    /**
     * Aggregate Version
     * @see me.ahoo.wow.api.command.CommandMessage.aggregateVersion
     */
    fun aggregateVersion(aggregateVersion: Int?): CommandBuilder

    /**
     * Named Aggregate
     * @see me.ahoo.wow.api.command.CommandMessage.aggregateId
     * @see me.ahoo.wow.api.modeling.AggregateId.namedAggregate
     */
    fun namedAggregate(namedAggregate: NamedAggregate): CommandBuilder

    /**
     * Header
     * @see me.ahoo.wow.api.command.CommandMessage.header
     */
    fun header(header: Header): CommandBuilder

    /**
     * Header
     * @see me.ahoo.wow.api.command.CommandMessage.header
     */
    fun header(customize: (header: Header) -> Unit): CommandBuilder

    /**
     * Enable Local First mode?
     *
     * @see me.ahoo.wow.messaging.LocalFirstMessageBus
     */
    fun localFirst(localFirst: Boolean = true): CommandBuilder {
        return header {
            it.withLocalFirst(localFirst)
        }
    }

    /**
     * Create Time
     * @see me.ahoo.wow.api.messaging.NamedMessage.createTime
     */
    fun createTime(createTime: Long): CommandBuilder

    fun upstream(upstream: DomainEvent<*>): CommandBuilder

    fun ownerIdSameAsAggregateId(ownerIdSameAsAggregateId: Boolean): CommandBuilder

    companion object {

        fun Any.commandBuilder(): CommandBuilder {
            return builder(this)
        }

        fun builder(body: Any): CommandBuilder {
            return MutableCommandBuilder(body)
        }
    }
}

class MutableCommandBuilder(body: Any) : CommandBuilder {
    override var body: Any = body
        private set
    override var id: String = generateGlobalId()
        private set
    override var requestId: String? = null
        private set
    override var aggregateId: String? = null
        private set
    override var tenantId: String? = null
        private set
    override var ownerId: String? = null
        private set
    override var aggregateVersion: Int? = null
        private set
    override var namedAggregate: NamedAggregate? = null
        private set
    override var header: Header = DefaultHeader.empty()
        private set
    override var createTime: Long = System.currentTimeMillis()
        private set
    override var upstream: DomainEvent<*>? = null
        private set
    override var ownerIdSameAsAggregateId: Boolean = false
        private set

    override fun body(body: Any): CommandBuilder {
        this.body = body
        return this
    }

    override fun id(id: String): CommandBuilder {
        this.id = id
        return this
    }

    override fun requestId(requestId: String?): CommandBuilder {
        this.requestId = requestId
        return this
    }

    override fun aggregateId(aggregateId: String?): CommandBuilder {
        this.aggregateId = aggregateId
        return this
    }

    override fun tenantId(tenantId: String?): CommandBuilder {
        this.tenantId = tenantId
        return this
    }

    override fun ownerId(ownerId: String?): CommandBuilder {
        this.ownerId = ownerId
        return this
    }

    override fun aggregateVersion(aggregateVersion: Int?): CommandBuilder {
        this.aggregateVersion = aggregateVersion
        return this
    }

    override fun namedAggregate(namedAggregate: NamedAggregate): CommandBuilder {
        this.namedAggregate = namedAggregate
        return this
    }

    override fun header(header: Header): CommandBuilder {
        this.header = header
        return this
    }

    override fun header(customize: (header: Header) -> Unit): CommandBuilder {
        customize(header)
        return this
    }

    override fun createTime(createTime: Long): CommandBuilder {
        this.createTime = createTime
        return this
    }

    override fun upstream(upstream: DomainEvent<*>): CommandBuilder {
        this.upstream = upstream
        return this
    }

    override fun ownerIdSameAsAggregateId(ownerIdSameAsAggregateId: Boolean): CommandBuilder {
        this.ownerIdSameAsAggregateId = ownerIdSameAsAggregateId
        return this
    }
}
