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
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader

interface CommandBuilder<C : Any> : Identifier {
    val body: C
    val requestId: String?
    val aggregateId: String?
    val tenantId: String?
    val aggregateVersion: Int?
    val namedAggregate: NamedAggregate?
    val header: Header
    val createTime: Long

    /**
     * Command Message ID
     *
     * @see me.ahoo.wow.api.command.CommandMessage.id
     */
    fun id(id: String): CommandBuilder<C>

    /**
     * Request Id
     * @see me.ahoo.wow.api.command.CommandMessage.requestId
     */
    fun requestId(requestId: String?): CommandBuilder<C>

    fun requestIfIfAbsent(requestId: String): CommandBuilder<C> {
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
    fun aggregateId(aggregateId: String?): CommandBuilder<C>

    /**
     * Tenant Id
     * @see me.ahoo.wow.api.command.CommandMessage.aggregateId
     * @see me.ahoo.wow.api.modeling.AggregateId.tenantId
     */
    fun tenantId(tenantId: String?): CommandBuilder<C>

    fun tenantIdIfAbsent(tenantId: String): CommandBuilder<C> {
        if (this.tenantId == null) {
            tenantId(tenantId)
        }
        return this
    }

    /**
     * Aggregate Version
     * @see me.ahoo.wow.api.command.CommandMessage.aggregateVersion
     */
    fun aggregateVersion(aggregateVersion: Int?): CommandBuilder<C>

    /**
     * Named Aggregate
     * @see me.ahoo.wow.api.command.CommandMessage.aggregateId
     * @see me.ahoo.wow.api.modeling.AggregateId.namedAggregate
     */
    fun namedAggregate(namedAggregate: NamedAggregate): CommandBuilder<C>

    /**
     * Header
     * @see me.ahoo.wow.api.command.CommandMessage.header
     */
    fun header(header: Header): CommandBuilder<C>

    /**
     * Header
     * @see me.ahoo.wow.api.command.CommandMessage.header
     */
    fun header(customize: (header: Header) -> Unit): CommandBuilder<C>

    /**
     * Create Time
     * @see me.ahoo.wow.api.messaging.NamedMessage.createTime
     */
    fun createTime(createTime: Long): CommandBuilder<C>

    companion object {

        fun <C : Any> C.commandBuilder(): CommandBuilder<C> {
            return builder(this)
        }

        fun <C : Any> builder(body: C): CommandBuilder<C> {
            return MutableCommandBuilder(body)
        }
    }
}

class MutableCommandBuilder<C : Any>(override val body: C) : CommandBuilder<C> {
    override var id: String = generateGlobalId()
        private set
    override var requestId: String? = null
        private set
    override var aggregateId: String? = null
        private set
    override var tenantId: String? = null
        private set
    override var aggregateVersion: Int? = null
        private set
    override var namedAggregate: NamedAggregate? = null
        private set
    override var header: Header = DefaultHeader.empty()
        private set
    override var createTime: Long = System.currentTimeMillis()
        private set

    override fun id(id: String): CommandBuilder<C> {
        this.id = id
        return this
    }

    override fun requestId(requestId: String?): CommandBuilder<C> {
        this.requestId = requestId
        return this
    }

    override fun aggregateId(aggregateId: String?): CommandBuilder<C> {
        this.aggregateId = aggregateId
        return this
    }

    override fun tenantId(tenantId: String?): CommandBuilder<C> {
        this.tenantId = tenantId
        return this
    }

    override fun aggregateVersion(aggregateVersion: Int?): CommandBuilder<C> {
        this.aggregateVersion = aggregateVersion
        return this
    }

    override fun namedAggregate(namedAggregate: NamedAggregate): CommandBuilder<C> {
        this.namedAggregate = namedAggregate
        return this
    }

    override fun header(header: Header): CommandBuilder<C> {
        this.header = header
        return this
    }

    override fun header(customize: (header: Header) -> Unit): CommandBuilder<C> {
        customize(header)
        return this
    }

    override fun createTime(createTime: Long): CommandBuilder<C> {
        this.createTime = createTime
        return this
    }
}
