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

import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.dsl.DependencyFactory
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import javax.inject.Inject

/**
 * The `wow { }` block of a module that applies `me.ahoo.wow`, or `me.ahoo.wow.service`, which builds its own sources
 * as a domain module does: its models and contract.
 *
 * ```kotlin
 * wow {
 *     context = "shop"
 *     contractPackage = "com.example.shop.api"
 *     service = "shop-service"
 *     schemaOverrides.put("sample.Money", """{"type":"string"}""")
 * }
 * ```
 *
 * It also names the contract of an api module, for a module that uses the code generated from it:
 * `implementation(wow.contract(project(":ordering-api")))` ([contract]).
 */
abstract class WowExtension {
    /** Creates the dependencies [contract] names by coordinates. */
    @get:Inject
    protected abstract val dependencyFactory: DependencyFactory

    /**
     * The contract of the api module [dependency] names: `project(":ordering-api")`, its coordinates
     * `"group:name[:version]"`, or an external module dependency without artifacts of its own. It is the module with
     * its feature `contract` (capability `<group>:<name>-contract`) required: `META-INF/wow/contract.json` and the code
     * generated from it, `<Context>Contract` and `<State>View`, with the api's types. A module that uses that code
     * declares it, `implementation(wow.contract(project(":ordering-api")))`; a service sees every api's contract without
     * it, and a domain's tests those of the build's api modules. A version catalog's accessor takes the other form
     * ([contract] of a provider).
     *
     * @throws IllegalArgumentException when [dependency] names no module: a classifier or an extension names an
     * artifact, not a contract, and anything else is no module at all.
     */
    fun contract(dependency: Any): ModuleDependency {
        val module = when (dependency) {
            is ProjectDependency -> dependency.copy()
            is ExternalModuleDependency -> external(dependency)
            is CharSequence -> coordinates(dependency.toString())
            else -> throw IllegalArgumentException(usage("not $dependency"))
        }
        module.capabilities { it.requireFeature(ContractRole.FEATURE) }
        return module
    }

    /**
     * The contract of the api module a version catalog names, `implementation(wow.contract(libs.ordering.api))`, as
     * [contract] of the dependency: a provider, as `implementation(...)` takes one.
     */
    fun contract(dependency: Provider<out ExternalModuleDependency>): Provider<ModuleDependency> =
        dependency.map { contract(it) }

    private fun external(dependency: ExternalModuleDependency): ModuleDependency {
        require(dependency.artifacts.isEmpty()) {
            usage("not ${dependency.group}:${dependency.name}, which names an artifact of a classifier or an extension")
        }
        return dependency.copy()
    }

    private fun coordinates(notation: String): ModuleDependency {
        val parts = COORDINATES.matchEntire(notation)?.groupValues
            ?: throw IllegalArgumentException(usage("not \"$notation\", which is no plain group:name[:version]"))
        return dependencyFactory.create(parts[1], parts[2], parts[3].ifEmpty { null })
    }

    private fun usage(what: String): String =
        "wow.contract(...) takes an api module, project(\":ordering-api\"), its coordinates " +
            "\"group:name[:version]\" or a version catalog's libs.ordering.api, $what"

    private companion object {
        /** `group:name` or `group:name:version`, none holding a classifier (`:`) or an extension (`@`). */
        val COORDINATES = Regex("""([^:@\s]+):([^:@\s]+)(?::([^:@\s]+))?""")
    }

    /**
     * The bounded context this module's aggregates and subscriptions belong to (the KSP option `wow.context`).
     * Unset, the module's one `@BoundedContext` names it.
     */
    abstract val context: Property<String>

    /**
     * The package of the code the api module generates for the module's contexts, `<Context>Contract` (and a view of
     * a state whose aggregate's classes share no package): the package other services import it from, so it is set
     * once and then kept, whatever aggregates the module gains. Required once the module has a context:
     * `generateWowContract` fails without it, naming the line to add. A Java package name; a segment that is a Kotlin
     * keyword is quoted in the generated code.
     */
    abstract val contractPackage: Property<String>

    /**
     * The service that hosts the module's contexts, as it names itself (`spring.application.name`): the module's
     * contract names it, so a peer that depends on the api module finds the service by this name, and the service
     * refuses to start under another. The service module merging the contexts takes its name from the contracts
     * (its own included), which must agree; unset everywhere, the contract names no service, a peer reaches it at
     * `wow.peers.<context>.url` alone, and the service is named after the project path of its module.
     */
    abstract val service: Property<String>

    /**
     * JSON Schemas (2020-12, as JSON text) that replace the introspected schema of a class, by its JVM binary name, or
     * of a property, by the binary name of the class that declares the accessor Jackson writes it by, `#` and the
     * property's declared name (`sample.Order#id`). Introspection follows Jackson's annotations, naming and Kotlin
     * module, but cannot see inside a custom serializer: a class or a property written by one declares its schema
     * here, and the build stops for one until it does, printing the line to add.
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
