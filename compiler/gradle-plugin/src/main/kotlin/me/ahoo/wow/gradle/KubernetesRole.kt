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

import me.ahoo.wow.gradle.WowPlugin.Companion.MANIFESTS_TASK
import org.gradle.api.Project
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.tasks.TaskProvider
import java.util.concurrent.Callable

/**
 * The service's Kubernetes manifests: `generateWowManifests` ([GenerateManifests]) renders the plugin's `wow-service`
 * chart with the build's Helm ([HelmService]) from the values [helmValues] writes and the deployment's
 * ([WowKubernetesExtension]). It is part of `assemble` when the build names the deployment's values, and nothing is
 * fetched until it runs.
 */
internal object KubernetesRole {
    fun kubernetes(project: Project, extension: WowServiceExtension, helmValues: TaskProvider<GenerateHelmValues>) {
        val helm = HelmService.register(project)
        val kubernetes = extension.kubernetes
        val manifests = project.tasks.register(MANIFESTS_TASK, GenerateManifests::class.java) { task ->
            task.group = GROUP_NAME
            task.description = "Renders the service's Kubernetes manifests from the wow-service chart into a Kustomize " +
                "base of plain YAML, build/wow/k8s."
            task.generatedValues.set(helmValues.flatMap { it.outputFile })
            task.values.from(kubernetes.values)
            task.namespace.set(kubernetes.namespace)
            task.chartVersion.set(WowPlugin.version)
            task.helmVersion.set(HelmRelease.VERSION)
            task.helm.set(helm)
            task.usesService(helm)
            task.outputDirectory.set(project.layout.buildDirectory.dir("wow/k8s"))
        }
        // In assemble only when the build names the deployment's values: without them there is nothing to render.
        project.tasks.named(BasePlugin.ASSEMBLE_TASK_NAME) { assemble ->
            // A Callable, resolved when the task graph is built: dependsOn takes no Provider of a list of tasks.
            assemble.dependsOn(Callable { if (kubernetes.values.isEmpty) emptyList() else listOf(manifests) })
        }
    }

    /** The group of the service role's tasks ([ServiceRole]). */
    private const val GROUP_NAME = "wow"
}
