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

package me.ahoo.wow.gradle

import org.gradle.api.Action
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested

/**
 * The `wow { }` block of a module that applies `me.ahoo.wow`.
 *
 * ```kotlin
 * wow {
 *     context = "shop"
 *     schemaOverrides.put("sample.Money", """{"type":"string"}""")
 *     manifestBaseline = layout.projectDirectory.file("manifest/baseline.json")
 *     contract {
 *         service = "shop-service"
 *     }
 * }
 * ```
 */
abstract class WowExtension {
    /**
     * The bounded context this module's aggregates and subscriptions belong to (the KSP option `wow.context`).
     * Unset, the module's one `@BoundedContext` names it.
     */
    abstract val context: Property<String>

    /**
     * JSON Schemas (2020-12, as JSON text) that replace the introspected schema of a class, by its JVM binary name.
     * Introspection follows Jackson's annotations, naming and Kotlin module, but cannot see inside a custom serializer:
     * a type written by one declares its schema here.
     */
    abstract val schemaOverrides: MapProperty<String, String>

    /**
     * The service the merged `manifest.json` describes. Unset, it is derived when the Manifest is merged: the service
     * the contracts of the contexts it hosts name (their domain modules' `wow { contract { service } }`, in their api
     * modules' jars on the runtime class path), or the project's path when none does (`:shop-server` is
     * `shop-server`). Contracts that name different
     * services fail the merge: set it then. A peer's contract does not count.
     */
    abstract val serviceName: Property<String>

    /** The service's version in the merged `manifest.json`; the project's version by default. */
    abstract val serviceVersion: Property<String>

    /**
     * The Manifest of the service's last published version, which `checkManifestCompatibility` compares the merged
     * Manifest with and `updateManifestBaseline` rewrites; `manifest/baseline.json` of the project by
     * default. Commit it.
     */
    abstract val manifestBaseline: RegularFileProperty

    /** The peer contract its api modules carry ([WowContract]); `@Nested` makes Gradle create it. */
    @get:Nested
    abstract val contract: WowContract

    /** Configures the peer contract its api modules carry ([WowContract]). */
    fun contract(action: Action<in WowContract>) = action.execute(contract)
}

/**
 * The `wow { contract { } }` block: what the peer contract of the module's contexts says beside them.
 */
abstract class WowContract {
    /**
     * The service that hosts the module's contexts, as it names itself (`spring.application.name`): a peer that
     * depends on the api module finds the service by this name, and the service refuses to start under another.
     * Unset, the contract names no service, and a peer reaches it at `wow.peers.<context>.url` alone.
     *
     * The service's own build derives its Manifest's service name from it ([WowExtension.serviceName]): the name is
     * written once, beside the contexts, and not in each server module that hosts them.
     */
    abstract val service: Property<String>
}
