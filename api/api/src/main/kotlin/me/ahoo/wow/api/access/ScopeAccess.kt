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

import me.ahoo.wow.api.aggregate.Scope

/**
 * The default rule: who reads and changes an aggregate that no access policy of its own governs ([AccessPolicy]). A
 * caller acting in a scope reads and changes the aggregates whose owner is the scope's owner and whose space is its
 * space, each part compared only when the scope names it: a caller with neither, of its tenant or acting across scopes
 * without naming one, reads and changes every aggregate of its tenant. The aggregate's ownership narrows the scope
 * first ([me.ahoo.wow.api.aggregate.Ownership.scopeOf]): no owner is compared for an aggregate of its tenant.
 *
 * It is one rule on every path, over the built-in attributes [AccessAttribute.OWNER] and [AccessAttribute.SPACE]: the
 * kernel evaluates it on the aggregate a command changes ([allows]), a state read on the state it reads, and the
 * query side compiles it to a filter of the records it reads.
 */
object ScopeAccess {
    /** The rule of a caller acting in [scope]: each part [scope] names is the aggregate's. */
    @JvmStatic
    fun of(scope: Scope): AccessPredicate {
        val owner = scope.ownerId?.let { equal(AccessAttribute.OWNER, it) }
        val space = scope.spaceId?.let { equal(AccessAttribute.SPACE, it) }
        return when {
            owner == null && space == null -> AccessPredicate.Allowed
            owner == null -> space!!
            space == null -> owner
            else -> AccessPredicate.AllOf(listOf(owner, space))
        }
    }

    /** Whether [predicate], of the built-in attributes alone, grants access to an aggregate in [aggregate]. */
    @JvmStatic
    fun allows(predicate: AccessPredicate, aggregate: Scope): Boolean = predicate.allows { valueOf(it, aggregate) }

    /** The value of a built-in [attribute] of an aggregate in [aggregate]; null for any other attribute. */
    @JvmStatic
    fun valueOf(attribute: AccessAttribute<*, *>, aggregate: Scope): String? = when (attribute) {
        AccessAttribute.OWNER -> aggregate.ownerId
        AccessAttribute.SPACE -> aggregate.spaceId
        else -> null
    }

    private fun equal(attribute: AccessAttribute<*, *>, value: String): AccessPredicate =
        AccessPredicate.Comparison(attribute, AccessOperator.EQUAL, listOf(value))
}
