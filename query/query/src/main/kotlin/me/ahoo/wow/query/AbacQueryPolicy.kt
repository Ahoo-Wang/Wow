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

import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.SnapshotFields

/**
 * The options of [AbacQueryPolicy] (`wow.query.abac.*`). The defaults keep it off; once [enabled], the other two
 * defaults keep 9.x's permissive semantics.
 *
 * @property enabled turns the policy on (`wow.query.abac.enabled`, decision D129): off, it restricts nothing.
 * @property requirePrincipalTags rejects an HTTP snapshot query whose caller has no ABAC tags, instead of letting it
 * match every resource. In-process queries are trusted and never rejected for this.
 * @property matchMissingTagKey lets a resource that lacks one of the caller's tag keys (or has it empty) match, as a
 * public resource. Off, a resource matches only when it carries every non-wildcard key with a value the caller holds.
 * @property attributePrefix the prefix of the caller attributes ([QueryCaller.attributes]) that are ABAC tags: the
 * attribute `abac.region` with the values `east` and `north` is the tag `region`.
 */
data class AbacQueryOptions(
    val enabled: Boolean = false,
    val requirePrincipalTags: Boolean = false,
    val matchMissingTagKey: Boolean = true,
    val attributePrefix: String = DEFAULT_ATTRIBUTE_PREFIX,
) {
    init {
        require(attributePrefix.isNotBlank()) { "attributePrefix must not be blank." }
    }

    companion object {
        const val DEFAULT_ATTRIBUTE_PREFIX: String = "abac."

        @JvmField
        val DEFAULT = AbacQueryOptions()
    }
}

/**
 * Attribute-based access control of snapshot queries (decision D129, 9.x `AbacQueryPolicy` semantics, independent of
 * CoSec): the caller's ABAC tags, its `abac.<key>` [attributes][QueryCaller.attributes], are matched against the
 * snapshot's `tags` system field. Other models are unrestricted (datasets with tag columns join in 6.1d).
 *
 * | Caller tags | Resource tags | Result |
 * |---------|---------|---------|
 * | wildcard (`["*"]`) | any | match when the key exists |
 * | `["a", "b"]` | `["a"]` | match |
 * | `["a", "b"]` | `["c"]` | no match |
 * | any | key absent or empty | match (public resource) |
 * | no tags | any | match |
 *
 * The last two rows are the defaults; [AbacQueryOptions] tightens each. Every tag must match (AND). A tag key that is
 * not a valid query path segment cannot be matched against the snapshot, so it matches nothing (fails closed).
 */
class AbacQueryPolicy(
    val options: AbacQueryOptions = AbacQueryOptions.DEFAULT,
    override val order: Int = 0,
) : QueryPolicy {
    override suspend fun evaluate(caller: QueryCaller, context: QueryContext<*>): FilterExpression {
        if (!options.enabled || context.schema.model != QueryModel.SNAPSHOT) return MatchAllFilter
        val tags = tagsOf(caller.attributes)
        if (tags.isEmpty()) {
            if (options.requirePrincipalTags && context.entry == QueryEntry.HTTP) {
                throw AbacPrincipalTagsRequiredException()
            }
            return MatchAllFilter
        }
        return tags.toFilterExpression(options.matchMissingTagKey)
    }

    /** The ABAC tags among [attributes]: a key without a name after the prefix, or without values, is none. */
    fun tagsOf(attributes: Map<String, List<String>>): Map<String, List<String>> =
        attributes.entries.mapNotNull { (key, values) ->
            val name = key.takeIf { it.startsWith(options.attributePrefix) }
                ?.removePrefix(options.attributePrefix)?.trim()
            val tagValues = values.map(String::trim).filter(String::isNotEmpty).distinct()
            if (name.isNullOrEmpty() || tagValues.isEmpty()) null else name to tagValues
        }.toMap()

    companion object {
        /** The value that matches any value of its key: the key only has to exist. */
        const val WILDCARD: String = "*"

        /** Every tag of [this] as one condition on the snapshot `tags`, ANDed; match-all when there is none. */
        @JvmStatic
        fun Map<String, List<String>>.toFilterExpression(matchMissingTagKey: Boolean = true): FilterExpression {
            val conditions = map { (key, values) -> tagCondition(key, values, matchMissingTagKey) }
            return when (conditions.size) {
                0 -> MatchAllFilter
                1 -> conditions.single()
                else -> AndFilter(conditions)
            }
        }

        private fun tagCondition(key: String, values: List<String>, matchMissingTagKey: Boolean): FilterExpression {
            val field = runCatching { QueryField("${SnapshotFields.TAGS}.$key") }.getOrNull()
                ?.takeIf { '.' !in key }
                ?: return MatchNoneFilter
            return when {
                WILDCARD in values -> ExistsFilter(field)
                !matchMissingTagKey -> InFilter(field, values)
                else -> OrFilter(listOf(NotExistsFilter(field), IsEmptyFilter(field), InFilter(field, values)))
            }
        }
    }
}

/** An HTTP query rejected because its caller has no ABAC tags; see [AbacQueryOptions.requirePrincipalTags]. */
class AbacPrincipalTagsRequiredException :
    RuntimeException("Query requires the principal's ABAC tags."),
    QueryError {
    override val errorCode: String
        get() = QueryErrors.FORBIDDEN

    override val errorMsg: String
        get() = checkNotNull(message)
}
