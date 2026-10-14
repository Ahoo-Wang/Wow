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

import com.google.devtools.ksp.gradle.KspExtension
import me.ahoo.wow.manifest.Manifest
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.CommandLineArgumentProvider
import java.util.Properties

/**
 * `me.ahoo.wow`: builds a V10 domain module. Once the Kotlin JVM plugin is applied, it
 *
 * - applies KSP with `wow-compiler` (of the plugin's version, unless [WowExtension.addCompilerDependency] is false)
 *   in `generate` mode, so the module's aggregates compile into models and its Manifest fragment
 *   (`META-INF/wow/manifest/<project name>.json`, decision D74); only the V10 model processor runs, the 9.x ones
 *   (`wow-metadata.json`, query helpers) are excluded;
 * - registers [GENERATE_FRAGMENT_TASK] ([GenerateManifestFragment]), which completes the fragment with the
 *   schemas Jackson introspection gives (decision D73); the completed fragment is part of the module's resources in
 *   place of the compiler's;
 * - registers [MERGE_TASK] ([MergeManifest]), which merges every fragment on the runtime class path into
 *   `build/wow/manifest.json`, and exposes that file as the outgoing variant [MANIFEST_ELEMENTS], published with
 *   the `java` component;
 * - registers [CHECK_COMPATIBILITY_TASK] ([CheckManifestCompatibility]), part of `check`, which compares the merged
 *   Manifest with the committed baseline (`manifest/baseline.json`, [WowExtension.manifestBaseline]) and fails on a
 *   breaking change within the major version (decision D76), and [UPDATE_BASELINE_TASK] ([UpdateManifestBaseline]),
 *   which records the merged Manifest as the baseline;
 * - registers [COLLECT_PEERS_TASK] ([CollectPeerManifests]), which copies the merged Manifests of the services the
 *   [PEERS_CONFIGURATION] names into the module's resources, `META-INF/wow/peers/<service>.json` (decision D139).
 *
 * The fragment, merge and compatibility tasks run their work in workers with an isolated class loader over
 * [TOOLS_CONFIGURATION]: the plugin itself depends on KSP and `wow-manifest` only, so it brings no Jackson, victools
 * or Kotlin reflection to the build script.
 */
class WowPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(EXTENSION, WowExtension::class.java)
        extension.addCompilerDependency.convention(true)
        extension.serviceName.convention(project.name)
        extension.serviceVersion.convention(project.provider { project.version.toString() })
        extension.manifestBaseline.convention(project.layout.projectDirectory.file(BASELINE_FILE))
        project.pluginManager.withPlugin(KOTLIN_JVM) { configure(project, extension) }
    }

    private fun configure(project: Project, extension: WowExtension) {
        configureKsp(project, extension)

        val tools = toolsConfiguration(project)
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val runtimeClasspath = project.configurations.named(main.runtimeClasspathConfigurationName)
        val compilerFragments = project.files(project.layout.buildDirectory.dir(KSP_RESOURCES))
            .builtBy(KSP_TASK)
            .asFileTree
            .matching { it.include("${Manifest.FRAGMENT_DIRECTORY}/*.json") }
        val fragmentDirectory = project.layout.buildDirectory.dir("wow/manifest-fragment")
        val generate = project.tasks.register(GENERATE_FRAGMENT_TASK, GenerateManifestFragment::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Completes this module's Manifest fragment with the schemas of its types."
            task.fragments.from(compilerFragments)
            task.classpath.from(main.output.classesDirs, runtimeClasspath)
            task.tools.from(tools)
            task.schemaOverrides.set(extension.schemaOverrides)
            task.outputDirectory.set(fragmentDirectory)
        }
        main.output.dir(mapOf("builtBy" to generate), fragmentDirectory)
        // The completed fragment takes the place of the compiler's.
        project.tasks.named(main.processResourcesTaskName, ProcessResources::class.java) {
            it.exclude("${Manifest.FRAGMENT_DIRECTORY}/**")
        }

        val merge = project.tasks.register(MERGE_TASK, MergeManifest::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Merges the Manifest fragments on the runtime class path into the service's manifest.json."
            // The module's own completed fragment and its dependencies': not its peers, which it may not resolve.
            task.classpath.from(project.files(fragmentDirectory).builtBy(generate), runtimeClasspath)
            task.tools.from(tools)
            task.serviceName.set(extension.serviceName)
            task.serviceVersion.set(extension.serviceVersion)
            task.outputFile.set(project.layout.buildDirectory.file("wow/manifest.json"))
        }
        manifestElements(project, merge)
        compatibility(project, extension, merge, tools)

        val peers = peersConfiguration(project)
        val peersDirectory = project.layout.buildDirectory.dir("wow/peers")
        val collect = project.tasks.register(COLLECT_PEERS_TASK, CollectPeerManifests::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Copies the merged Manifests of the wowPeers services into META-INF/wow/peers."
            task.peers.from(peers)
            task.classpath.from(project.files(fragmentDirectory).builtBy(generate), runtimeClasspath)
            task.serviceName.set(extension.serviceName)
            task.outputDirectory.set(peersDirectory)
        }
        main.output.dir(mapOf("builtBy" to collect), peersDirectory)
    }

    /** Applies KSP with the model processor alone, and adds `wow-compiler` unless turned off. */
    private fun configureKsp(project: Project, extension: WowExtension) {
        project.pluginManager.apply(KSP)
        project.extensions.configure(KspExtension::class.java) { ksp ->
            ksp.arg(MODEL_OPTION, "generate")
            ksp.arg(MODULE_OPTION, project.name)
            ksp.arg(ContextArgument(extension.context))
            // A V10 module is built by the model processor alone; the 9.x processors serve wow-core.
            NINE_X_PROCESSORS.forEach(ksp::excludeProcessor)
        }
        project.configurations.named(KSP_CONFIGURATION) { configuration ->
            configuration.withDependencies { dependencies ->
                if (extension.addCompilerDependency.get()) {
                    dependencies.add(project.dependencies.create("$GROUP:wow-compiler:$version"))
                }
            }
        }
    }

    /**
     * The Manifest compatibility gate (decision D76): [CHECK_COMPATIBILITY_TASK] compares the merged Manifest with the
     * committed [WowExtension.manifestBaseline] and is part of `check`; [UPDATE_BASELINE_TASK] rewrites the baseline.
     */
    private fun compatibility(
        project: Project,
        extension: WowExtension,
        merge: TaskProvider<MergeManifest>,
        tools: Configuration,
    ) {
        val update = project.tasks.register(UPDATE_BASELINE_TASK, UpdateManifestBaseline::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Records the merged Manifest as the baseline checkManifestCompatibility compares with."
            task.manifest.set(merge.flatMap { it.outputFile })
            task.baseline.set(extension.manifestBaseline)
        }
        val check = project.tasks.register(CHECK_COMPATIBILITY_TASK, CheckManifestCompatibility::class.java) { task ->
            task.group = LifecycleBasePlugin.VERIFICATION_GROUP
            task.description = "Fails on a change of the merged Manifest that breaks its baseline within the major version."
            task.baseline.set(extension.manifestBaseline)
            task.manifest.set(merge.flatMap { it.outputFile })
            task.tools.from(tools)
            // Recording the baseline and checking against it in one build checks the new baseline.
            task.mustRunAfter(update)
            task.report.set(project.layout.buildDirectory.file("wow/manifest-compatibility.txt"))
        }
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { it.dependsOn(check) }
    }

    /**
     * [MANIFEST_ELEMENTS]: the outgoing variant of the service's merged `manifest.json`, what a consumer's
     * [PEERS_CONFIGURATION] selects (decision D139). Its attributes are those of a documentation variant (like the
     * sources and Javadoc ones Gradle defines) of the docs type [MANIFEST_DOCS_TYPE], so no other variant matches a
     * request for it. It is added to the `java` component as an optional variant: a publication carries the Manifest
     * as the artifact of classifier [MANIFEST_CLASSIFIER] (`<name>-<version>-wow-manifest.json`), and Gradle Module
     * Metadata lets a consumer select it by the same attributes.
     */
    private fun manifestElements(project: Project, merge: TaskProvider<MergeManifest>) {
        val elements = project.configurations.create(MANIFEST_ELEMENTS) { configuration ->
            configuration.description = "The service's merged Wow Manifest, for the services that take it as a peer."
            configuration.isCanBeConsumed = true
            configuration.isCanBeResolved = false
            configuration.isVisible = false
            manifestAttributes(project, configuration)
        }
        project.artifacts.add(elements.name, merge.flatMap { it.outputFile }) { artifact ->
            artifact.builtBy(merge)
            artifact.type = MANIFEST_EXTENSION
            artifact.extension = MANIFEST_EXTENSION
            artifact.classifier = MANIFEST_CLASSIFIER
        }
        project.pluginManager.withPlugin("java") {
            val component = project.components.getByName("java") as AdhocComponentWithVariants
            component.addVariantsFromConfiguration(elements) { it.mapToOptional() }
        }
    }

    /**
     * [PEERS_CONFIGURATION]: the services whose merged Manifests this one takes as peers, a project
     * (`wowPeers(project(":inventory-server"))`) or the coordinates of a published service
     * (`wowPeers("com.example:inventory-server:1.0.0")`); it resolves their [MANIFEST_ELEMENTS] variant only, without
     * their dependencies.
     */
    private fun peersConfiguration(project: Project): Configuration =
        project.configurations.create(PEERS_CONFIGURATION) { configuration ->
            configuration.description = "The services whose merged Wow Manifests this service takes as peers."
            configuration.isCanBeConsumed = false
            configuration.isTransitive = false
            manifestAttributes(project, configuration)
        }

    private fun manifestAttributes(project: Project, configuration: Configuration) {
        val objects = project.objects
        configuration.attributes { attributes ->
            attributes.attribute(
                Category.CATEGORY_ATTRIBUTE,
                objects.named(Category::class.java, Category.DOCUMENTATION)
            )
            attributes.attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType::class.java, MANIFEST_DOCS_TYPE))
            attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
            attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
        }
    }

    /**
     * [TOOLS_CONFIGURATION]: the introspection tools the workers load (victools, Jackson, the kernel), resolved in
     * the build's project so they stay off the build script's class path (decision D83). By default it holds
     * `me.ahoo.wow:wow-manifest-tools` of the plugin's version; declaring a dependency in it replaces that default.
     */
    private fun toolsConfiguration(project: Project): Configuration {
        val objects = project.objects
        return project.configurations.create(TOOLS_CONFIGURATION) { configuration ->
            configuration.description = "The tools the Wow Manifest tasks run in their isolated workers."
            configuration.isCanBeConsumed = false
            configuration.isVisible = false
            configuration.attributes { attributes ->
                attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                attributes.attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objects.named(LibraryElements::class.java, LibraryElements.JAR),
                )
                attributes.attribute(
                    Bundling.BUNDLING_ATTRIBUTE,
                    objects.named(Bundling::class.java, Bundling.EXTERNAL)
                )
            }
            configuration.defaultDependencies { dependencies ->
                dependencies.add(project.dependencies.create("$GROUP:wow-manifest-tools:$version"))
            }
        }
    }

    /** `wow.context`, only when [WowExtension.context] is set: otherwise the compiler takes the `@BoundedContext`. */
    internal class ContextArgument(
        @get:Input @get:Optional val context: Provider<String>
    ) : CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> = listOfNotNull(context.orNull?.let { "$CONTEXT_OPTION=$it" })
    }

    companion object {
        const val EXTENSION = "wow"
        const val GENERATE_FRAGMENT_TASK = "generateWowManifestFragment"
        const val MERGE_TASK = "mergeWowManifest"
        const val TOOLS_CONFIGURATION = "wowManifestTools"
        const val COLLECT_PEERS_TASK = "collectWowPeers"
        const val PEERS_CONFIGURATION = "wowPeers"
        const val MANIFEST_ELEMENTS = "wowManifestElements"
        const val CHECK_COMPATIBILITY_TASK = "checkManifestCompatibility"
        const val UPDATE_BASELINE_TASK = "updateManifestBaseline"

        /** Where the Manifest baseline lives in the project by default. */
        const val BASELINE_FILE = "manifest/baseline.json"

        /** The docs type of the merged Manifest's variant, and the classifier of its published artifact. */
        const val MANIFEST_DOCS_TYPE = "wow-manifest"
        const val MANIFEST_CLASSIFIER = "wow-manifest"
        private const val MANIFEST_EXTENSION = "json"
        private const val GROUP_NAME = "wow"
        private const val GROUP = "me.ahoo.wow"
        private const val KOTLIN_JVM = "org.jetbrains.kotlin.jvm"
        private const val KSP = "com.google.devtools.ksp"
        private const val KSP_CONFIGURATION = "ksp"
        private const val KSP_TASK = "kspKotlin"
        private const val KSP_RESOURCES = "generated/ksp/main/resources"
        private const val MODEL_OPTION = "wow.model"
        private const val MODULE_OPTION = "wow.manifest.module"
        private const val CONTEXT_OPTION = "wow.context"
        private val NINE_X_PROCESSORS = listOf(
            "me.ahoo.wow.compiler.metadata.MetadataSymbolProcessorProvider",
            "me.ahoo.wow.compiler.query.QuerySymbolProcessorProvider",
            "me.ahoo.wow.compiler.aggregate.metadata.AggregatesMetadataSymbolProcessorProvider",
        )

        /** The plugin's version, which is also the version of `wow-compiler` it adds. */
        val version: String by lazy {
            Properties().apply {
                WowPlugin::class.java.getResourceAsStream("/META-INF/wow-gradle-plugin.properties")!!.use(::load)
            }.getProperty("version")
        }
    }
}
