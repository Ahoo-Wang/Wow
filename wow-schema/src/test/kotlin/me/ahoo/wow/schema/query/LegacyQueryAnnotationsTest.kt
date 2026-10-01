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

@file:Suppress("DEPRECATION")

package me.ahoo.wow.schema.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.mask.CompiledMask
import me.ahoo.wow.api.query.mask.KeepMask
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.mask.MaskStrategy
import me.ahoo.wow.api.query.mask.Masking
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryTemporal
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.InferredQuerySchemaSource
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.tck.query.NoOpSnapshotQueryBackend
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.TimeUnit

/**
 * A domain class compiled against 9.1 names the 9.1 annotations; the JVM would drop them silently if their classes
 * were gone, serving the fields unmasked. These fixtures are compiled exactly as such a class: against the same
 * annotation classes.
 */
class LegacyQueryAnnotationsTest {
    private val context = QuerySchemaContext(
        MaterializedNamedAggregate("legacy-context", "legacy-aggregate"),
        QueryModel.SNAPSHOT,
    )

    @Test
    fun `9_1 mask annotations still mask as DISPLAY`() {
        val declaration = load(LegacyMaskedState::class.java)

        declaration.rule("state.password").let { rule ->
            rule.assert().isEqualTo(MaskRule(SensitivityLevel.DISPLAY))
            rule.compiled.mask("secret").assert().isEqualTo("******")
        }
        declaration.rule("state.phone").let { rule ->
            rule.level.assert().isEqualTo(SensitivityLevel.DISPLAY)
            rule.compiled.mask("13800138000").assert().isEqualTo("138****8000")
        }
        declaration.rule("state.composed").compiled.mask("abc").assert().isEqualTo("***")
        declaration.rule("state.redacted").let { rule ->
            rule.level.assert().isEqualTo(SensitivityLevel.DISPLAY)
            rule.compiled.mask("anything").assert().isEqualTo("[gone]")
        }
        declaration.field("state.plain").maskRule.assert().isEqualTo(DeclarationValue.Unset)
    }

    @Test
    fun `9_1 mask annotations on Kotlin and Java getters still mask`() {
        listOf(LegacyGetterMaskedState::class.java, JavaLegacyMaskedState::class.java).forEach { type ->
            val declaration = load(type)
            declaration.rule("state.password").assert().isEqualTo(MaskRule(SensitivityLevel.DISPLAY))
            declaration.rule("state.phone").let { rule ->
                rule.level.assert().isEqualTo(SensitivityLevel.DISPLAY)
                rule.compiled.mask("13800138000").assert().isEqualTo("138****8000")
            }
        }
    }

    @Test
    fun `a query result is masked where a 9_1 annotation marks the field`() {
        val provider = DefaultQueryModelSchemaProvider(
            context,
            listOf(InferredQuerySchemaSource(JsonQueryModelSource()) { LegacyMaskedState::class.java }),
            // Every field matches exactly, as the gateway's default deletion scope filters on `deleted`.
            QueryStorageAdapter { logicalSchema ->
                Mono.just(
                    QueryStorageFacts(
                        logicalSchema.values.keys.filter { it.segments.isNotEmpty() }.associateWith { path ->
                            QueryValueBindings(
                                mapOf(QueryCapability.EXACT_MATCH to QueryFieldBindingTemplate(path, null)),
                                path,
                                path,
                            )
                        },
                    ),
                )
            },
        )
        val backend = object : SnapshotQueryBackend by NoOpSnapshotQueryBackend(context.namedAggregate) {
            override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> = Flux.just(
                """
                {"contextName":"legacy-context","aggregateName":"legacy-aggregate","tenantId":"tenant",
                 "ownerId":"_default_","spaceId":"_default_","aggregateId":"aggregate","version":1,
                 "eventId":"event","firstOperator":"operator","operator":"operator","firstEventTime":1,
                 "eventTime":1,"snapshotTime":1,"tags":{},"deleted":false,
                 "state":{"password":"secret","phone":"13800138000","composed":"abc","redacted":"x",
                          "plain":"visible"}}
                """.toJsonNode<ObjectNode>(),
            )
        }
        val gateway = DefaultSnapshotQueryGateway<LegacyMaskedState>(
            namedAggregate = context.namedAggregate,
            backend = backend,
            schemaProvider = provider,
            targetType = JsonSerializer.typeFactory.constructParametricType(
                MaterializedSnapshot::class.java,
                LegacyMaskedState::class.java,
            ),
        )

        val state = gateway.dynamicList(listQuery { }).single().block()!!.path("state")
        state.path("password").stringValue().assert().isEqualTo("******")
        state.path("phone").stringValue().assert().isEqualTo("138****8000")
        state.path("composed").stringValue().assert().isEqualTo("***")
        state.path("redacted").stringValue().assert().isEqualTo("[gone]")
        state.path("plain").stringValue().assert().isEqualTo("visible")
        gateway.list(listQuery { }).single().block()!!.state.password.assert().isEqualTo("******")
    }

    @Test
    fun `9_1 temporal annotation still declares an epoch unit`() {
        load(LegacyTemporalState::class.java).field("state.createdAt").semanticType.assert()
            .isEqualTo(DeclarationValue.Set(Temporal.Epoch(TimeUnit.SECONDS)))
    }

    private fun load(type: Class<*>): QuerySchemaDeclaration =
        InferredQuerySchemaSource(JsonQueryModelSource()) { type }.load(context).single().block()!!

    /** The declaration at [name], under the root field that is its longest prefix. */
    private fun QuerySchemaDeclaration.field(name: String): QueryFieldDeclaration {
        val root = fields.entries.filter { name == it.key.path || name.startsWith("${it.key.path}.") }
            .maxBy { it.key.path.length }
        return name.removePrefix(root.key.path).split('.').filter { it.isNotEmpty() }
            .fold(root.value) { value, segment -> (value.properties as DeclarationValue.Set).value!!.getValue(segment) }
    }

    private fun QuerySchemaDeclaration.rule(name: String): MaskRule =
        (field(name).maskRule as DeclarationValue.Set).value!!
}

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@Masking(RedactStrategy::class)
annotation class Redact(val replacement: String)

object RedactStrategy : MaskStrategy<Redact> {
    override fun compile(annotation: Redact): CompiledMask = CompiledMask { value ->
        if (value.isEmpty()) value else annotation.replacement
    }
}

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@Mask
annotation class ComposedLegacySecret

internal data class LegacyMaskedState(
    @field:Mask
    val password: String,
    @field:KeepMask(prefix = 3, suffix = 4)
    val phone: String,
    @field:ComposedLegacySecret
    val composed: String,
    @field:Redact("[gone]")
    val redacted: String,
    val plain: String,
)

internal data class LegacyGetterMaskedState(
    @get:Mask
    val password: String,
    @get:KeepMask(prefix = 3, suffix = 4)
    val phone: String,
)

internal data class LegacyTemporalState(
    @field:QueryTemporal(TimeUnit.SECONDS)
    val createdAt: Long,
)
