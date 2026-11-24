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

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject

/**
 * Renders the service's Kubernetes manifests: the `wow-service` chart the plugin carries (at the plugin's own version)
 * with Helm [HelmRelease.VERSION] ([HelmService], found when this task runs and never before), from the values the build
 * generates ([generatedValues]) and the deployment's [values] after them, for [namespace]. It writes a Kustomize base of
 * plain YAML into [outputDirectory]: one file per object, `<kind>-<name>.yaml`, and a `kustomization.yaml` naming them
 * and the namespace. A value the chart's schema refuses fails the build with the chart's own message.
 */
@CacheableTask
internal abstract class GenerateManifests @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    /** The values the build knows: `generateWowHelmValues`'s file, which names the service. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val generatedValues: RegularFileProperty

    /** The deployment's values files ([WowKubernetesExtension.values]), in order. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val values: ConfigurableFileCollection

    /** The order of [values], which decides which one wins: their names, as the files' contents are fingerprinted. */
    @get:Input
    val valuesOrder: Provider<List<String>> get() = values.elements.map { files -> files.map { it.asFile.name } }

    /** The namespace the service is deployed to ([WowKubernetesExtension.namespace]); required when the task runs. */
    @get:Input
    @get:Optional
    abstract val namespace: Property<String>

    /** The chart's version, the plugin's: the chart is in the plugin's resources. */
    @get:Input
    abstract val chartVersion: Property<String>

    /** The pinned Helm's version: the same version renders the same objects on every machine. */
    @get:Input
    abstract val helmVersion: Property<String>

    /** The build's Helm. */
    @get:Internal
    abstract val helm: Property<HelmService>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun render() {
        if (values.isEmpty) {
            throw GradleException(
                "$name renders the wow-service chart with the deployment's values, which the build names: " +
                    "wow { kubernetes { values.from(\"deploy/<service>/values.yaml\") } }.",
            )
        }
        val namespace = namespace.orNull ?: throw GradleException(
            "$name renders the service for the namespace it is deployed to, which the chart derives from (whether a " +
                "peer is in another namespace, the autoscaler's query): set wow { kubernetes { namespace = \"<namespace>\" } }.",
        )
        val release = serviceOf(generatedValues.get().asFile)
        val chart = extractChart(File(temporaryDir, "chart"))
        val arguments = listOf("template", release, chart.path, "--namespace", namespace) +
            (listOf(generatedValues.get().asFile) + values.files).flatMap { listOf("--values", it.path) }
        val rendered = helmTemplate(helm.get().executable(), arguments)
        write(objectsOf(rendered), namespace, release)
    }

    private fun helmTemplate(helm: File, arguments: List<String>): String {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val result = exec.exec { spec ->
            spec.commandLine(listOf(helm.path) + arguments)
            spec.standardOutput = out
            spec.errorOutput = err
            spec.isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            val message = err.toString(Charsets.UTF_8).trim()
            throw GradleException("Helm refused to render the wow-service chart: $message")
        }
        return out.toString(Charsets.UTF_8)
    }

    /** The chart of the plugin's resources, written under [directory]; its directory. */
    private fun extractChart(directory: File): File {
        directory.deleteRecursively()
        val archive = checkNotNull(javaClass.getResourceAsStream(CHART)) { "The plugin carries no $CHART." }
        ZipInputStream(archive).use { zip ->
            generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.forEach { entry ->
                val file = File(directory, entry.name)
                check(file.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { entry.name }
                file.parentFile.mkdirs()
                file.outputStream().use { zip.copyTo(it) }
            }
        }
        return File(directory, "wow-service")
    }

    private fun write(objects: List<Rendered>, namespace: String, release: String) {
        val directory = outputDirectory.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        objects.forEach { File(directory, it.file).writeText(it.text) }
        File(directory, KUSTOMIZATION).writeText(
            buildString {
                appendLine("# Generated by the Wow Gradle plugin ($name): the wow-service chart ${chartVersion.get()},")
                appendLine("# rendered with Helm ${helmVersion.get()} for $release in $namespace. Overlay it with")
                appendLine("# kubectl apply -k; do not edit it, and do not move it to another namespace, which")
                appendLine("# the chart derives from: render for that namespace instead.")
                appendLine("apiVersion: kustomize.config.k8s.io/v1beta1")
                appendLine("kind: Kustomization")
                appendLine("namespace: $namespace")
                appendLine("resources:")
                objects.map { it.file }.sorted().forEach { appendLine("  - $it") }
            },
        )
    }

    /** One object Helm rendered: its [file], `<kind>-<name>.yaml`, and its [text]. */
    data class Rendered(val file: String, val text: String)

    companion object {
        private const val CHART = "/me/ahoo/wow/gradle/helm/wow-service.zip"
        private const val KUSTOMIZATION = "kustomization.yaml"
        private val KIND = Regex("""(?m)^kind:\s*(\S+)\s*$""")
        private val NAME = Regex("""(?m)^metadata:\s*\n(?:[ ]{2}.*\n)*?[ ]{2}name:\s*"?([^"\s]+)"?\s*$""")
        private val SERVICE = Regex("""(?m)^service:\s*\n(?:[ ]{2}.*\n)*?[ ]{2}name:\s*"([^"]+)"\s*$""")

        /** The objects of Helm's [output]: its documents, each with a kind, named after their kind and name. */
        fun objectsOf(output: String): List<Rendered> = output.split(Regex("""(?m)^---\s*$"""))
            .map { it.trim('\n') }
            .filter { document -> document.lines().any { it.isNotBlank() && !it.startsWith("#") } }
            .map { document ->
                val kind = checkNotNull(KIND.find(document)) { "Helm rendered an object without a kind:\n$document" }
                val name = checkNotNull(NAME.find(document)) { "Helm rendered an object without a name:\n$document" }
                Rendered("${kind.groupValues[1].lowercase()}-${name.groupValues[1]}.yaml", document + "\n")
            }

        /** The service's name in the generated [values]: the chart's release, after which it names every object. */
        fun serviceOf(values: File): String = SERVICE.find(values.readText())?.groupValues?.get(1)
            ?: throw GradleException("$values names no service: generateWowHelmValues writes service.name.")
    }
}
