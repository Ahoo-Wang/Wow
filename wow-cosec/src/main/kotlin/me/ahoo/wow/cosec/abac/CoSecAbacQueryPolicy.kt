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

package me.ahoo.wow.cosec.abac

import me.ahoo.cosec.webflux.ReactiveSecurityContexts
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.snapshot.filter.AbacQueryOptions
import me.ahoo.wow.query.snapshot.filter.AbacQueryPolicy
import reactor.core.publisher.Mono
import reactor.util.context.ContextView

/**
 * The principal's ABAC tags from CoSec: each attribute of the authenticated principal whose key starts with
 * [attributePrefix] is a tag named by the rest of the key, its values a comma-separated string or a collection
 * (`abac.region = "east,north"` is the tag `region` with `east` and `north`). An anonymous principal, or one without
 * such attributes, has no tags. CoSec's WebFlux filter puts the principal in the Reactor context the query runs in.
 * Since 9.9.0.
 */
class CoSecAbacQueryPolicy(
    options: AbacQueryOptions = AbacQueryOptions.DEFAULT,
    val attributePrefix: String = DEFAULT_ATTRIBUTE_PREFIX,
) : AbacQueryPolicy(options) {
    init {
        require(attributePrefix.isNotBlank()) { "attributePrefix must not be blank." }
    }

    override fun getPrincipalTags(contextView: ContextView, context: QueryContext<*>): Mono<AbacTags> = Mono.fromCallable {
        val principal = with(ReactiveSecurityContexts) { contextView.getSecurityContextOrEmpty() }?.principal
        if (principal == null || principal.anonymous) emptyMap() else tagsOf(principal.attributes)
    }

    /** The tags among [attributes]: a key without a name after the prefix, or without values, is none. */
    fun tagsOf(attributes: Map<String, Any?>): AbacTags = attributes.entries.mapNotNull { (key, value) ->
        val name = key.takeIf { it.startsWith(attributePrefix) }?.removePrefix(attributePrefix)?.trim()
        val values = valuesOf(value)
        if (name.isNullOrEmpty() || values.isEmpty()) null else name to values
    }.toMap()

    private fun valuesOf(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is Iterable<*> -> value.mapNotNull { it?.toString()?.trim() }
        is Array<*> -> value.mapNotNull { it?.toString()?.trim() }
        else -> value.toString().split(',').map(String::trim)
    }.filter(String::isNotEmpty).distinct()

    companion object {
        const val DEFAULT_ATTRIBUTE_PREFIX = "abac."
    }
}
