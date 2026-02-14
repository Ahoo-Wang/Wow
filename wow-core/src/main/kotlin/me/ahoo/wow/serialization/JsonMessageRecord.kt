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

package me.ahoo.wow.serialization

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.OwnerId.Companion.orDefaultOwnerId
import me.ahoo.wow.api.modeling.SpaceIdCapable.Companion.orDefaultSpaceId
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.serialization.MessageRecords.AGGREGATE_ID
import me.ahoo.wow.serialization.MessageRecords.AGGREGATE_NAME
import me.ahoo.wow.serialization.MessageRecords.BODY
import me.ahoo.wow.serialization.MessageRecords.BODY_TYPE
import me.ahoo.wow.serialization.MessageRecords.COMMAND_ID
import me.ahoo.wow.serialization.MessageRecords.CONTEXT_NAME
import me.ahoo.wow.serialization.MessageRecords.CREATE_TIME
import me.ahoo.wow.serialization.MessageRecords.HEADER
import me.ahoo.wow.serialization.MessageRecords.ID
import me.ahoo.wow.serialization.MessageRecords.NAME
import me.ahoo.wow.serialization.MessageRecords.OWNER_ID
import me.ahoo.wow.serialization.MessageRecords.REQUEST_ID
import me.ahoo.wow.serialization.MessageRecords.SPACE_ID
import me.ahoo.wow.serialization.MessageRecords.TENANT_ID
import me.ahoo.wow.serialization.MessageRecords.VERSION
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

object MessageRecords {
    //region common
    const val ID = "id"
    const val HEADER = "header"
    const val BODY_TYPE = "bodyType"
    const val BODY = "body"
    const val CREATE_TIME = "createTime"
    const val NAME = "name"
    //endregion

    const val CONTEXT_NAME = "contextName"

    const val REQUEST_ID = "requestId"

    const val AGGREGATE_NAME = "aggregateName"
    const val AGGREGATE_ID = "aggregateId"
    const val TENANT_ID = "tenantId"
    const val OWNER_ID = "ownerId"
    const val SPACE_ID = "spaceId"
    const val COMMAND_ID = "commandId"
    const val VERSION = "version"
}

interface JsonRecord {
    val actual: ObjectNode
}

interface MessageIdRecord : JsonRecord {
    val id: String
        get() = actual[ID].asString()
}

interface HeaderRecord : JsonRecord {
    val header: ObjectNode
        get() {
            return actual[HEADER] as ObjectNode
        }

    fun toMessageHeader(): Header {
        val messageHeader = DefaultHeader.empty()
        header.properties().forEach {
            messageHeader[it.key] = it.value.asString()
        }
        return messageHeader
    }
}

interface MessageBodyTypeRecord : JsonRecord {
    val bodyType: String
        get() = actual[BODY_TYPE].asString()
}

interface MessageBodyRecord : JsonRecord {
    val body: JsonNode
        get() = actual[BODY]
}

interface MessageCreateTimeRecord : JsonRecord {
    val createTime: Long
        get() = actual[CREATE_TIME].asLong()
}

interface MessageNameRecord : JsonRecord, Named {
    override val name: String
        get() = actual[NAME].asString()
}

interface MessageRequestIdRecord : JsonRecord {
    val requestId: String
        get() = actual[REQUEST_ID].asString()
}

interface MessageNamedBoundedContextRecord : JsonRecord, NamedBoundedContext {
    override val contextName: String
        get() = actual[CONTEXT_NAME].asString()
}

interface MessageAggregateNameRecord : JsonRecord {
    val aggregateName: String
        get() = actual[AGGREGATE_NAME].asString()
}

interface MessageAggregateIdRecord : JsonRecord {
    val aggregateId: String
        get() = actual[AGGREGATE_ID].asString()
    val tenantId: String
        get() = actual[TENANT_ID].asString()
}

interface MessageRecord : MessageIdRecord, HeaderRecord, MessageBodyRecord, MessageCreateTimeRecord

class DelegatingMessageRecord(override val actual: ObjectNode) : MessageRecord

fun ObjectNode.toMessageRecord(): MessageRecord {
    return DelegatingMessageRecord(this)
}

interface NamedBoundedContextMessageRecord :
    MessageIdRecord,
    MessageNamedBoundedContextRecord,
    HeaderRecord,
    MessageBodyRecord,
    MessageCreateTimeRecord

class DelegatingNamedBoundedContextMessageRecord(override val actual: ObjectNode) : NamedBoundedContextMessageRecord

fun ObjectNode.toBoundedContextMessageRecord(): NamedBoundedContextMessageRecord {
    return DelegatingNamedBoundedContextMessageRecord(this)
}

interface MessageCommandIdRecord : JsonRecord {
    val commandId: String
        get() = actual[COMMAND_ID].asString()
}

interface MessageVersionRecord : JsonRecord {
    val version: Int
        get() = actual[VERSION].asInt()
}

interface OwnerIdRecord : JsonRecord {
    val ownerId: String
        get() = actual[OWNER_ID]?.asString().orDefaultOwnerId()
}

interface SpaceIdRecord : JsonRecord {
    val spaceId: String
        get() = actual[SPACE_ID]?.asString().orDefaultSpaceId()
}
