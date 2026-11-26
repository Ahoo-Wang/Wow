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

import me.ahoo.test.asserts.assert
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [GenerateKubernetes]'s action in process, and its place in `assemble` ([KubernetesRole]), with a Helm of the test: a
 * script that answers `version` as the pinned Helm does and `template` with what [rendered] holds, or refuses.
 */
class GenerateKubernetesTest {
    @TempDir
    lateinit var directory: File

    private val project: Project by lazy {
        wowProject(File(directory, "shop"), kotlin = true, id = WowPlugin.SERVICE_ID)
    }

    /** A Helm that renders [rendered] on stdout, or refuses with [refusal] on stderr. */
    private fun helm(rendered: String = OUTPUT, refusal: String? = null): File {
        val output = File(directory, "rendered.yaml").apply { writeText(rendered) }
        val template = refusal?.let { "echo \"$it\" >&2; exit 1" } ?: "cat '${output.path}'"
        return File(directory, "helm").apply {
            writeText(
                "#!/bin/sh\nif [ \"$1\" = version ]; then echo ${HelmRelease.VERSION}; exit 0; fi\n" +
                    "echo \"$@\" > '${File(directory, "arguments").path}'\n$template\n",
            )
            setExecutable(true)
        }
    }

    /** The service's task, its values and namespace set, rendering with [helm]. */
    private fun task(helm: File, values: Boolean = true, namespace: Boolean = true): GenerateKubernetes {
        val extension = project.extensions.getByType(WowServiceExtension::class.java)
        val deployment = File(directory, "values.yaml").apply { writeText("image:\n  repository: shop\n") }
        extension.kubernetes.values.setFrom(listOfNotNull(deployment.takeIf { values }))
        extension.kubernetes.namespace.set("shop".takeIf { namespace })
        val generated = File(directory, "generated.yaml").apply { writeText("service:\n  name: \"shop-service\"\n") }
        return (project.tasks.getByName(WowPlugin.KUBERNETES_TASK) as GenerateKubernetes).also {
            it.generatedValues.set(generated)
            it.helm.set(
                project.gradle.sharedServices.registerIfAbsent("testHelm", HelmService::class.java) { spec ->
                    spec.parameters.executable.set(helm.path)
                },
            )
        }
    }

    @Test
    fun `the task should write each object and the kustomization, rendered for the namespace from the values in order`() {
        val task = task(helm())
        // The order is the contents' digests, which tell apart two files of one name.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(File(directory, "values.yaml").readBytes()).joinToString("") { "%02x".format(it) }
        task.valuesOrder.get().assert().containsExactly(digest)
        task.chartVersion.get().assert().isEqualTo(WowPlugin.version)
        task.helmVersion.get().assert().isEqualTo(HelmRelease.VERSION)
        task.render()
        val output = task.outputDirectory.get().asFile
        output.canonicalFile.assert().isEqualTo(File(directory, "shop/build/wow/k8s").canonicalFile)
        output.list()!!.toList().assert().containsExactlyInAnyOrder(
            "kustomization.yaml",
            "service-shop-service.yaml",
            "serviceaccount-shop-service.yaml",
        )
        File(output, "kustomization.yaml").readText().assert().contains(
            "the wow-service chart ${WowPlugin.version},",
            "for shop-service in shop.",
            "namespace: shop\nresources:\n  - service-shop-service.yaml\n  - serviceaccount-shop-service.yaml\n",
        )
        File(output, "service-shop-service.yaml").readText().assert()
            .startsWith("# Source: wow-service/templates/service.yaml")
        // The release is the service, the chart the plugin's, the generated values first and the deployment's after.
        File(directory, "arguments").readText().trim().assert().matches(
            "template shop-service .*/chart/wow-service --namespace shop " +
                "--values .*/generated.yaml --values .*/values.yaml",
        )
        File(task.temporaryDir, "chart/wow-service/Chart.yaml").isFile.assert().isTrue()
        // A second render leaves no object of the first that it no longer renders.
        File(output, "stale.yaml").writeText("kind: ConfigMap\n")
        task.render()
        File(output, "stale.yaml").exists().assert().isFalse()
    }

    @Test
    fun `a refusal of Helm should fail the task with Helm's message`() {
        val task = task(helm(refusal = "values don't meet the specifications of the schema(s)"))
        assertThrows<GradleException> { task.render() }.message.assert().isEqualTo(
            "Helm refused to render the wow-service chart: values don't meet the specifications of the schema(s)",
        )
    }

    @Test
    fun `a render without values or without a namespace should fail naming the line to add`() {
        assertThrows<GradleException> { task(helm(), values = false).render() }
            .message.assert().contains("wow { kubernetes { values.from(\"deploy/<service>/values.yaml\") } }")
        assertThrows<GradleException> { task(helm(), namespace = false).render() }
            .message.assert().contains("set wow { kubernetes { namespace = \"<namespace>\" } }")
    }

    @Test
    fun `a values file that does not exist should fail naming it and the line to correct`() {
        val task = task(helm())
        val missing = File(directory, "deploy/valeus.yaml")
        task.values.from(missing)
        assertThrows<GradleException> { task.valuesOrder.get() }.message.assert().isEqualTo(
            "wow { kubernetes { values.from(…) } } names \"${missing.path}\", which does not exist: correct the path " +
                "in values.from(…), or remove it.",
        )
    }

    @Test
    fun `assemble should render only when the build names the deployment's values`() {
        val assemble = project.tasks.getByName("assemble")
        fun dependsOnManifests() =
            assemble.taskDependencies.getDependencies(assemble).any { it.name == WowPlugin.KUBERNETES_TASK }
        dependsOnManifests().assert().isFalse()
        project.extensions.getByType(WowServiceExtension::class.java).kubernetes {
            it.values.from(File(directory, "values.yaml"))
        }
        dependsOnManifests().assert().isTrue()
    }

    private companion object {
        val OUTPUT = """
            ---
            # Source: wow-service/templates/serviceaccount.yaml
            apiVersion: v1
            kind: ServiceAccount
            metadata:
              name: shop-service
            ---
            # Source: wow-service/templates/service.yaml
            apiVersion: v1
            kind: Service
            metadata:
              name: shop-service
        """.trimIndent() + "\n"
    }
}
