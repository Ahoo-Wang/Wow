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

package me.ahoo.wow.openapi.schema

import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.openapi.SchemaRef.Companion.toRefSchema
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemaName
import me.ahoo.wow.serialization.MessageRecords

/**
 *
 * AggregateId Schema
 * @see me.ahoo.wow.api.modeling.AggregateId
 * @see me.ahoo.wow.serialization.AggregateIdJsonSerializer
 */
object AggregateIdSchema {

    val SCHEMA_NAME = requireNotNull(AggregateId::class.java.toSchemaName())
    val REF_SCHEMA_NAME = SCHEMA_NAME.toRefSchema()
    val SCHEMA = Schema<AggregateId>()

    init {
        SCHEMA.addProperty(MessageRecords.CONTEXT_NAME, StringSchema())
        SCHEMA.addProperty(MessageRecords.AGGREGATE_NAME, StringSchema())
        SCHEMA.addProperty(MessageRecords.AGGREGATE_ID, StringSchema())
        SCHEMA.addProperty(MessageRecords.TENANT_ID, StringSchema()._default(TenantId.DEFAULT_TENANT_ID))
    }
}
