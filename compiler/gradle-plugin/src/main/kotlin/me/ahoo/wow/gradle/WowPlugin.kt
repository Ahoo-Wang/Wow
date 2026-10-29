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

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.util.GradleVersion
import java.util.Properties

/**
 * `me.ahoo.wow`: builds a domain module ([WowRoles.domain]). It needs the Kotlin JVM plugin (`kotlin("jvm")`), also
 * for a module of Java sources only: KSP processes the module's Java sources within the Kotlin compilation, and the
 * models it generates are Kotlin. A module that applies this plugin without it fails to configure, naming the fix,
 * instead of building without models. It
 *
 * - adds `platform("me.ahoo.wow:wow-bom:<plugin version>")` and `me.ahoo.wow:wow-kernel` to `implementation`, the
 *   kernel unless the module declares it itself, so the module's other Wow dependencies take no version;
 * - applies KSP with `wow-compiler` of the plugin's version (unless `ksp` declares `wow-compiler` itself) in `generate`
 *   mode, so the module's aggregates compile into models and its Manifest fragment
 *   (`META-INF/wow/manifest/<module>.json`, the module named by its project path, [moduleName]);
 * - registers [GENERATE_FRAGMENT_TASK] ([GenerateManifestFragment]), which completes the fragment with the
 *   schemas Jackson introspection gives; the completed fragment is part of the module's resources in
 *   place of the compiler's. A module whose compiler writes no fragment (no model, no processor) contributes nothing;
 * - registers [GENERATE_CONTRACT_TASK] ([GenerateContract]), which filters the completed fragment down to the peer
 *   contract of the module's contexts, offered as the variant [CONTRACT_ELEMENTS] (empty for a module of no fragment),
 *   which an api module declares in [CONTRACT_CONFIGURATION] (`me.ahoo.wow.api`, [WowApiPlugin]); the task fails,
 *   naming the line to add, when the classes of a context are in a project that does not pair with the module;
 * - registers the test suite [INTEGRATION_TEST] unless the build declares one.
 *
 * It needs Gradle [MIN_GRADLE] or later and the Kotlin Gradle plugin [minKotlin] or later, and fails naming both
 * versions otherwise. A failure names the projects and modules involved, not their build directories. No plugin
 * configures another project: a domain module and its api module each configure themselves, so the build may use
 * Gradle's isolated projects; the configuration cache is supported.
 *
 * The fragment, merge and compatibility tasks run their work in workers with an isolated class loader over
 * [TOOLS_CONFIGURATION]: the plugin itself depends on KSP and `wow-manifest` only, so it brings no Jackson, victools
 * or Kotlin reflection to the build script.
 */
class WowPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        requireGradle(project.path, GradleVersion.current())
        val extension = project.extensions.create(EXTENSION, WowExtension::class.java)
        WowRoles.domain(project, extension, ID)
    }

    companion object {
        /** The id of the domain module's plugin. */
        const val ID = "me.ahoo.wow"

        /** The id of the api module's plugin ([WowApiPlugin]). */
        const val API_ID = "me.ahoo.wow.api"

        /** The id of the service module's plugin ([WowServicePlugin]). */
        const val SERVICE_ID = "me.ahoo.wow.service"

        /** The test suite of the container-backed tests the domain and service plugins register. */
        const val INTEGRATION_TEST: String = "integrationTest"

        const val EXTENSION = "wow"
        const val GENERATE_FRAGMENT_TASK = "generateWowManifestFragment"
        const val MERGE_TASK = "mergeWowManifest"
        const val OPENAPI_TASK = "generateWowOpenApi"
        const val TOOLS_CONFIGURATION = "wowTools"
        const val GENERATE_CONTRACT_TASK = "generateWowContract"
        const val CONTRACT_ELEMENTS = "wowContractElements"
        const val ASSEMBLE_CONTRACT_TASK = "assembleWowContract"

        /** The api module's declarable configuration of its domain modules: `wowContract(project(":shop-domain"))`. */
        const val CONTRACT_CONFIGURATION = "wowContract"

        /** The resolvable configuration of the contracts of [CONTRACT_CONFIGURATION]'s domain modules. */
        const val CONTRACTS_CONFIGURATION = "wowContracts"
        const val GENERATE_CONTRACT_SOURCES_TASK = "generateWowContractSources"
        const val CONTRACT_SOURCE_SET = "wowContract"
        const val CORE_JAR_TASK = "wowCoreJar"
        const val CHECK_TASK = "checkWowManifest"
        const val UPDATE_BASELINE_TASK = "updateWowManifestBaseline"

        /**
         * The attribute of an api module's core views: [coreView] of the domain module that requests it, whose
         * contract the api's generated code comes from.
         */
        val API_VIEW: Attribute<String> = Attribute.of("me.ahoo.wow.api-view", String::class.java)

        /** The [API_VIEW] value of the core view of an api module for the domain module [domain] (its path). */
        fun coreView(domain: String): String = "core$domain"

        /**
         * The Gradle property that lets [UPDATE_BASELINE_TASK] record a breaking change within the baseline's
         * compatibility line: `-Pwow.manifest.allowBreaking`, for pre-releases.
         */
        const val ALLOW_BREAKING_PROPERTY = "wow.manifest.allowBreaking"

        /** Where the Manifest baseline lives in the project by default. */
        const val BASELINE_FILE = "manifest/baseline.json"

        /** The docs type of the peer contract's variant. */
        const val CONTRACT_DOCS_TYPE = "wow-contract"

        /** The oldest Gradle the plugin runs on: the first whose Kotlin DSL reads the plugin's Kotlin 2 metadata. */
        internal val MIN_GRADLE: GradleVersion = GradleVersion.version("9.0")

        /**
         * The oldest Kotlin Gradle plugin the plugin works with: one minor version before the Kotlin Wow is built with,
         * the oldest compiler that reads the metadata of Wow's libraries.
         */
        internal val minKotlin: String by lazy {
            val (major, minor) = properties.getProperty("kotlinVersion").split('.').map(String::toInt)
            "$major.${minor - 1}"
        }

        /**
         * The name of the module at the project [path], which names its Manifest fragment and, by default, the service
         * it merges: the path's segments joined by `-` (`:eshop:ordering-domain` is `eshop-ordering-domain`), the
         * root project's [rootName] for the root. Unique within a build, where a project's name need not be.
         */
        internal fun moduleName(path: String, rootName: String): String =
            if (path == ":") rootName else path.removePrefix(":").replace(':', '-')

        /** Fails a project at [path] built by a Gradle older than [MIN_GRADLE]. */
        internal fun requireGradle(path: String, gradle: GradleVersion) {
            if (gradle.baseVersion < MIN_GRADLE) {
                throw GradleException(
                    "Project '$path' applies the Wow plugin ($version), which needs Gradle ${MIN_GRADLE.version} or " +
                        "later; this build runs Gradle ${gradle.version}. Upgrade the Gradle wrapper: " +
                        "./gradlew wrapper --gradle-version <version>.",
                )
            }
        }

        /** Fails a project at [path] whose Kotlin Gradle plugin, of version [kotlin], is older than [minKotlin]. */
        internal fun requireKotlin(path: String, kotlin: String) {
            val (major, minor) = kotlin.split('.').take(2).map { it.takeWhile(Char::isDigit).toInt() }
            val (minMajor, minMinor) = minKotlin.split('.').map(String::toInt)
            if (major < minMajor || major == minMajor && minor < minMinor) {
                throw GradleException(
                    "Project '$path' applies the Wow plugin ($version) with the Kotlin Gradle plugin $kotlin; it " +
                        "needs Kotlin $minKotlin or later, whose compiler reads Wow's libraries. Raise kotlin(\"jvm\") " +
                        "to $minKotlin or later.",
                )
            }
        }

        /** Why a module that applies the plugin [id] without the Kotlin JVM plugin fails. */
        internal fun missingKotlinJvm(path: String, id: String = ID): String =
            "Project '$path' applies the Wow plugin ($id) without the Kotlin JVM plugin " +
                "(org.jetbrains.kotlin.jvm), so it would compile no Wow models: the Wow compiler runs as a KSP " +
                "processor of the Kotlin compilation. Apply kotlin(\"jvm\") in the module as well; a module of " +
                "Java sources only needs it too, and its Java sources are then processed."

        private val properties: Properties by lazy {
            Properties().apply {
                WowPlugin::class.java.getResourceAsStream("/META-INF/wow-gradle-plugin.properties")!!.use(::load)
            }
        }

        /** The plugin's version, which is also the version of the Wow modules it adds. */
        val version: String by lazy { properties.getProperty("version") }
    }
}

/**
 * `me.ahoo.wow.api`: builds a context's api module ([WowRoles.api]), the commands, events and value classes another
 * service depends on, with the peer contract and the code generated from it. The module declares the domain modules
 * whose contracts it carries, `dependencies { wowContract(project(":shop-domain")) }`, and
 *
 * - becomes a `java-library`, with `api(platform("me.ahoo.wow:wow-bom:<plugin version>"))` and
 *   `api("me.ahoo.wow:wow-api")`, the latter unless it declares `wow-api` itself;
 * - assembles `META-INF/wow/contract.json` into its jar ([AssembleContract]): the contexts of those contracts whose
 *   command and event classes are all its own; a declared domain module of no contract fails it, naming the module;
 * - with the Kotlin JVM plugin, compiles `<package>.<Context>Contract` per context and `<State>View` per published
 *   state from that contract ([GenerateContractSources], source set [WowPlugin.CONTRACT_SOURCE_SET]), which its
 *   classes, jar and sources jar carry;
 * - offers each declared domain module a core view of itself, a jar of its own classes without that code, which the
 *   domain compiles against (attribute [WowPlugin.API_VIEW], a variant named after the domain's path): the domain's
 *   contract comes from compiling the domain, so the domain cannot wait for the code generated from it.
 *
 * An api module of no declared domain module carries no contract and generates nothing.
 */
class WowApiPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        WowPlugin.requireGradle(project.path, GradleVersion.current())
        WowRoles.api(project)
    }
}

/**
 * `me.ahoo.wow.service`: builds a service module ([WowRoles.service]): the domain role of `me.ahoo.wow` on its own
 * sources (the processors, routes and models it declares; nothing when it declares none), and the service's
 * Manifest:
 *
 * - [WowPlugin.MERGE_TASK] ([MergeManifest]) merges every fragment on the runtime class path into
 *   `build/wow/manifest.json`, failing on two fragments of one name; the service is the one the contracts of its
 *   contexts name, its own `wow { service }` included, which must agree, else it is named after the project path; its
 *   version is the project's;
 * - [WowPlugin.OPENAPI_TASK] ([GenerateOpenApi]) writes the OpenAPI document of that Manifest's routes into
 *   `build/wow/openapi.json`;
 * - [WowPlugin.CHECK_TASK] ([CheckManifestCompatibility]), part of `check`, compares the merged Manifest with the
 *   committed baseline (`manifest/baseline.json`, [WowServiceExtension.manifestBaseline]) and fails, naming
 *   [WowPlugin.UPDATE_BASELINE_TASK], when there is none, on a breaking change within the baseline's compatibility
 *   line (the service's major; a later major, or a pre-release of a major's first version, may break it), and when the
 *   baseline does not record this build's Manifest; [WowPlugin.UPDATE_BASELINE_TASK] ([UpdateManifestBaseline])
 *   records the merged Manifest, without its version, on the line of its major, refusing a break within the line
 *   unless [WowPlugin.ALLOW_BREAKING_PROPERTY] is set. The service publishes no Manifest: its contract is the committed
 *   baseline.
 *
 * A single-module project, the quickstart's, applies this plugin alone: the module is its domain and its service. It
 * has no api role: a service whose peers compile against its contract splits its commands and events into an api
 * module (`me.ahoo.wow.api`).
 */
class WowServicePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        WowPlugin.requireGradle(project.path, GradleVersion.current())
        val extension = project.extensions.create(WowPlugin.EXTENSION, WowServiceExtension::class.java)
        WowRoles.domain(project, extension, WowPlugin.SERVICE_ID)
        WowRoles.service(project, extension)
    }
}
