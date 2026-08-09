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

package me.ahoo.wow.query.backend

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.query.internal.model.QueryDocumentKind
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.normalization.PredicateOperator
import me.ahoo.wow.query.internal.normalization.SearchScopeId
import me.ahoo.wow.query.internal.planning.PlanningFixtures
import me.ahoo.wow.query.internal.schema.QuerySchemaRegistry
import org.junit.jupiter.api.Test

@OptIn(ExperimentalQueryBackendApi::class)
class QueryDocumentSchemaTest {

    @Test
    fun `schema digest should be independent of registration order`() {
        val original = PlanningFixtures.schema
        val reordered = QueryDocumentSchema(
            target = original.target,
            fields = original.fields.values.reversed(),
            searchScopes = original.searchScopes.values.reversed(),
        )

        reordered.contractId.assert().isEqualTo(original.contractId)
        reordered.fields.assert().isEqualTo(original.fields)
    }

    @Test
    fun `schema should reject missing prefixes and duplicate ids`() {
        val leaf = QueryFieldSchema(
            id = QueryFieldId.Path(listOf("missing", "leaf")),
            type = LogicalFieldType.Text,
            presence = Presence.OPTIONAL,
            nullability = Nullability.NULLABLE,
            allowedOperators = setOf(PredicateOperator.EQ),
            capabilities = setOf(FieldCapability.EXACT),
        )

        assertThrownBy<IllegalArgumentException> {
            QueryDocumentSchema(PlanningFixtures.target, listOf(leaf), emptyList())
        }
        assertThrownBy<IllegalArgumentException> {
            QueryDocumentSchema(
                PlanningFixtures.target,
                listOf(
                    PlanningFixtures.schema.fields.getValue(PlanningFixtures.identity),
                    PlanningFixtures.schema.fields.getValue(PlanningFixtures.identity),
                ),
                emptyList(),
            )
        }
    }

    @Test
    fun `search scope should resolve a legacy logical alias without physical expansion`() {
        val definition = PlanningFixtures.schema.resolveLegacySearchScope(
            owner = null,
            alias = PlanningFixtures.description,
        )

        val resolved = checkNotNull(definition)
        resolved.id.assert().isEqualTo(PlanningFixtures.searchScopeId)
        resolved.fields.assert().containsExactly(PlanningFixtures.description)
        PlanningFixtures.schema.resolveField(QueryFieldId.Path(listOf("aggregateId"))).assert()
            .isEqualTo(PlanningFixtures.identity)
    }

    @Test
    fun `search scope should enforce its nearest element owner and unambiguous alias`() {
        val itemName = PlanningFixtures.schema.fields.getValue(PlanningFixtures.itemName).let { field ->
            QueryFieldSchema(
                field.id,
                field.type,
                field.presence,
                field.nullability,
                field.allowedOperators,
                field.capabilities + FieldCapability.FULL_TEXT,
                field.logicalAliases,
            )
        }
        val nestedScope = QuerySearchScopeDefinition(
            SearchScopeId("item-name"),
            PlanningFixtures.items,
            listOf(PlanningFixtures.itemName),
            setOf(PlanningFixtures.itemName),
        )
        QueryDocumentSchema(
            PlanningFixtures.target,
            PlanningFixtures.schema.fields.values.map { field ->
                if (field.id == PlanningFixtures.itemName) itemName else field
            },
            PlanningFixtures.schema.searchScopes.values + nestedScope,
        )

        val crossOwner = QuerySearchScopeDefinition(
            SearchScopeId("invalid-nested"),
            PlanningFixtures.items,
            listOf(PlanningFixtures.description),
            setOf(PlanningFixtures.itemName),
        )
        assertThrownBy<IllegalArgumentException> {
            QueryDocumentSchema(
                PlanningFixtures.target,
                PlanningFixtures.schema.fields.values.map { field ->
                    if (field.id == PlanningFixtures.itemName) itemName else field
                },
                listOf(crossOwner),
            )
        }

        assertThrownBy<IllegalArgumentException> {
            QueryDocumentSchema(
                PlanningFixtures.target,
                PlanningFixtures.schema.fields.values,
                listOf(
                    PlanningFixtures.schema.searchScopes.values.single(),
                    QuerySearchScopeDefinition(
                        SearchScopeId("duplicate-alias"),
                        null,
                        listOf(PlanningFixtures.description),
                        setOf(PlanningFixtures.description),
                    ),
                ),
            )
        }
    }

    @Test
    fun `search scope should reject duplicate fields`() {
        assertThrownBy<IllegalArgumentException> {
            QuerySearchScopeDefinition(
                SearchScopeId("duplicate-field"),
                null,
                listOf(PlanningFixtures.description, PlanningFixtures.description),
                setOf(PlanningFixtures.description),
            )
        }
    }

    @Test
    fun `array value validation should preserve element nullability`() {
        val nullable = LogicalFieldType.Array(
            LogicalFieldType.Text,
            Nullability.NULLABLE,
            EmptyArraySemantics.DISTINCT,
        )
        val nonNull = nullable.copy(elementNullability = Nullability.NON_NULL)
        val value = NormalizedValue.ListValue(listOf(NormalizedValue.Text("tag"), NormalizedValue.Null))

        nullable.accepts(value).assert().isTrue()
        nonNull.accepts(value).assert().isFalse()
        LogicalFieldType.Array(nullable, Nullability.NON_NULL, EmptyArraySemantics.DISTINCT)
            .accepts(NormalizedValue.ListValue(listOf(value))).assert().isTrue()
    }

    @Test
    fun `schema should reject object exact and ambiguous canonical path encodings`() {
        assertThrownBy<IllegalArgumentException> {
            QueryFieldSchema(
                PlanningFixtures.state,
                LogicalFieldType.Object,
                Presence.OPTIONAL,
                Nullability.NULLABLE,
                setOf(PredicateOperator.EQ),
                setOf(FieldCapability.EXACT),
            )
        }
        assertThrownBy<IllegalArgumentException> {
            QueryFieldSchema(
                PlanningFixtures.tags,
                LogicalFieldType.Array(
                    LogicalFieldType.Array(
                        LogicalFieldType.Object,
                        Nullability.NON_NULL,
                        EmptyArraySemantics.DISTINCT,
                    ),
                    Nullability.NON_NULL,
                    EmptyArraySemantics.DISTINCT,
                ),
                Presence.OPTIONAL,
                Nullability.NULLABLE,
                setOf(PredicateOperator.EQ),
                setOf(FieldCapability.EXACT),
            )
        }
        assertThrownBy<IllegalArgumentException> {
            QueryFieldId.Path(listOf("a\u0000b"))
        }

        val identity = PlanningFixtures.schema.fields.getValue(PlanningFixtures.identity).let { field ->
            QueryFieldSchema(
                field.id,
                field.type,
                field.presence,
                field.nullability,
                field.allowedOperators,
                field.capabilities,
                setOf(QueryFieldId.Path(listOf("state", "items", "id"))),
            )
        }
        assertThrownBy<IllegalArgumentException> {
            QueryDocumentSchema(
                PlanningFixtures.target,
                PlanningFixtures.schema.fields.values.map { field ->
                    if (field.id == PlanningFixtures.identity) identity else field
                },
                PlanningFixtures.schema.searchScopes.values,
            )
        }
    }

    @Test
    fun `logical field collections should be defensively immutable`() {
        val schema = PlanningFixtures.schema

        assertThrownBy<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (schema.fields as MutableMap<QueryFieldId, QueryFieldSchema>).clear()
        }
        assertThrownBy<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (schema.fields.getValue(PlanningFixtures.name).capabilities as MutableSet<FieldCapability>).clear()
        }
    }

    @Test
    fun `registry should resolve the complete query target and reject duplicates`() {
        val snapshot = PlanningFixtures.schema
        val eventTarget = QueryTarget(snapshot.target.namedAggregate, QueryDocumentKind.EVENT_STREAM)
        val event = QueryDocumentSchema(eventTarget, snapshot.fields.values, snapshot.searchScopes.values)
        val registry = QuerySchemaRegistry(listOf(event, snapshot))

        registry[snapshot.target].assert().isEqualTo(snapshot)
        registry[eventTarget].assert().isEqualTo(event)
        assertThrownBy<IllegalArgumentException> {
            QuerySchemaRegistry(listOf(snapshot, snapshot))
        }
    }
}
