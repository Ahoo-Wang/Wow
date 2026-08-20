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

package me.ahoo.wow.query.policy.abac

import me.ahoo.wow.api.abac.AbacTagKey
import me.ahoo.wow.api.abac.AbacTagValue
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.policy.QueryPolicyContext
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySchemaCustomizationContext
import me.ahoo.wow.query.schema.QuerySchemaCustomizer
import reactor.core.publisher.Mono
import java.util.Collections
import java.util.function.Function

/** Resolves an immutable principal-tag snapshot from a finite declared key set. */
class PrincipalTagResolver(
    declaredKeys: Set<AbacTagKey>,
    private val resolver: Function<QueryPolicyContext, Mono<AbacTags>>
) {
    val declaredKeys: Set<AbacTagKey> = immutableDeclaredKeys(declaredKeys)

    fun resolve(context: QueryPolicyContext): Mono<AbacTags> = Mono.defer { resolver.apply(context) }
        .map { tags -> immutableTags(tags) }

    override fun toString(): String = "PrincipalTagResolver(declaredKeys=<redacted>, resolver=<redacted>)"

    private fun immutableDeclaredKeys(source: Set<AbacTagKey>): Set<AbacTagKey> {
        val snapshot = LinkedHashSet(source)
        require(snapshot.isNotEmpty()) { "At least one principal tag key must be declared." }
        require(snapshot.size == source.size) { "Principal tag key cardinality changed during snapshot." }
        snapshot.forEach { key ->
            require(key.isNotEmpty() && '.' !in key) { "Principal tag keys must be direct tag children." }
            LogicalField("tags.$key")
        }
        return Collections.unmodifiableSet(snapshot)
    }

    private fun immutableTags(source: AbacTags): AbacTags {
        val snapshot = LinkedHashMap<AbacTagKey, AbacTagValue>(source.size)
        source.forEach { (key, values) ->
            val valueSnapshot = Collections.unmodifiableList(ArrayList(values))
            snapshot[key] = valueSnapshot
        }
        require(snapshot.size == source.size) { "Principal tag cardinality changed during snapshot." }
        return Collections.unmodifiableMap(snapshot)
    }
}

/** Adds only the resolver's declared tag keys to Snapshot query schemas. */
class PrincipalTagSchemaCustomizer(
    resolver: PrincipalTagResolver
) : QuerySchemaCustomizer {
    private val declaredKeys: Set<AbacTagKey> = resolver.declaredKeys

    override fun customize(context: QuerySchemaCustomizationContext): QuerySchema {
        if (context.target.documentKind == QueryDocumentKind.EVENT_STREAM) {
            return context.baseSchema
        }
        return declaredKeys.fold(context.baseSchema) { schema, key ->
            schema.withField(
                QueryFieldSchema(
                    path = LogicalField("tags.$key"),
                    valueKind = QueryFieldValueKind.STRING,
                    nullable = false,
                    collectionKind = QueryCollectionKind.SCALAR
                )
            )
        }
    }
}
