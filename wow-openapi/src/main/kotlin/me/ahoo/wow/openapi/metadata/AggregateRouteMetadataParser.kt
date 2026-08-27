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

package me.ahoo.wow.openapi.metadata

import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.infra.reflection.ClassMetadata.visit
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.metadata.CacheableMetadataParser
import me.ahoo.wow.metadata.Metadata
import me.ahoo.wow.modeling.annotation.aggregateMetadata

object AggregateRouteMetadataParser : CacheableMetadataParser() {
    override fun <TYPE : Any, M : Metadata> parseToMetadata(type: Class<TYPE>): M {
        val visitor = AggregateRouteMetadataVisitor(type)
        type.kotlin.visit(visitor)
        @Suppress("UNCHECKED_CAST")
        return visitor.toMetadata() as M
    }
}

internal class AggregateRouteMetadataVisitor<C : Any>(private val aggregateType: Class<C>) :
    ClassVisitor<C, AggregateRouteMetadata<C>> {

    override fun toMetadata(): AggregateRouteMetadata<C> {
        val aggregateMetadata = aggregateType.aggregateMetadata<C, Any>()
        val aggregateRoute = aggregateType.kotlin.scanAnnotation<AggregateRoute>() ?: return AggregateRouteMetadata(
            enabled = true,
            aggregateMetadata = aggregateMetadata,
            resourceName = aggregateMetadata.aggregateName,
            spaced = false,
            owner = AggregateRoute.Owner.NEVER
        )

        return AggregateRouteMetadata(
            aggregateMetadata = aggregateMetadata,
            enabled = aggregateRoute.enabled,
            resourceName = aggregateRoute.resourceName.ifBlank { aggregateMetadata.aggregateName },
            spaced = aggregateRoute.spaced,
            owner = aggregateRoute.owner
        )
    }
}

fun <C : Any> Class<out C>.aggregateRouteMetadata(): AggregateRouteMetadata<C> {
    return AggregateRouteMetadataParser.parse(this)
}

inline fun <reified C : Any> aggregateRouteMetadata(): AggregateRouteMetadata<C> {
    return C::class.java.aggregateRouteMetadata()
}
