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

package me.ahoo.wow.openapi.query

import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.RequestBody
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.asStringWithAlias
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.AggregateRouteSpec
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.Schemas.getSchemaRef

class IdsQueryAggregateRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateMetadata: AggregateMetadata<*, *>
) : AggregateRouteSpec() {
    override val id: String
        get() = "${aggregateMetadata.asStringWithAlias()}.idsQueryStateAggregate"
    override val method: String
        get() = Https.Method.POST

    override val appendPathSuffix: String
        get() = "state/ids"
    override val ignoreTenant: Boolean
        get() = true
    override val summary: String
        get() = "Load state aggregate by ids"

    override val requestBodyType: Class<*>
        get() = if (super.ignoreTenant) {
            String::class.java
        } else {
            TenantIdsQuery::class.java
        }
    override val requestBody: RequestBody
        get() {
            val arraySchema = ArraySchema()
            val requestBody = RequestBody().required(true).content(content(arraySchema))
            if (super.ignoreTenant) {
                arraySchema.items(StringSchema())
            } else {
                arraySchema.items(requestBodyType.getSchemaRef())
            }
            return requestBody
        }
    override val isArrayResponse: Boolean
        get() = true
    override val responseType: Class<*>
        get() = aggregateMetadata.state.aggregateType
}

data class TenantIdsQuery(
    val tenantId: String,
    val id: String
)
