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

package me.ahoo.wow.openapi.snapshot

import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.modeling.asStringWithAlias
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.AggregateRouteSpec
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.Schemas.asSchemName
import me.ahoo.wow.openapi.Schemas.asSchemaRef
import me.ahoo.wow.openapi.Schemas.asSchemas
import me.ahoo.wow.openapi.snapshot.SnapshotSchema.Companion.asSnapshotSchema
import me.ahoo.wow.serialization.state.StateAggregateRecords

class LoadSnapshotRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateMetadata: AggregateMetadata<*, *>
) : AggregateRouteSpec() {
    override val id: String
        get() = "${aggregateMetadata.asStringWithAlias()}.getSnapshot"
    override val method: String
        get() = Https.Method.GET
    override val appendIdPath: Boolean
        get() = true

    override val appendPathSuffix: String
        get() = "snapshot"

    override val summary: String
        get() = "Get snapshot"
    private val snapshotSchema = aggregateMetadata.state.aggregateType.asSnapshotSchema()

    override fun customize(apiResponse: ApiResponse): ApiResponse {
        return apiResponse.content(jsonContent(snapshotSchema.schemaRef))
    }

    override fun build(): RouteSpec {
        super.build()
        schemas[snapshotSchema.name] = snapshotSchema.schema
        return this
    }
}

data class SnapshotSchema(val name: String, val schema: Schema<*>, val schemaRef: Schema<*>) {

    companion object {
        fun Class<*>.asSnapshotSchema(): SnapshotSchema {
            val snapshotName = Snapshot::class.java.simpleName
            val schema = Snapshot::class.java.asSchemas()[Snapshot::class.java.asSchemName()]!!
            schema.properties[StateAggregateRecords.STATE] = this.asSchemaRef()
            val schemaName = "${asSchemName()}$snapshotName"
            return SnapshotSchema(schemaName, schema, schemaName.asSchemaRef())
        }
    }
}
