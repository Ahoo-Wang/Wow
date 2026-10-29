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

package me.ahoo.wow.query.schema

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.schema.ManifestFixtures.ITEM_STATE_DEFS
import me.ahoo.wow.query.schema.ManifestFixtures.ORDER
import me.ahoo.wow.query.schema.ManifestFixtures.REDACT
import me.ahoo.wow.query.schema.ManifestFixtures.STRATEGIES
import me.ahoo.wow.query.schema.ManifestFixtures.manifest
import me.ahoo.wow.query.schema.ManifestFixtures.orderSchema
import me.ahoo.wow.query.schema.ManifestFixtures.schemaOf
import me.ahoo.wow.query.schema.ManifestFixtures.stateManifest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

class ManifestQuerySchemaSourceTest {
    private fun QueryModelSchema.value(path: String): QueryValueSchema = checkNotNull(field(QueryField(path))) {
        "No field [$path]."
    }.value

    @Test
    fun `the state schema and the system fields make the snapshot model`() {
        runTest {
            val schema = orderSchema()
            schema.model.assert().isEqualTo(QueryModel.SNAPSHOT)
            listOf("tenantId", "aggregateId", "version", "ownerId", "spaceId", "deleted", "tags").forEach {
                schema.field(QueryField(it)).assert().isNotNull()
            }
            schema.value("state").kind.assert().isEqualTo(QueryValueKind.OBJECT)
            schema.value("state").nullable.assert().isFalse()
            schema.value("state.address.city").valueTypes.assert().containsExactly(QueryValueType.STRING)
            schema.value("state.address").required.assert().isTrue()
            schema.value("state.shipping").nullable.assert().isTrue()
            schema.value("state.shipping").required.assert().isFalse()
            schema.value("state.placedAt").semanticType.assert().isEqualTo(Temporal.Date)
            schema.value("state.lines").kind.assert().isEqualTo(QueryValueKind.ARRAY)
            schema.value("state.lines.sku").valueTypes.assert().containsExactly(QueryValueType.STRING)
        }
    }

    @Test
    fun `x-wow-temporal maps to an epoch or a formatted temporal`() {
        runTest {
            val schema = orderSchema()
            schema.value("state.createdAt").semanticType.assert().isEqualTo(Temporal.Epoch(TimeUnit.SECONDS))
            schema.value("state.shippedAt").semanticType.assert().isEqualTo(Temporal.Formatted("yyyy-MM-dd HH:mm:ss"))
            schema.value("state.shippedAt").nullable.assert().isTrue()
        }
    }

    @Test
    fun `x-wow-temporal holds on a nullable time in list elements and map values alike`() {
        runTest {
            // A map value is addressed by a concrete key; the descriptor lists the key's fields as `{key}` patterns.
            val items = mapOf(
                "ItemListState" to listOf("state.items"),
                "ItemMapState" to listOf("state.itemsById.k1"),
                "ItemListAndMapState" to listOf("state.items", "state.itemsById.k1"),
                "TwoItemListsState" to listOf("state.first", "state.second"),
            )
            ITEM_STATE_DEFS.keys.map { it.substringAfterLast('$') }.assert()
                .containsExactlyInAnyOrderElementsOf(items.keys)
            ITEM_STATE_DEFS.forEach { (state, defs) ->
                val schema = schemaOf(ManifestQuerySchemaSource(manifest(defs, state)))
                val descriptor = schema.describe(QueryBudget.HTTP_DEFAULT, defaultListSize = null)
                items.getValue(state.substringAfterLast('$')).forEach { item ->
                    schema.value("$item.endDate").run {
                        kind.assert().isEqualTo(QueryValueKind.SCALAR)
                        valueTypes.assert().containsExactly(QueryValueType.INTEGER)
                        nullable.assert().isTrue()
                        semanticType.assert().isEqualTo(Temporal.Epoch(TimeUnit.MILLISECONDS))
                    }
                    schema.value("$item.startDate").run {
                        nullable.assert().isFalse()
                        semanticType.assert().isEqualTo(Temporal.Epoch(TimeUnit.MILLISECONDS))
                    }
                    schema.value("$item.type").enumValues?.map { it.stringValue() }.assert().containsExactly("A", "B")
                    listOf("endDate", "startDate").forEach { time ->
                        // A list element's field is reached through its element scope, a map value's directly.
                        val scope = schema.field(QueryField("$item.$time"))?.elementAncestors?.lastOrNull()?.path
                        val field = QueryField(if (scope == null) "$item.$time" else time)
                        listOf(TodayFilter(field), RecentDaysFilter(field, 7)).forEach { predicate ->
                            val filter = scope?.let { ElementMatchFilter(QueryField(it), predicate) } ?: predicate
                            // Admitted, or admission throws.
                            QueryAdmission.Trusted.list(ListQuery(filter, limit = 1), schema)
                        }
                        val path = "$item.$time".replace(".k1.", ".{key}.")
                        val operators = descriptor.fields.singleOrNull { it.path == path }?.filter?.operators
                            ?: descriptor.dynamic.single { it.pattern == path }.filter.operators
                        operators.assert().contains(FilterOperator.TODAY, FilterOperator.RECENT_DAYS)
                    }
                }
            }
        }
    }

    @Test
    fun `x-wow-decimal, x-wow-money and x-wow-duration map to numeric formats and time spans`() {
        runTest {
            val schema = orderSchema()
            schema.value("state.rate").semanticType.assert().isEqualTo(NumericFormat.Decimal(4))
            schema.value("state.lines.quantity").semanticType.assert().isEqualTo(NumericFormat.Decimal(3))
            schema.value("state.paid").semanticType.assert()
                .isEqualTo(NumericFormat.Money(currencyField = "currency", scale = 2))
            schema.value("state.tip").semanticType.assert().isEqualTo(NumericFormat.Money(currency = "JPY", scale = 1))
            // A fixed currency without a scale takes its standard fraction digits.
            schema.value(
                "state.total"
            ).semanticType.assert().isEqualTo(NumericFormat.Money(currency = "CNY", scale = 2))
            schema.value("state.timeout").semanticType.assert().isEqualTo(TimeSpan(TimeUnit.SECONDS))
        }
    }

    @Test
    fun `x-wow-reference names the declaring context when it names none`() {
        runTest {
            val schema = orderSchema()
            schema.value("state.buyerId").semanticType.assert()
                .isEqualTo(Reference(contextName = "shop", aggregateName = "member"))
            // On an array, every item refers.
            schema.value("state.productIds").items?.semanticType.assert()
                .isEqualTo(Reference(contextName = "catalog", aggregateName = "product"))
        }
    }

    @Test
    fun `x-wow-alias names the canonical field`() {
        runTest {
            val definition = orderSchema().definition
            definition.canonical(QueryField("state.customer")).assert().isEqualTo(QueryField("state.buyer"))
            definition.canonical(QueryField("state.client")).assert().isEqualTo(QueryField("state.buyer"))
            definition.canonical(QueryField("state.remark")).assert().isEqualTo(QueryField("state.note"))
            definition.canonical(
                QueryField("state.deliverTo.city")
            ).assert().isEqualTo(QueryField("state.shipping.city"))
        }
    }

    @Test
    fun `x-wow-sensitive and x-wow-mask map to the mask rule of each level`() {
        runTest {
            val schema = orderSchema()
            schema.value("state.phone").maskRule.assert()
                .isEqualTo(MaskRule(SensitivityLevel.DISPLAY, QueryMask.Keep(3, 4)))
            schema.value("state.password").maskRule.assert().isEqualTo(MaskRule(SensitivityLevel.CONFIDENTIAL))
            schema.value("state.address.street").maskRule?.level.assert().isEqualTo(SensitivityLevel.CONFIDENTIAL)
            val email = checkNotNull(schema.value("state.email").maskRule)
            email.mask.assert().isEqualTo(QueryMask.Strategy(REDACT))
            email.compiled.mask("a@b.c").assert().isEqualTo("[redacted]")
            schema.maskedFields.assert().contains(
                "state.address.street",
                "state.backupPhone",
                "state.email",
                "state.password",
                "state.phone",
                "state.token",
            )
            schema.field(QueryField("state.phone"))?.protection.assert().isEqualTo(SensitivityLevel.DISPLAY)
            schema.field(QueryField("state.password"))?.protection.assert().isEqualTo(SensitivityLevel.CONFIDENTIAL)
            schema.field(QueryField("state.password"))?.comparable.assert().isFalse()
            schema.field(QueryField("state.phone"))?.comparable.assert().isTrue()
            schema.hasIncomparableFields.assert().isTrue()
            // The shipping address is the same Address type, so its street is protected too.
            schema.field(
                QueryField("state.shipping.street")
            )?.protection.assert().isEqualTo(SensitivityLevel.CONFIDENTIAL)
        }
    }

    @Test
    fun `an unregistered mask strategy fails the schema build`() {
        runTest {
            assertThrows<QuerySchemaConflictException> { schemaOf(ManifestQuerySchemaSource(manifest())) }
        }
    }

    @Test
    fun `the source is keyed by aggregate and serves only the snapshot model`() {
        runTest {
            val source = ManifestQuerySchemaSource(manifest(), STRATEGIES)
            source.priority.assert().isEqualTo(QuerySchemaSourcePriority.MANIFEST)
            source.load(QuerySchemaContext(ORDER, QueryModel.SNAPSHOT)).single().fields.keys
                .assert().containsExactly(QueryField("state"))
            source.load(QuerySchemaContext(ORDER, QueryModel.DATASET)).assert().isEmpty()
            source.load(QuerySchemaContext(AggregateType("shop", "cart"), QueryModel.SNAPSHOT)).assert().isEmpty()
            source.load(QuerySchemaContext(AggregateType("other", "order"), QueryModel.SNAPSHOT)).assert().isEmpty()
            ManifestQuerySchemaSource(manifest(snapshots = false), STRATEGIES)
                .load(QuerySchemaContext(ORDER, QueryModel.SNAPSHOT)).assert().isEmpty()
            // Loaded once per aggregate.
            source.load(QuerySchemaContext(ORDER, QueryModel.SNAPSHOT)).assert()
                .isSameAs(source.load(QuerySchemaContext(ORDER, QueryModel.SNAPSHOT)))
        }
    }

    @Test
    fun `x-wow-index on the state root is informational`() {
        runTest {
            val manifest = manifest(
                defs = mapOf(
                    "State" to mapOf(
                        "type" to "object",
                        "properties" to mapOf("status" to mapOf("type" to "string")),
                        ManifestQueryKeywords.INDEX to listOf(mapOf("name" to "ix", "keys" to listOf("state.status"))),
                    ),
                ),
                state = "State",
            )
            val schema = schemaOf(ManifestQuerySchemaSource(manifest))
            schema.value("state.status").valueTypes.assert().containsExactly(QueryValueType.STRING)
        }
    }

    private fun structureManifest() = stateManifest(
        mapOf(
            "status" to mapOf(
                "type" to "string",
                "enum" to listOf("NEW", "PAID"),
                "title" to "Status",
                "description" to "The order status",
            ),
            "legacy" to mapOf("type" to "string", "deprecated" to true),
            "secret" to mapOf("type" to "string", "writeOnly" to true),
            "odd name" to mapOf("type" to "string"),
            "amount" to mapOf("type" to listOf("integer", "number", "null")),
            "either" to mapOf("type" to listOf("string", "object")),
            "oneOf" to mapOf("oneOf" to listOf(mapOf("type" to "string"), mapOf("type" to "integer"))),
            "attributes" to mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string")),
            "anything" to mapOf("type" to "object", "additionalProperties" to true),
            "untyped" to mapOf("properties" to mapOf("a" to mapOf("type" to "boolean"))),
            "list" to mapOf("items" to mapOf("type" to "string")),
            "opaque" to emptyMap<String, Any?>(),
            "merged" to mapOf(
                "allOf" to listOf(
                    mapOf("type" to "object", "properties" to mapOf("a" to mapOf("type" to "string"))),
                    mapOf("type" to "object", "properties" to mapOf("b" to mapOf("type" to "integer"))),
                ),
            ),
            "node" to mapOf("\$ref" to "#/\$defs/Node"),
            "nothing" to mapOf("type" to "null"),
        ),
        extra = mapOf(
            "Node" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "next" to mapOf("\$ref" to "#/\$defs/Node"),
                ),
            ),
        ),
    )

    @Test
    fun `structure keywords read as their query values`() {
        runTest {
            val schema = schemaOf(ManifestQuerySchemaSource(structureManifest()))
            schema.value("state.status").run {
                enumValues?.map { it.stringValue() }.assert().containsExactly("NEW", "PAID")
                title.assert().isEqualTo("Status")
                description.assert().isEqualTo("The order status")
            }
            schema.value("state.legacy").deprecated.assert().isEqualTo(QueryDeprecation())
            schema.field(QueryField("state.secret")).assert().isNull()
        }
    }

    @Test
    fun `type lists, compositions, maps and references read as their query values`() {
        runTest {
            val schema = schemaOf(ManifestQuerySchemaSource(structureManifest()))
            schema.value("state.amount").run {
                valueTypes.assert().containsExactly(QueryValueType.INTEGER, QueryValueType.DECIMAL)
                nullable.assert().isTrue()
            }
            schema.value("state.either").kind.assert().isEqualTo(QueryValueKind.UNION)
            schema.value("state.oneOf").kind.assert().isEqualTo(QueryValueKind.UNION)
            schema.value("state.attributes").additionalProperties?.valueTypes.assert()
                .isEqualTo(setOf(QueryValueType.STRING))
            schema.value("state.anything").additionalProperties?.kind.assert().isEqualTo(QueryValueKind.UNKNOWN)
            schema.value("state.untyped.a").valueTypes.assert().containsExactly(QueryValueType.BOOLEAN)
            schema.value("state.list").items?.valueTypes.assert().isEqualTo(setOf(QueryValueType.STRING))
            schema.value("state.opaque").kind.assert().isEqualTo(QueryValueKind.UNKNOWN)
            schema.value("state.merged.a").valueTypes.assert().containsExactly(QueryValueType.STRING)
            schema.value("state.merged.b").valueTypes.assert().containsExactly(QueryValueType.INTEGER)
            schema.value("state.node.name").valueTypes.assert().containsExactly(QueryValueType.STRING)
            // A recursive reference is not expanded again.
            schema.value("state.node.next").kind.assert().isEqualTo(QueryValueKind.UNKNOWN)
            schema.value("state.nothing").kind.assert().isEqualTo(QueryValueKind.NULL)
        }
    }

    @Test
    fun `sensitivity reaches map values and union branches`() {
        runTest {
            val sensitive = mapOf("level" to "display")
            val manifest = stateManifest(
                mapOf(
                    "contacts" to mapOf(
                        "type" to "object",
                        "additionalProperties" to mapOf("type" to "string"),
                        ManifestQueryKeywords.SENSITIVE to sensitive,
                    ),
                    "phones" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "string"),
                        ManifestQueryKeywords.SENSITIVE to sensitive,
                    ),
                    "code" to mapOf(
                        "anyOf" to listOf(mapOf("type" to "string"), mapOf("type" to "array", "items" to mapOf("type" to "string"))),
                        ManifestQueryKeywords.SENSITIVE to sensitive,
                        ManifestQueryKeywords.MASK to mapOf("keepPrefix" to 1),
                    ),
                ),
            )
            val schema = schemaOf(ManifestQuerySchemaSource(manifest))
            schema.value(
                "state.contacts"
            ).additionalProperties?.maskRule?.level.assert().isEqualTo(SensitivityLevel.DISPLAY)
            schema.value("state.phones").maskRule?.level.assert().isEqualTo(SensitivityLevel.DISPLAY)
            schema.value("state.code").maskRule?.mask.assert().isEqualTo(QueryMask.Keep(1, 0))
            schema.maskedFields.assert().contains("state.contacts.{key}", "state.phones", "state.code")
        }
    }

    @Test
    fun `malformed or misplaced keywords fail the schema build`() {
        runTest {
            val invalid = listOf(
                mapOf("type" to "integer", ManifestQueryKeywords.SENSITIVE to mapOf("level" to "display")),
                mapOf("type" to "string", ManifestQueryKeywords.SENSITIVE to mapOf("level" to "SECRET")),
                mapOf("type" to "string", ManifestQueryKeywords.SENSITIVE to "display"),
                mapOf("type" to "string", ManifestQueryKeywords.MASK to mapOf("keepPrefix" to 1)),
                mapOf(
                    "type" to "string",
                    ManifestQueryKeywords.SENSITIVE to mapOf("level" to "display"),
                    ManifestQueryKeywords.MASK to mapOf("keepPrefix" to -1),
                ),
                mapOf(
                    "type" to "string",
                    ManifestQueryKeywords.SENSITIVE to mapOf("level" to "display"),
                    ManifestQueryKeywords.MASK to mapOf("strategy" to REDACT, "keepPrefix" to 1),
                ),
                mapOf("type" to "string", ManifestQueryKeywords.TEMPORAL to mapOf("unit" to "SECONDS")),
                mapOf("type" to "integer", ManifestQueryKeywords.TEMPORAL to mapOf("pattern" to "yyyy")),
                mapOf(
                    "type" to "integer",
                    ManifestQueryKeywords.TEMPORAL to mapOf("unit" to "SECONDS", "pattern" to "yyyy")
                ),
                mapOf("type" to "object", ManifestQueryKeywords.TEMPORAL to mapOf("unit" to "SECONDS")),
                mapOf("type" to "number", ManifestQueryKeywords.DECIMAL to mapOf("scale" to 99)),
                mapOf("type" to "number", ManifestQueryKeywords.DECIMAL to emptyMap<String, Any?>()),
                mapOf("type" to "number", ManifestQueryKeywords.MONEY to mapOf("currency" to "XYZ")),
                mapOf("type" to "number", ManifestQueryKeywords.MONEY to mapOf("currencyField" to "currency")),
                mapOf("type" to "integer", ManifestQueryKeywords.DURATION to mapOf("unit" to "AGES")),
                mapOf("type" to "object", ManifestQueryKeywords.REFERENCE to mapOf("aggregateName" to "member")),
                mapOf("type" to "string", ManifestQueryKeywords.REFERENCE to emptyMap<String, Any?>()),
                mapOf("type" to "string", ManifestQueryKeywords.ALIAS to listOf("not a path")),
                mapOf(
                    "type" to "number",
                    ManifestQueryKeywords.DECIMAL to mapOf("scale" to 2),
                    ManifestQueryKeywords.DURATION to mapOf("unit" to "SECONDS"),
                ),
                mapOf("type" to "string", "x" to 1, "\$ref" to "https://example.com/schema"),
                mapOf("\$ref" to "#/\$defs/Missing"),
                mapOf("type" to 1),
                mapOf("type" to "date"),
            )
            invalid.forEach { property ->
                assertThrows<QuerySchemaConflictException>("$property") {
                    schemaOf(ManifestQuerySchemaSource(stateManifest(mapOf("value" to property))))
                }
            }
        }
    }

    @Test
    fun `a sensitive property that is not a query path fails instead of dropping its protection`() {
        runTest {
            val manifest = stateManifest(
                mapOf(
                    "bad name" to mapOf("\$ref" to "#/\$defs/Secret"),
                ),
                extra = mapOf(
                    "Secret" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "value" to mapOf(
                                "type" to "string",
                                ManifestQueryKeywords.SENSITIVE to mapOf("level" to "confidential"),
                            ),
                        ),
                    ),
                ),
            )
            assertThrows<QuerySchemaConflictException> { schemaOf(ManifestQuerySchemaSource(manifest)) }
        }
    }

    @Test
    fun `alternatives may not mask one path differently`() {
        runTest {
            fun masked(level: String) = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "value" to mapOf("type" to "string", ManifestQueryKeywords.SENSITIVE to mapOf("level" to level)),
                ),
            )
            val manifest = stateManifest(
                mapOf(
                    "value" to mapOf("anyOf" to listOf(masked("display"), masked("confidential"), mapOf("type" to "string")))
                ),
            )
            assertThrows<QuerySchemaConflictException> { schemaOf(ManifestQuerySchemaSource(manifest)) }
        }
    }
}
