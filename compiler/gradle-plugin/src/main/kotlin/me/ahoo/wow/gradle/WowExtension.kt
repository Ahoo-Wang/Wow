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

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * The `wow { }` block of a module that applies `me.ahoo.wow`, or `me.ahoo.wow.service`, which builds its own sources
 * as a domain module does: its models and contract.
 *
 * ```kotlin
 * wow {
 *     context = "shop"
 *     service = "shop-service"
 *     schemaOverrides.put("sample.Money", """{"type":"string"}""")
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
     * The service that hosts the module's contexts, as it names itself (`spring.application.name`): the module's
     * contract names it, so a peer that depends on the api module finds the service by this name, and the service
     * refuses to start under another. The service module merging the contexts takes its name from the contracts
     * (its own included), which must agree; unset everywhere, the contract names no service, a peer reaches it at
     * `wow.peers.<context>.url` alone, and the service is named after the project path of its module.
     */
    abstract val service: Property<String>

    /**
     * JSON Schemas (2020-12, as JSON text) that replace the introspected schema of a class, by its JVM binary name.
     * Introspection follows Jackson's annotations, naming and Kotlin module, but cannot see inside a custom serializer:
     * a type written by one declares its schema here.
     */
    abstract val schemaOverrides: MapProperty<String, String>
}

/**
 * The `wow { }` block of a module that applies `me.ahoo.wow.service`: a domain module's, and the baseline of the
 * service's Manifest. The service's name comes from the contracts of the contexts it hosts ([WowExtension.service]),
 * its version from the project's.
 *
 * ```kotlin
 * wow {
 *     manifestBaseline = layout.projectDirectory.file("manifest/baseline.json")
 * }
 * ```
 */
abstract class WowServiceExtension : WowExtension() {
    /**
     * The committed Manifest `checkWowManifest` compares the merged Manifest with and `updateWowManifestBaseline`
     * rewrites; `manifest/baseline.json` of the project by default. Commit it.
     */
    abstract val manifestBaseline: RegularFileProperty
}
