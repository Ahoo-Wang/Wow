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

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.annotation.QueryTemporal
import me.ahoo.wow.api.query.annotation.Sensitive
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.infra.TypeNameMapper.toType
import me.ahoo.wow.infra.reflection.MergedAnnotation.Companion.toMergedAnnotation
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.serialization.JsonSerializer
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.JsonNode
import java.util.concurrent.ConcurrentHashMap

/**
 * Declares the fields a [QueryModelSource] infers from the domain types of a built-in model.
 *
 * The model decides which types to describe: a Snapshot's payload is the aggregate state; an EventStream's payload
 * (`body.body`) is one variant per domain event type, each tagged with its `bodyType`, and `body.bodyType` lists the
 * event types. Field annotations get their query meaning here: [Sensitive] becomes a [MaskRule], [QueryTemporal] and
 * standard date formats become [Temporal] encodings. Declarations are cached per model and type.
 *
 * @param modelSource the type inference that describes each domain type.
 * @param typeResolver the type that owns a model's payload: the state type of a Snapshot, the aggregate type of an
 * EventStream (whose event types are read from the aggregate's metadata).
 */
class InferredQuerySchemaSource(
    private val modelSource: QueryModelSource,
    private val typeResolver: (QuerySchemaContext) -> Class<*> = ::payloadOwnerType,
) : QuerySchemaSource {
    private val declarations = ConcurrentHashMap<Pair<QueryModel, Class<*>>, QuerySchemaDeclaration>()

    override val priority: Int = QuerySchemaSourcePriority.INFERRED

    override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.defer {
        val profile = QueryModelProfile.of(context.model) ?: return@defer Flux.empty()
        val type = typeResolver(context)
        Flux.just(declarations.computeIfAbsent(context.model to type) { infer(profile, type) })
    }.subscribeOn(Schedulers.boundedElastic()).onErrorMap { error ->
        when (error) {
            is QuerySchemaException -> error
            is Exception -> QuerySchemaUnavailableException(
                "Unable to infer JSON query schema [${context.namedAggregate}/${context.model.value}].",
                error,
            )
            else -> error
        }
    }

    private fun infer(profile: QueryModelProfile, type: Class<*>): QuerySchemaDeclaration = when (profile) {
        is SnapshotQueryModelProfile -> QuerySchemaDeclaration(
            mapOf(profile.payloadField to modelSource.describe(type).toDeclaration(profile.payloadField).asPayload()),
        )
        is EventStreamQueryModelProfile -> eventStream(profile, type.aggregateEventTypes())
    }

    private fun eventStream(profile: EventStreamQueryModelProfile, events: List<Class<*>>): QuerySchemaDeclaration {
        if (events.isEmpty()) return QuerySchemaDeclaration(emptyMap())
        val field = profile.payloadField
        val variants = events.map { event ->
            modelSource.describe(event).toDeclaration(field).copy(variant = DeclarationValue.Set(event.name))
        }
        val payload = variants.singleOrNull() ?: QueryFieldDeclaration(
            kind = DeclarationValue.Set(QueryValueKind.UNION),
            alternatives = DeclarationValue.Set(variants.also { requireConsistentMaskRules(it, field) }),
        )
        return QuerySchemaDeclaration(
            mapOf(
                field to payload.asPayload(),
                profile.payloadTypeField to QueryFieldDeclaration(
                    enumValues = DeclarationValue.Set(events.map { JsonSerializer.valueToTree<JsonNode>(it.name) }),
                ),
            ),
        )
    }

    private companion object {
        fun payloadOwnerType(context: QuerySchemaContext): Class<*> {
            val aggregateType = context.namedAggregate.requiredAggregateType<Any>()
            return if (context.model == QueryModel.EVENT_STREAM) {
                aggregateType
            } else {
                aggregateType.aggregateMetadata<Any, Any>().state.aggregateType
            }
        }

        /** Event types the aggregate sources or its metadata declares, ordered by name. */
        fun Class<*>.aggregateEventTypes(): List<Class<*>> {
            val metadata = aggregateMetadata<Any, Any>()
            val declared = MetadataSearcher.getAggregate(metadata.namedAggregate)?.events.orEmpty()
                .map { it.toType<Any>() }
            return (metadata.state.sourcingFunctionRegistry.keys + declared).distinct().sortedBy { it.name }
        }

        /** The model's system declaration owns the payload's presence; inference only describes its shape. */
        fun QueryFieldDeclaration.asPayload(): QueryFieldDeclaration =
            copy(nullable = DeclarationValue.Unset, required = DeclarationValue.Unset)
    }
}

/**
 * The query meaning of a [QueryTypeFact] at [field]: its structure as a declaration, with properties that are not
 * valid query path segments left out, standard date formats as [Temporal.Date], and member annotations applied.
 *
 * @throws QuerySchemaConflictException when an annotation cannot apply to the value, or a sensitive member would be
 * lost (behind an invalid property name, or among members the source did not expand).
 */
fun QueryTypeFact.toDeclaration(field: QueryField): QueryFieldDeclaration {
    if (omitted.any { it.sensitive() != null }) {
        throw QuerySchemaConflictException("Query schema field cannot hide masked descendants: [$field].")
    }
    val branches = alternatives.map { it.toDeclaration(field) }
    if (kind == QueryValueKind.UNION) requireConsistentMaskRules(branches, field)
    val declaration = QueryFieldDeclaration(
        title = DeclarationValue.Set(title),
        description = DeclarationValue.Set(description),
        enumValues = DeclarationValue.Set(enumValues),
        valueTypes = DeclarationValue.Set(valueTypes),
        nullable = nullable.known(),
        required = required.known(),
        kind = DeclarationValue.Set(kind),
        properties = queryProperties(field).takeIf { kind == QueryValueKind.OBJECT }.known(),
        items = items?.toDeclaration(QueryField("${field.path}.__items")).known(),
        additionalProperties = additionalProperties?.toDeclaration(QueryField("${field.path}.__key")).known(),
        alternatives = if (kind == QueryValueKind.UNION) DeclarationValue.Set(branches) else DeclarationValue.Unset,
        semanticType = DeclarationValue.Set(Temporal.Date.takeIf { formats.any(DATE_FORMATS::contains) }),
    )
    return member?.let { declaration.withMember(it, field) } ?: declaration
}

private fun <T : Any> T?.known(): DeclarationValue<T> = this?.let { DeclarationValue.Set(it) } ?: DeclarationValue.Unset

/** Properties that can be queried; one that cannot must not carry a sensitive member, or its protection is lost. */
private fun QueryTypeFact.queryProperties(field: QueryField): Map<String, QueryFieldDeclaration> =
    properties.mapNotNull { (name, child) ->
        when {
            name.isQueryPathSegment() -> name to child.toDeclaration(QueryField("${field.path}.$name"))
            child.hasSensitiveMembers() -> throw QuerySchemaConflictException(
                "Masked query schema property is not a valid QueryField: [$field[\"$name\"]]."
            )
            else -> null
        }
    }.toMap()

private val DATE_FORMATS = setOf("date", "date-time")

private fun String.isQueryPathSegment(): Boolean = '.' !in this && runCatching { QueryField(this) }.isSuccess

/** The annotations a member declares, each with the annotations its own class carries (meta-annotations). */
private fun QueryMemberFact.effectiveAnnotations(): List<Annotation> = annotations.flatMap { annotation ->
    listOf(annotation) + annotation.annotationClass.toMergedAnnotation().mergedAnnotations
}.distinct()

private fun QueryMemberFact.sensitive(): Sensitive? {
    val sensitive = effectiveAnnotations().filterIsInstance<Sensitive>().distinct()
    if (sensitive.size > 1) {
        throw QuerySchemaConflictException("Multiple effective @Sensitive annotations are not allowed.")
    }
    return sensitive.singleOrNull()
}

private fun QueryTypeFact.hasSensitiveMembers(): Boolean =
    member?.sensitive() != null || omitted.any { it.sensitive() != null } ||
        properties.values.any { it.hasSensitiveMembers() } || items?.hasSensitiveMembers() == true ||
        additionalProperties?.hasSensitiveMembers() == true || alternatives.any { it.hasSensitiveMembers() }

private fun QueryFieldDeclaration.withMember(member: QueryMemberFact, field: QueryField): QueryFieldDeclaration {
    val temporal = member.annotations.filterIsInstance<QueryTemporal>().distinct()
    if (temporal.size > 1) {
        throw QuerySchemaConflictException("Multiple @QueryTemporal annotations are not allowed.")
    }
    val timed = temporal.singleOrNull()?.let { withTemporal(it) } ?: this
    return member.sensitive()?.let { timed.withSensitive(it, member, field) } ?: timed
}

private fun QueryFieldDeclaration.withSensitive(
    sensitive: Sensitive,
    member: QueryMemberFact,
    field: QueryField,
): QueryFieldDeclaration {
    if (member.type != String::class.java) {
        throw QuerySchemaConflictException("Sensitive query schema member [${member.name}] must have String JVM type.")
    }
    if (!isMaskStringDomain()) {
        throw QuerySchemaConflictException("Masked query schema field must have STRING value type.")
    }
    val rule = MaskRule.of(sensitive)
    return copy(maskRule = maskRule.mergeRule(rule, field))
}

private fun DeclarationValue<MaskRule>.mergeRule(rule: MaskRule, field: QueryField): DeclarationValue<MaskRule> {
    val existing = valueOr(null)
    if (existing != null && existing != rule) {
        throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.maskRule].")
    }
    return DeclarationValue.Set(rule)
}

private fun QueryFieldDeclaration.withTemporal(annotation: QueryTemporal): QueryFieldDeclaration {
    if (annotation.pattern.isEmpty()) return withTemporal(Temporal.Epoch(annotation.unit), QueryValueType.INTEGER)
    if (annotation.unit != QueryTemporal().unit) {
        throw QuerySchemaConflictException("@QueryTemporal declares either a unit or a pattern, not both.")
    }
    val formatted = try {
        Temporal.Formatted(annotation.pattern)
    } catch (error: IllegalArgumentException) {
        throw QuerySchemaConflictException("Invalid @QueryTemporal pattern [${annotation.pattern}].", error)
    }
    return withTemporal(formatted, QueryValueType.STRING)
}

/** Applies a declared time encoding to every non-null leaf, which must have the [wire] JSON type. */
private fun QueryFieldDeclaration.withTemporal(temporal: Temporal, wire: QueryValueType): QueryFieldDeclaration =
    when (inferredKind()) {
        QueryValueKind.ARRAY -> copy(
            items = DeclarationValue.Set(checkNotNull(items.valueOr(null)).withTemporal(temporal, wire))
        )
        QueryValueKind.UNION -> copy(
            alternatives = DeclarationValue.Set(
                alternatives.valueOr(emptyList()).map { branch ->
                    if (branch.inferredKind() == QueryValueKind.NULL) branch else branch.withTemporal(temporal, wire)
                }
            )
        )
        QueryValueKind.SCALAR -> {
            if (valueTypes.valueOr(emptySet()) != setOf(wire)) {
                throw QuerySchemaConflictException("@QueryTemporal requires ${temporal.wireShape()} JSON wire shape.")
            }
            val previous = semanticType.valueOr(null)
            if (previous != null && previous != temporal) {
                throw QuerySchemaConflictException("Conflicting query schema temporal encoding.")
            }
            copy(semanticType = DeclarationValue.Set(temporal))
        }
        else -> throw QuerySchemaConflictException("@QueryTemporal requires ${temporal.wireShape()} JSON wire shape.")
    }

private fun Temporal.wireShape(): String = if (this is Temporal.Formatted) "a string" else "an integer"

/** Alternatives of one value must not mask the same path differently: a response value takes one rule. */
private fun requireConsistentMaskRules(alternatives: List<QueryFieldDeclaration>, field: QueryField) {
    val rules = mutableMapOf<List<String>, MaskRule>()
    fun collect(value: QueryFieldDeclaration, path: List<String>) {
        value.maskRule.valueOr(null)?.let { rule ->
            if (rules.putIfAbsent(path, rule)?.let { it != rule } == true) {
                throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.maskRule].")
            }
        }
        value.properties.valueOr(emptyMap()).forEach { (name, child) -> collect(child, path + name) }
        value.items.valueOr(null)?.let { collect(it, path + "[]") }
        value.additionalProperties.valueOr(null)?.let { collect(it, path + "{}") }
        value.alternatives.valueOr(emptyList()).forEach { collect(it, path) }
    }
    alternatives.forEach { collect(it, emptyList()) }
}
