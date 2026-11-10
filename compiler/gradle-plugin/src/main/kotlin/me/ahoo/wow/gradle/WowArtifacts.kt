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

import me.ahoo.wow.gradle.WowPlugin.Companion.TOOLS_CONFIGURATION
import me.ahoo.wow.gradle.WowPlugin.Companion.version
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider

/**
 * Wow's artifacts in a project of any role: the Wow dependencies under the BOM of the plugin's version, unless the
 * build declares the module itself ([wowDependencies]); the tools the Wow tasks load in their workers
 * ([toolsConfiguration]); and the attributes of the documentation variants Wow publishes and resolves
 * ([documentationAttributes]), the peer contract's among them, which the domain role offers and the api role takes;
 * and who owns each artifact of a resolved configuration ([ownersOf]), which the api's contract and the service's
 * merged Manifest name.
 */
internal object WowArtifacts {
    /**
     * Adds `platform("me.ahoo.wow:wow-bom:<plugin version>")` to [configuration] of [project], so its Wow dependencies
     * take no version, and the role's Wow module [artifact] unless the module declares it in a configuration it
     * compiles against ([DECLARING]): by its coordinates, or as its jar among files.
     */
    internal fun wowDependencies(project: Project, configuration: String, artifact: String) {
        project.configurations.named(configuration) {
            it.withDependencies { dependencies ->
                val declared = DECLARING.mapNotNull(project.configurations::findByName)
                    .filter { other -> other.name != configuration }.flatMap { other -> other.dependencies } +
                    dependencies
                if (declared.none { dependency -> declares(dependency, artifact) }) {
                    dependencies.add(project.dependencies.create("$GROUP:$artifact"))
                }
                dependencies.add(project.dependencies.platform("$GROUP:$BOM:$version"))
            }
        }
    }

    /** Whether [dependency] declares the Wow module [artifact]: by its coordinates, or as its jar. */
    internal fun declares(dependency: Dependency, artifact: String): Boolean = when (dependency) {
        is FileCollectionDependency -> dependency.files.any { it.name.startsWith("$artifact-") }
        else -> dependency.name == artifact
    }

    /**
     * [TOOLS_CONFIGURATION]: the introspection tools the workers load (victools, Jackson, the kernel), resolved in
     * the build's project so they stay off the build script's class path. By default it holds
     * `me.ahoo.wow:wow-manifest-tools` of the plugin's version; declaring a dependency in it replaces that default.
     */
    internal fun toolsConfiguration(project: Project): Configuration {
        project.configurations.findByName(TOOLS_CONFIGURATION)?.let { return it }
        val objects = project.objects
        return project.configurations.create(TOOLS_CONFIGURATION) { configuration ->
            configuration.description = "The tools the Wow tasks run in their isolated workers."
            configuration.isCanBeConsumed = false
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

    /** The attributes of a documentation variant of [docsType]: the merged Manifest's, or the peer contract's. */
    internal fun documentationAttributes(project: Project, configuration: Configuration, docsType: String) {
        val objects = project.objects
        configuration.attributes { attributes ->
            attributes.attribute(
                Category.CATEGORY_ATTRIBUTE,
                objects.named(Category::class.java, Category.DOCUMENTATION)
            )
            attributes.attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType::class.java, docsType))
            attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
            attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
        }
    }

    /**
     * The owner of each file [configuration] resolves to, by its path, as a failure names it: `project ':x'` for a
     * project of the build, the coordinates for a module. Only messages read it.
     */
    internal fun ownersOf(configuration: Configuration): Provider<Map<String, String>> =
        configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
            artifacts.associate { artifact ->
                artifact.file.path to when (val id = artifact.id.componentIdentifier) {
                    is ProjectComponentIdentifier -> "project '${id.projectPath}'"
                    is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
                    else -> id.displayName
                }
            }
        }

    internal const val GROUP = "me.ahoo.wow"
    private const val BOM = "wow-bom"

    /** The configurations a module declares what it compiles against in. */
    private val DECLARING = listOf(
        JavaPlugin.API_CONFIGURATION_NAME,
        JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
        JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
        JavaPlugin.COMPILE_ONLY_API_CONFIGURATION_NAME,
    )
}
