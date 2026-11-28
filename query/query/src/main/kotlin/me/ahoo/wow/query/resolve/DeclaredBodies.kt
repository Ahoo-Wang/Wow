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

package me.ahoo.wow.query.resolve

import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FieldPredicate
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.fields
import me.ahoo.wow.query.resolve.QueryResolver.Companion.normalizer
import me.ahoo.wow.query.resolve.QueryResolver.Scope
import me.ahoo.wow.query.schema.EventBodyDeclarations
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.requireValid
import me.ahoo.wow.query.schema.toPathTemplate
import me.ahoo.wow.query.spec.matchesAbsentField
import me.ahoo.wow.spi.json.query.EventFields

/**
 * The `events` model's rule for the paths of an event's body, in its `events` element [scope]: a body path means that
 * path in the events that declare it, at a revision this node knows ([EventBodyDeclarations]), so nothing of an event
 * the Manifest does not know, of one at a newer revision than it knows, or of an older revision of an event that no
 * longer declares the path is compared, grouped, expanded or counted by its body. Admission applies it where it
 * resolves the scope ([QueryResolver.storedEvents], [AggregationResolution]); every backend runs what it yields as it
 * runs any element predicate: a condition on the event's `name` and `revision` beside the condition on its body.
 *
 * The guard of a path is `name in (the names its events are stored under) and revision <= (their revision)`, one such
 * pair for each revision they are at: one pair for every path a comparison, a group or a metric reads, whose events
 * are all at revision 1 ([me.ahoo.wow.query.schema.EventsQuerySchemas]). A path no event declares is refused.
 */
internal class DeclaredBodies(
    private val resolver: QueryResolver,
    private val bodies: EventBodyDeclarations,
    private val scope: Scope,
) {
    /**
     * [filter], resolved in [scope], with each condition on a body path held to the events that declare it: one that
     * does not match an absent field ([matchesAbsentField]) holds only on them, and one that does (`ne`, `not-in`,
     * `is-null`, `not-exists`) holds on every other event as well, as on a body without the path. Either way it answers,
     * on any event, what it answers on the body a masked read shows of that event, which for an event the node does not
     * know is `null`. An element match on a body array is a condition on that array; its own predicate is in its own
     * scope. A search reads its fields on the events that declare any of them.
     */
    fun filter(filter: FilterExpression): FilterExpression = when (filter) {
        is AndFilter -> AndFilter(filter.operands.map(::filter))
        is OrFilter -> OrFilter(filter.operands.map(::filter))
        is NorFilter -> NorFilter(filter.operands.map(::filter))
        is SearchFilter -> searched(filter)
        is ExpressionFilter -> guard(filter.expression.fields)?.let { and(it, filter) } ?: filter
        is FieldPredicate -> predicate(filter)
        else -> filter
    }

    /**
     * What an element of [scope] must be for every body path of [fields] to hold a value on it: an event that
     * declares each of them; `null` when they read none. A group's keys, a metric's inputs and an expansion below a body
     * array read their paths so.
     */
    fun guard(fields: Collection<QueryField<*>>): FilterExpression? = fields.mapNotNull(::declaring)
        .takeIf { it.isNotEmpty() }
        ?.let { declarations -> normalizer.simplifyAnd(declarations.map(::declared)) }

    /** [filter], one field's condition, as [filter] holds it: by its polarity on an absent field. */
    private fun predicate(filter: FieldPredicate): FilterExpression {
        val declaring = declaring(filter.field) ?: return filter
        return if (filter.matchesAbsentField() == true) {
            normalizer.simplifyOr(listOf(undeclared(declaring), filter))
        } else {
            and(declared(declaring), filter)
        }
    }

    /** [filter] read on the events that declare any body path it searches. */
    private fun searched(filter: SearchFilter): FilterExpression {
        val declarations = filter.fields.mapNotNull(::declaring).ifEmpty { return filter }
        return and(normalizer.simplifyOr(declarations.map(::declared)), filter)
    }

    /**
     * The events that declare the body path [field] reads, by revision; `null` when it reads no body path. The body
     * itself is no path of it: every stored event has one.
     */
    private fun declaring(field: QueryField<*>): Map<Int, Set<String>>? {
        val resolved = resolver.fields.getValue(field)
        val path = resolved.logicalField.path.removePrefix("${bodies.events.path}.").split('.')
        if (path.first() != EventFields.BODY || path.size == 1) return null
        val declaring = bodies.declaring(QueryField(path.drop(1).joinToString(".")).toPathTemplate())
        requireValid(declaring.isNotEmpty()) {
            QueryViolation.UnsupportedCapability(resolved.logicalField, setOfNotNull(resolved.capability))
        }
        return declaring
    }

    private fun declared(declaring: Map<Int, Set<String>>): FilterExpression = normalizer.simplifyOr(
        declaring.map { (revision, names) ->
            and(InFilter(event(EventFields.NAME, QueryCapability.EXACT_MATCH), names.toList()), atMost(revision))
        },
    )

    private fun undeclared(declaring: Map<Int, Set<String>>): FilterExpression = normalizer.simplifyAnd(
        declaring.map { (revision, names) ->
            normalizer.simplifyOr(
                listOf(
                    NotInFilter(event(EventFields.NAME, QueryCapability.EXACT_MATCH), names.toList()),
                    GreaterThanFilter(event(EventFields.REVISION, QueryCapability.RANGE), revision),
                ),
            )
        },
    )

    private fun atMost(revision: Int): FilterExpression =
        LessThanOrEqualFilter(event(EventFields.REVISION, QueryCapability.RANGE), revision)

    /** A fresh reference to the event's own [name] field in [scope], registered for [capability]. */
    private fun event(name: String, capability: QueryCapability): QueryField<*> =
        resolver.register(resolver.reference(QueryField(name), scope, capability), scope, capability)

    private fun and(left: FilterExpression, right: FilterExpression): FilterExpression =
        normalizer.simplifyAnd(listOf(left, right))
}
