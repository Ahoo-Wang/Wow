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

package me.ahoo.wow.serialization.event

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.SpaceId
import tools.jackson.databind.JsonNode

data class JsonDomainEvent(
    override val id: String,
    override val header: Header,
    val bodyType: String,
    override val body: JsonNode,
    override val aggregateId: AggregateId,
    override val ownerId: String,
    override val spaceId: SpaceId,
    override val version: Int,
    override val sequence: Int,
    override val revision: String,
    override val commandId: String,
    override val name: String,
    override val isLast: Boolean = true,
    override val createTime: Long = System.currentTimeMillis()
) : DomainEvent<JsonNode>, NamedAggregate by aggregateId
