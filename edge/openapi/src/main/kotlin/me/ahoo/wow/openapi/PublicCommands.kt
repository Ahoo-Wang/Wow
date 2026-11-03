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

package me.ahoo.wow.openapi

import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.copyWith

/**
 * A Manifest as the public port serves it: without its internal commands (`@InternalCommand`), whose routes only the
 * internal port serves, and without the schemas only they reach, so a document of the public routes says nothing of
 * them. A schema something else reaches stays: a public command's, a state's, an event's, a service route's, or
 * one of `$defs` no internal command reaches, as every schema of the Manifest is otherwise documented.
 */
internal object PublicCommands {
    fun of(manifest: Manifest): Manifest {
        val commands = manifest.contexts.flatMap { context -> context.aggregates.flatMap { it.commands } }
        if (commands.none { it.internal }) return manifest
        val public = manifest.copyWith(
            contexts = manifest.contexts.map { context ->
                context.copyWith(
                    aggregates = context.aggregates.map { aggregate ->
                        aggregate.copyWith(commands = aggregate.commands.filterNot { it.internal })
                    },
                )
            },
        )
        val internalKeys = commands.filter { it.internal }.map { Manifest.schemaKey(it.schema) }.toSet() -
            commands.filterNot { it.internal }.map { Manifest.schemaKey(it.schema) }.toSet()
        val hidden = reached(internalKeys, manifest.defs)
        // A subscription that derives or calls an internal command refers to its schema too: that is no reason to
        // document it.
        val roots = (manifest.defs.keys - hidden) +
            public.schemaRefs.map(Manifest::schemaKey).filterNot { it in internalKeys }
        val kept = reached(roots, manifest.defs)
        return public.copyWith(defs = manifest.defs.filterKeys { it in kept })
    }

    /** The keys of [defs] that [keys] are, or that their schemas refer to, transitively. */
    private fun reached(keys: Collection<String>, defs: Map<String, Any?>): Set<String> {
        val reached = mutableSetOf<String>()
        fun visit(node: Any?) {
            when (node) {
                is Map<*, *> -> node.values.forEach(::visit)
                is List<*> -> node.forEach(::visit)
                is String -> if (Manifest.isSchemaRef(node)) enter(Manifest.schemaKey(node), defs, reached, ::visit)
            }
        }
        keys.forEach { enter(it, defs, reached, ::visit) }
        return reached
    }

    private fun enter(key: String, defs: Map<String, Any?>, reached: MutableSet<String>, visit: (Any?) -> Unit) {
        if (key in defs && reached.add(key)) visit(defs[key])
    }
}
