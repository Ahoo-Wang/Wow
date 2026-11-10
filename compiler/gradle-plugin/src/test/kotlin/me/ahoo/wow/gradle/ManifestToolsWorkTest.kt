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
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.tools.ManifestJson
import org.gradle.api.Project
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException

/** The work that reaches the Manifest tools ([ManifestToolsWork]): by name, rethrowing what they throw, in work actions. */
class ManifestToolsWorkTest {
    @TempDir
    lateinit var directory: File

    /** A project of [id], the service plugin by default: the domain role on its sources, and the service's. */
    private fun project(kotlin: Boolean = true, id: String = WowPlugin.SERVICE_ID): Project =
        wowProject(directory, kotlin, id)

    @Test
    fun `the work should reach the tools by name and rethrow what they throw`() {
        val output = File(directory, "manifest.json")
        ManifestToolsWork.call(
            javaClass.classLoader,
            "merge",
            emptyList<File>(),
            emptyMap<String, String>(),
            "shop",
            "default",
            null,
            output
        )
            .assert().isEqualTo(0)
        ManifestJson.read(output).service?.name.assert().isEqualTo("shop")
        // The tools' own exception, not the reflective wrapper.
        assertThrows<FileNotFoundException> {
            ManifestToolsWork.call(
                javaClass.classLoader,
                "completeFragment",
                File(directory, "missing.json"),
                output,
                emptyMap<String, String>()
            )
        }
    }

    @Test
    fun `the work actions should complete and merge fragments`() {
        val project = project()
        val skeleton = File(directory, "skeleton.json")
        ManifestJson.write(Manifest(), skeleton)
        val fragment = File(directory, "classes/${Manifest.FRAGMENT_DIRECTORY}/shop.json")
        val complete = project.objects.newInstance(ManifestToolsWork.CompleteFragment.Parameters::class.java).apply {
            this.fragment.set(skeleton)
            output.set(fragment)
            schemaOverrides.set(emptyMap())
        }
        object : ManifestToolsWork.CompleteFragment() {
            override fun getParameters() = complete
        }.execute()
        fragment.isFile.assert().isTrue()

        val manifest = File(directory, "manifest.json")
        val merge = project.objects.newInstance(ManifestToolsWork.Merge.Parameters::class.java).apply {
            classpath.from(File(directory, "classes"))
            defaultServiceName.set("shop")
            serviceVersion.set("1.0.0")
            output.set(manifest)
        }
        object : ManifestToolsWork.Merge() {
            override fun getParameters() = merge
        }.execute()
        ManifestJson.read(manifest).service.assert().isEqualTo(ServiceInfo("shop", "1.0.0"))
    }
}
