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

package me.ahoo.wow.api.access

/**
 * What an access policy decides for one caller ([AccessPolicy]): a predicate over an aggregate's access attributes,
 * the caller's claims already in it. The query side compiles it to a filter its storage applies; the kernel and the
 * test DSL evaluate it on an aggregate's attribute values ([allows]). Both read it alike:
 *
 * - **Deny by default.** A comparison with a claim the caller does not hold ([Unknown]) holds neither way, and nor does
 *   a comparison of a value the aggregate does not have: its attribute absent, or its record holding none. The
 *   predicate is read in negation normal form, `not` pushed down to the comparisons, each negated comparison holding
 *   only for a present value (`not(a eq x)` is `a` present and not `x`). So an absent value or a missing claim never
 *   grants, however the predicate negates it, and SQL, document and in-memory storage agree on it.
 * - **Logic.** [AllOf] holds when each of its predicates does (none: always), [AnyOf] when one does (none: never),
 *   [Not] when its predicate does not, as above; [Allowed] always, [Denied] never.
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 */
sealed interface AccessPredicate {
    /** Grants: holds for every aggregate (`allowAll()`, `allowIf(true)`). */
    object Allowed : AccessPredicate {
        override fun toString(): String = "Allowed"
    }

    /** Denies: holds for none (`denyAll()`, `allowIf(false)`). */
    object Denied : AccessPredicate {
        override fun toString(): String = "Denied"
    }

    /** A comparison with a claim the caller does not hold: it holds for none, and neither does its negation. */
    object Unknown : AccessPredicate {
        override fun toString(): String = "Unknown"
    }

    /** The [attribute]'s value compared by [operator] with [values], the caller's, never empty but for the checks. */
    class Comparison(
        val attribute: AccessAttribute<*, *>,
        val operator: AccessOperator,
        val values: List<Any> = emptyList(),
    ) : AccessPredicate {
        init {
            require(attribute.multiple == operator.multiple) {
                "$operator compares ${if (operator.multiple) "several values" else "one value"}, not ${attribute.name}."
            }
            require(values.isNotEmpty() == operator.compares) { "$operator of ${attribute.name} takes $values." }
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                other is Comparison && attribute == other.attribute && operator == other.operator &&
                values == other.values

        override fun hashCode(): Int = listOf(attribute, operator, values).hashCode()

        override fun toString(): String = "${attribute.name} $operator $values"
    }

    /** Holds when [predicate] does not, for a present value (see [AccessPredicate]). */
    class Not(val predicate: AccessPredicate) : AccessPredicate {
        override fun equals(other: Any?): Boolean = this === other || other is Not && predicate == other.predicate

        override fun hashCode(): Int = predicate.hashCode()

        override fun toString(): String = "not($predicate)"
    }

    /** Holds when each of [predicates] does; always when there is none. */
    class AllOf(val predicates: List<AccessPredicate>) : AccessPredicate {
        override fun equals(other: Any?): Boolean = this === other || other is AllOf && predicates == other.predicates

        override fun hashCode(): Int = predicates.hashCode()

        override fun toString(): String = "allOf$predicates"
    }

    /** Holds when one of [predicates] does; never when there is none. */
    class AnyOf(val predicates: List<AccessPredicate>) : AccessPredicate {
        override fun equals(other: Any?): Boolean = this === other || other is AnyOf && predicates == other.predicates

        override fun hashCode(): Int = predicates.hashCode()

        override fun toString(): String = "anyOf$predicates"
    }

    /**
     * Whether this predicate grants access to an aggregate whose attribute values [valueOf] gives: a single
     * attribute's value or null, a multiple one's values (a collection), or null when the aggregate has none of it.
     * An enum attribute's value is its constant, as the predicate holds it.
     */
    fun allows(valueOf: (AccessAttribute<*, *>) -> Any?): Boolean = holds(this, valueOf, negated = false)

    private companion object {
        fun holds(predicate: AccessPredicate, valueOf: (AccessAttribute<*, *>) -> Any?, negated: Boolean): Boolean =
            when (predicate) {
                Allowed -> !negated
                Denied -> negated
                Unknown -> false
                is Not -> holds(predicate.predicate, valueOf, !negated)
                is AllOf -> if (negated) {
                    predicate.predicates.any { holds(it, valueOf, negated = true) }
                } else {
                    predicate.predicates.all { holds(it, valueOf, negated = false) }
                }
                is AnyOf -> if (negated) {
                    predicate.predicates.all { holds(it, valueOf, negated = true) }
                } else {
                    predicate.predicates.any { holds(it, valueOf, negated = false) }
                }
                is Comparison -> compares(predicate, valueOf(predicate.attribute), negated)
            }

        /** [comparison] of [value], or its negation; an absent value holds neither way, but as one that is null. */
        fun compares(comparison: Comparison, value: Any?, negated: Boolean): Boolean {
            if (value == null) {
                return comparison.operator == AccessOperator.IS_NULL && !negated
            }
            val values = comparison.values
            val holds = when (comparison.operator) {
                AccessOperator.IS_NULL -> false
                AccessOperator.EQUAL -> value == values.single()
                AccessOperator.IN -> value in values
                AccessOperator.STARTS_WITH -> (value as String).startsWith(values.single() as String)
                AccessOperator.CONTAINS -> values.single() in value as Collection<*>
                AccessOperator.CONTAINS_ANY -> values.any { it in value as Collection<*> }
                AccessOperator.IS_EMPTY -> (value as Collection<*>).isEmpty()
            }
            return holds != negated
        }
    }
}

/**
 * How a [AccessPredicate.Comparison] compares an attribute's value with the caller's: [EQUAL], [IN], [STARTS_WITH] and
 * [IS_NULL] a single attribute's value, [CONTAINS], [CONTAINS_ANY] and [IS_EMPTY] a multiple one's.
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 */
enum class AccessOperator(val multiple: Boolean, val compares: Boolean) {
    /** The value is the one given. */
    EQUAL(multiple = false, compares = true),

    /** The value is one of those given. */
    IN(multiple = false, compares = true),

    /** The value, a string, starts with the one given: a prefix of a materialized path such as `/acme/sales`. */
    STARTS_WITH(multiple = false, compares = true),

    /** The aggregate has no value. */
    IS_NULL(multiple = false, compares = false),

    /** The values hold the one given. */
    CONTAINS(multiple = true, compares = true),

    /** The values hold one of those given. */
    CONTAINS_ANY(multiple = true, compares = true),

    /** The values are none. */
    IS_EMPTY(multiple = true, compares = false),
}
