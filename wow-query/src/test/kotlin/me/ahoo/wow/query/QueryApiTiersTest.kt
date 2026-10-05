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
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.query.aggregation.DenseDateGrid
import me.ahoo.wow.query.dsl.ConditionDsl
import me.ahoo.wow.query.dsl.ListQueryDsl
import me.ahoo.wow.query.event.AbstractEventStreamQueryBackendFactory
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.event.RoutingEventStreamQueryBackendFactory
import me.ahoo.wow.query.event.filter.EventStreamQueryFilter
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.schema.AbsentValues
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.EventStreamQueryModelProfile
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.MaskRule
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
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.SnapshotQueryModelProfile
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.UnavailableQueryStorageAdapter
import me.ahoo.wow.query.snapshot.AbstractSnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.RoutingSnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryFilter
import org.junit.jupiter.api.Test
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

/**
 * wow-query's two public tiers (design 9.3.0 §5 T2): the backend SPI carries [WowSpi], and the application API does
 * not reach it. Kotlin asks for the opt-in wherever a signature mentions an SPI type, type arguments included, so an
 * application type whose constructor or method names one would make every application caller opt in to the SPI.
 */
class QueryApiTiersTest {
    @Test
    fun `the backend SPI is marked`() {
        val unmarked = SPI_TYPES.filterNot { it.isAnnotationPresent(WowSpi::class.java) }
        unmarked.assert().describedAs("Mark these backend SPI types @WowSpi: $unmarked").isEmpty()
        val unmarkedFunctions = SPI_FUNCTIONS.flatMap { (facade, names) ->
            val methods = Class.forName(facade).methods.filter { it.name in names }
            names.filter { name -> methods.none { it.name == name } }.map { "$facade.$it (missing)" } +
                methods.filterNot { it.isAnnotationPresent(WowSpi::class.java) }.map { "$facade.${it.name}" }
        }
        unmarkedFunctions.assert().describedAs("Mark these backend helpers @WowSpi: $unmarkedFunctions").isEmpty()
    }

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
                types.flatMap { it.classes() }.filter { it.isAnnotationPresent(WowSpi::class.java) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { spi -> "$member -> ${spi.map(Class<*>::getSimpleName)}" }
            }
        }
        leaks.assert().describedAs("Application API reaches the SPI: $leaks").isEmpty()
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

        val SPI_TYPES = listOf(
            QueryBackend::class.java,
            SnapshotQueryBackend::class.java,
            EventStreamQueryBackend::class.java,
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
            SnapshotQueryBackendFactory::class.java,
            AbstractSnapshotQueryBackendFactory::class.java,
            RoutingSnapshotQueryBackendFactory::class.java,
            EventStreamQueryBackendFactory::class.java,
            AbstractEventStreamQueryBackendFactory::class.java,
            RoutingEventStreamQueryBackendFactory::class.java,
            QueryBackendProvider::class.java,
            SimpleQueryBackendProvider::class.java,
            QueryBackendProviders::class.java,
            PojoOperands::class.java,
            DenseDateGrid::class.java,
            QueryStorageAdapter::class.java,
            UnavailableQueryStorageAdapter::class.java,
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
            QueryValueSchema::class.java,
            LogicalQuerySchema::class.java,
            QueryPathTemplate::class.java,
            QueryPathSegment::class.java,
            QueryModelProfile::class.java,
            SnapshotQueryModelProfile::class.java,
            EventStreamQueryModelProfile::class.java,
        )

        val SPI_FUNCTIONS = mapOf(
            "me.ahoo.wow.query.BackendQueries" to listOf("single", "list", "paged", "cursor", "aggregate"),
            "me.ahoo.wow.query.QueryOperandsKt" to listOf("operandValue", "requiredOperandValue"),
            "me.ahoo.wow.query.schema.QueryStorageAdapterKt" to listOf("isElementScope"),
            "me.ahoo.wow.query.schema.QueryStorageFamiliesKt" to listOf("storageFamilies"),
            "me.ahoo.wow.query.schema.QueryValueDomainsKt" to
                listOf("operationValues", "alternativesOrSelf", "hasArrayBranch"),
        )

        /** What applications declare, implement or call; none of it may name an SPI type. */
        val APPLICATION_TYPES = listOf(
            QueryGateway::class.java,
            SnapshotQueryGateway::class.java,
            EventStreamQueryGateway::class.java,
            QueryPolicy::class.java,
            QueryFilter::class.java,
            SnapshotQueryFilter::class.java,
            EventStreamQueryFilter::class.java,
            QueryContext::class.java,
            QueryEntryPolicy::class.java,
            QueryObserver::class.java,
            QueryAudit::class.java,
            QueryBudget::class.java,
            QueryScope::class.java,
            QueryRequestException::class.java,
            QueryExecutionException::class.java,
            QueryScopeRequiredException::class.java,
            QueryViolation::class.java,
            MaskRule::class.java,
            QuerySchemaSource::class.java,
            QuerySchemaRegistration::class.java,
            QuerySchemaDeclaration::class.java,
            QueryFieldDeclaration::class.java,
            QuerySchemaDeclarationBuilder::class.java,
            QueryFieldDeclarationBuilder::class.java,
            QueryModelSchemaProvider::class.java,
            ConditionDsl::class.java,
            ListQueryDsl::class.java,
        )
    }
}
