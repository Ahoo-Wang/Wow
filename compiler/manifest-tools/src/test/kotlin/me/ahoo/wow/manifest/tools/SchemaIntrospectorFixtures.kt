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

package me.ahoo.wow.manifest.tools

import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Manifest.Companion.schemaRef
import me.ahoo.wow.manifest.StateManifest

/** What the schema tests share: the declared schema of [SchemaIntrospectorTest.Money], and the reads of a completed Manifest. */
internal object SchemaIntrospectorFixtures {
    /** Money is written as its text (`@JsonValue`), which introspection cannot see: its schema is declared. */
    const val moneySchema = """{"type":"string","pattern":"^[0-9]+$"}"""

    @Suppress("UNCHECKED_CAST")
    fun defOf(
        manifest: Manifest,
        type: Class<*>
    ): Map<String, Any?> = manifest.defs[type.name] as Map<String, Any?>

    /** A fragment of the one aggregate `plan` of [state]. */
    fun stateFragment(state: Class<*>): Manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                "shop",
                aggregates = listOf(AggregateManifest("plan", state = StateManifest(schemaRef(state.name)))),
            ),
        ),
    )
}
