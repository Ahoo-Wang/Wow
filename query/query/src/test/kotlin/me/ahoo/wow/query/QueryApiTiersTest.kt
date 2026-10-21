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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.aggregation.DenseDateGrid
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.schema.AbsentValues
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.PagingSupport
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QueryFieldDeclarationBuilder
import me.ahoo.wow.query.schema.QueryModelProfile
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaDeclarationBuilder
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryStorageFamily
import me.ahoo.wow.query.schema.QueryStorageFamilyRules
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.SnapshotQueryModelProfile
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import org.junit.jupiter.api.Test
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

/**
 * The query module's two tiers (9.x design 9.3.0 §5 T2): the backend SPI, which storage modules implement and call,
 * and the application API, which must not reach it. 9.x marked the SPI `@WowSpi` and checked the marks; 10.x has no
 * such opt-in yet, so the SPI is listed here and only the second rule is checked.
 */
class QueryApiTiersTest {
    @Test
    fun `the application API does not expose the SPI`() {
        val leaks = APPLICATION_TYPES.flatMap { type ->
            val members = type.constructors.filter { Modifier.isPublic(it.modifiers) }.map { constructor ->
                "${type.simpleName}.<init>" to constructor.genericParameterTypes.toList()
            } + type.methods.filter { method ->
                // Kotlin `internal` members are public in bytecode under a name mangled with `$`.
                method.declaringClass.packageName.startsWith(PACKAGE) && !method.isSynthetic && '$' !in method.name
            }.map { method ->
                "${type.simpleName}.${method.name}" to method.genericParameterTypes.toList() + method.genericReturnType
            }
            members.mapNotNull { (member, types) ->
                types.flatMap { it.classes() }.filter { it in SPI_TYPES }
                    .takeIf { it.isNotEmpty() }
                    ?.let { spi -> "$member -> ${spi.map(Class<*>::getSimpleName)}" }
            }
        }
        leaks.assert().describedAs("Application API reaches the SPI: $leaks").isEmpty()
    }

    @Test
    fun `the backend primitives accept only an admitted query`() {
        val primitives = listOf("stream", "page", "count", "aggregate")
        QueryBackend::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }.distinct()
            .assert().containsExactlyInAnyOrder(
                *(primitives + "getAggregateType" + "getCursorPositions").toTypedArray()
            )
        QueryBackend::class.java.declaredMethods.filter { it.name in primitives }.forEach { method ->
            Modifier.isAbstract(method.modifiers).assert().isTrue()
            (method.genericParameterTypes.first() as ParameterizedType).rawType
                .assert().isEqualTo(AdmittedQuery::class.java)
        }
    }

    private fun Type.classes(): List<Class<*>> = when (this) {
        is Class<*> -> if (isArray) componentType.classes() else listOf(this)
        is ParameterizedType -> rawType.classes() + actualTypeArguments.flatMap { it.classes() }
        is WildcardType -> (upperBounds + lowerBounds).flatMap { it.classes() }
        is GenericArrayType -> genericComponentType.classes()
        is TypeVariable<*> -> emptyList()
        else -> emptyList()
    }

    private companion object {
        const val PACKAGE = "me.ahoo.wow.query"

        /** What a storage module implements or calls. */
        val SPI_TYPES: Set<Class<*>> = setOf(
            QueryBackend::class.java,
            QueryBackendBinding::class.java,
            PageWindow::class.java,
            GroupWindow::class.java,
            BackendPage::class.java,
            AdmittedQuery::class.java,
            ResolvedField::class.java,
            CursorPosition::class.java,
            CursorPositionCodec::class.java,
            QueryBackendFactory::class.java,
            AbstractQueryBackendFactory::class.java,
            RoutingQueryBackendFactory::class.java,
            DenseDateGrid::class.java,
            QueryStorageAdapter::class.java,
            QueryStorageFacts::class.java,
            StorageSupport::class.java,
            SupportMode::class.java,
            PagingSupport::class.java,
            AggregationSupport::class.java,
            AbsentValues::class.java,
            QueryStorageFamily::class.java,
            QueryStorageFamilyRules::class.java,
            QueryStorageType::class.java,
            QueryFieldBinding::class.java,
            QueryFieldBindingTemplate::class.java,
            QueryValueBindings::class.java,
            LogicalQuerySchema::class.java,
            QueryPathTemplate::class.java,
            QueryPathSegment::class.java,
            QueryModelProfile::class.java,
            SnapshotQueryModelProfile::class.java,
        )

        /** What applications declare, implement or call; none of it may name an SPI type. */
        val APPLICATION_TYPES = listOf(
            QueryGateway::class.java,
            QueryPolicy::class.java,
            QueryFilter::class.java,
            QueryContext::class.java,
            QueryEntryPolicy::class.java,
            QueryObserver::class.java,
            QueryLogObserver::class.java,
            QueryAudit::class.java,
            QueryBudget::class.java,
            QueryScope::class.java,
            QueryCaller::class.java,
            QueryError::class.java,
            QueryRequestException::class.java,
            QueryExecutionException::class.java,
            QueryTimeoutException::class.java,
            QueryScopeRequiredException::class.java,
            QueryViolation::class.java,
            QuerySchemaSource::class.java,
            QuerySchemaRegistration::class.java,
            QuerySchemaDeclaration::class.java,
            QueryFieldDeclaration::class.java,
            QuerySchemaDeclarationBuilder::class.java,
            QueryFieldDeclarationBuilder::class.java,
            QueryModelSchemaProvider::class.java,
        )
    }
}
