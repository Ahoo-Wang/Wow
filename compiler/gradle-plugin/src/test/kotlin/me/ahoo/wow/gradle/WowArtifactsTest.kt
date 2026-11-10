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
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The artifacts each role adds ([WowArtifacts]): the tools dependency and the BOM. */
class WowArtifactsTest {
    @TempDir
    lateinit var directory: File

    /** A project of [id], the service plugin by default: the domain role on its sources, and the service's. */
    private fun project(kotlin: Boolean = true, id: String = WowPlugin.SERVICE_ID): Project =
        wowProject(directory, kotlin, id)

    @Test
    fun `a declared tools dependency should replace the default`() {
        val project = project()
        project.dependencies.add(WowPlugin.TOOLS_CONFIGURATION, project.files("tools.jar"))
        project.configurations.getByName(WowPlugin.TOOLS_CONFIGURATION).incoming.dependencies
            .filterIsInstance<ExternalModuleDependency>().assert().isEmpty()
    }

    @Test
    fun `each role should add the BOM of the plugin's version and its Wow module, unless declared`() {
        fun declared(project: Project, configuration: String) =
            project.configurations.getByName(configuration).incoming.dependencies
                .filter { it.group == "me.ahoo.wow" }.map { "${it.group}:${it.name}:${it.version}" }
        val bom = "me.ahoo.wow:wow-bom:${WowPlugin.version}"
        declared(project(id = WowPlugin.ID), "implementation").assert()
            .containsExactlyInAnyOrder("me.ahoo.wow:wow-kernel:null", bom)
        val pinned = project(id = WowPlugin.ID)
        pinned.dependencies.add("implementation", "me.ahoo.wow:wow-kernel:10.0.0")
        declared(pinned, "implementation").assert().containsExactlyInAnyOrder("me.ahoo.wow:wow-kernel:10.0.0", bom)
        // Declared in another configuration the module compiles against, the module is the module's own.
        val implemented = project(id = WowPlugin.ID)
        implemented.dependencies.add("compileOnly", implemented.files("libs/wow-kernel-10.0.0.jar"))
        declared(implemented, "implementation").assert().containsExactly(bom)
    }
}
