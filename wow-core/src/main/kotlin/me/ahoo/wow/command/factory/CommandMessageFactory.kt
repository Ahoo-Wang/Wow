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
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import reactor.core.publisher.Mono

interface CommandMessageFactory {
    fun <C : Any> create(body: C, options: CommandOptions = CommandOptions.builder()): Mono<CommandMessage<C>>
}

interface CommandOptions : Identifier {
    val requestId: String?
    val aggregateId: String?
    val tenantId: String?
    val aggregateVersion: Int?
    val namedAggregate: NamedAggregate?
    val header: Header
    val createTime: Long

    fun id(id: String): CommandOptions
    fun requestId(requestId: String?): CommandOptions
    fun aggregateId(aggregateId: String?): CommandOptions
    fun tenantId(tenantId: String?): CommandOptions
    fun aggregateVersion(aggregateVersion: Int?): CommandOptions
    fun namedAggregate(namedAggregate: NamedAggregate?): CommandOptions
    fun header(header: Header): CommandOptions
    fun header(customize: (header: Header) -> Unit): CommandOptions
    fun createTime(createTime: Long): CommandOptions

    companion object {
        fun builder(): CommandOptions = SimpleCommandOptions()
    }
}

class SimpleCommandOptions : CommandOptions {
    override var id: String = GlobalIdGenerator.generateAsString()
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

    override fun id(id: String): CommandOptions {
        this.id = id
        return this
    }

    override fun requestId(requestId: String?): CommandOptions {
        this.requestId = requestId
        return this
    }

    override fun aggregateId(aggregateId: String?): CommandOptions {
        this.aggregateId = aggregateId
        return this
    }

    override fun tenantId(tenantId: String?): CommandOptions {
        this.tenantId = tenantId
        return this
    }

    override fun aggregateVersion(aggregateVersion: Int?): CommandOptions {
        this.aggregateVersion = aggregateVersion
        return this
    }

    override fun namedAggregate(namedAggregate: NamedAggregate?): CommandOptions {
        this.namedAggregate = namedAggregate
        return this
    }

    override fun header(header: Header): CommandOptions {
        this.header = header
        return this
    }

    override fun header(customize: (header: Header) -> Unit): CommandOptions {
        customize(header)
        return this
    }

    override fun createTime(createTime: Long): CommandOptions {
        this.createTime = createTime
        return this
    }
}
