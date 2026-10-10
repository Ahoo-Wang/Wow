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

package me.ahoo.wow.openapi.context

import com.fasterxml.classmate.ResolvedType
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.wow.openapi.contract.HttpComponent
import me.ahoo.wow.query.schema.InferredQuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySensitivityPolicy
import me.ahoo.wow.schema.query.JsonQueryModelSource
import java.lang.reflect.Type

/**
 * What an [HttpComponent] is built with: schema generation, plus [ref] for referencing another component from inside
 * this one. The renderer registers the built component under its key; the context exposes no way to register, build
 * or finish components itself.
 */
interface HttpComponentContext {
    /** Whether schemas (and components) are inlined rather than referenced. */
    val inline: Boolean

    fun resolveType(mainTargetType: Type, vararg typeParameters: Type): ResolvedType

    /** The schema of the type, usually a `$ref` that the renderer resolves when the document is finished. */
    fun schema(mainTargetType: Type, vararg typeParameters: Type): Schema<*>

    /** An array schema whose items are the schema of the type. */
    fun arraySchema(mainTargetType: Type, vararg typeParameters: Type): Schema<*>

    /** Registers [schema] as the schema component [key] and returns a `$ref` to it. */
    fun componentSchema(key: String, schema: Schema<*>): Schema<*>

    /**
     * A reference to [component] (a `$ref`, or the component itself when schemas are inlined), building it first
     * unless the render already did.
     */
    fun <T : Any> ref(component: HttpComponent<T>): T
}

/**
 * This context seen as an [HttpComponentContext]: schema generation delegates to it, and [ref] resolves a component
 * reference.
 */
internal fun OpenAPIComponentContext.asHttpComponentContext(
    queryFieldSources: QueryFieldSources = QueryFieldSources.INFERRED,
    ref: (HttpComponent<*>) -> Any
): HttpComponentContext {
    val context = this
    val fieldSources = queryFieldSources
    return object : HttpComponentContext, QueryFieldSourcesCapable {
        override val queryFieldSources: QueryFieldSources
            get() = fieldSources

        override val inline: Boolean
            get() = context.inline

        override fun resolveType(mainTargetType: Type, vararg typeParameters: Type): ResolvedType =
            context.resolveType(mainTargetType, *typeParameters)

        override fun schema(mainTargetType: Type, vararg typeParameters: Type): Schema<*> =
            context.schema(mainTargetType, *typeParameters)

        override fun arraySchema(mainTargetType: Type, vararg typeParameters: Type): Schema<*> =
            context.arraySchema(mainTargetType, *typeParameters)

        override fun componentSchema(key: String, schema: Schema<*>): Schema<*> =
            context.componentSchema(key, schema)

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> ref(component: HttpComponent<T>): T = ref(component) as T
    }
}

/**
 * Where the queryable fields of the aggregated query request bodies come from: the query schema [sources] an
 * application declares, merged under its [sensitivity] policy as the query schema Catalog merges them.
 */
internal class QueryFieldSources(
    val sources: List<QuerySchemaSource>,
    val sensitivity: QuerySensitivityPolicy = QuerySensitivityPolicy.DEFAULT,
) {
    companion object {
        /** Wow's type inference alone: the fields of the aggregate's state. */
        val INFERRED = QueryFieldSources(listOf(InferredQuerySchemaSource(JsonQueryModelSource())))
    }
}

internal interface QueryFieldSourcesCapable {
    val queryFieldSources: QueryFieldSources
}

/** The query field sources this context renders with; [QueryFieldSources.INFERRED] for a context built elsewhere. */
internal val HttpComponentContext.queryFieldSources: QueryFieldSources
    get() = (this as? QueryFieldSourcesCapable)?.queryFieldSources ?: QueryFieldSources.INFERRED
