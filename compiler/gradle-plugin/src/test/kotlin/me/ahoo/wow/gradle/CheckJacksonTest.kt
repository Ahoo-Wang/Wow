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
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [CheckJackson] in a service built in-process: the class paths it checks, the module that asked for a Jackson, and the
 * minors of the `wow-spi` on the class path, which it reads from the jar.
 */
class CheckJacksonTest {
    @TempDir
    lateinit var directory: File

    private val repository by lazy { File(directory, "maven") }

    private fun pom(group: String, artifact: String, version: String, dependency: String = "") {
        val path = "${group.replace('.', '/')}/$artifact/$version/$artifact-$version"
        File(repository, "$path.pom").apply { parentFile.mkdirs() }.writeText(
            "<project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId><artifactId>$artifact</artifactId>" +
                "<version>$version</version><dependencies>$dependency</dependencies></project>",
        )
        File(repository, "$path.jar").writeText("")
    }

    /** A `wow-spi` jar that names [minors], or none when it has no resource. */
    private fun wowSpi(minors: String?): File = File(directory, "wow-spi-test.jar").apply {
        ZipOutputStream(outputStream()).use { zip ->
            if (minors != null) {
                zip.putNextEntry(ZipEntry(CheckJackson.RESOURCE))
                zip.write("minors=$minors\nmodules=tools.jackson.core:jackson-databind\n".toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun databind(version: String) = "<dependency><groupId>tools.jackson.core</groupId>" +
        "<artifactId>jackson-databind</artifactId><version>$version</version></dependency>"

    /**
     * A service that resolves jackson-databind [jackson], asked for at that version by `sample:asker` and by Jackson's
     * Kotlin module, and at an older one by `sample:older`, beside [wowSpi].
     */
    private fun service(jackson: String, wowSpi: File): CheckJackson {
        pom("tools.jackson.core", "jackson-databind", jackson)
        pom("tools.jackson.core", "jackson-databind", "3.0.0")
        pom("tools.jackson.module", "jackson-module-kotlin", jackson, databind(jackson))
        pom("sample", "asker", "1", databind(jackson))
        pom("sample", "older", "1", databind("3.0.0"))
        val version = WowPlugin.version
        pom("me.ahoo.wow", "wow-kernel", version)
        File(repository, "me/ahoo/wow/wow-bom/$version/wow-bom-$version.pom").apply { parentFile.mkdirs() }.writeText(
            "<project><modelVersion>4.0.0</modelVersion><groupId>me.ahoo.wow</groupId><artifactId>wow-bom" +
                "</artifactId><version>$version</version><packaging>pom</packaging></project>",
        )
        val shop: Project = ProjectBuilder.builder().withProjectDir(File(directory, "shop")).withName("shop").build()
        shop.extensions.extraProperties.set("kotlin.stdlib.default.dependency", "false")
        shop.pluginManager.apply(WowPlugin.SERVICE_ID)
        shop.pluginManager.apply("org.jetbrains.kotlin.jvm")
        shop.repositories.maven { it.url = repository.toURI() }
        shop.dependencies.add("implementation", "me.ahoo.wow:wow-kernel:$version")
        shop.dependencies.add("implementation", "tools.jackson.module:jackson-module-kotlin:$jackson")
        shop.dependencies.add("implementation", "sample:older:1")
        shop.dependencies.add("implementation", "sample:asker:1")
        shop.dependencies.add("implementation", shop.files(wowSpi))
        (shop as ProjectInternal).evaluate()
        return shop.tasks.getByName(WowPlugin.JACKSON_CHECK_TASK) as CheckJackson
    }

    @Test
    fun `a service's class paths at a minor wow-spi does not name should be refused, naming who asked`() {
        val check = service("3.0.3", wowSpi("3.1,3.2"))
        check.resolved.get().keys.assert().containsExactlyInAnyOrder("runtimeClasspath", "testRuntimeClasspath")
        val failure = assertThrows<GradleException> { check.check() }
        failure.message.assert().contains(
            "The runtimeClasspath of project ':' resolves Jackson 3.0.3, asked for by sample:asker:1, " +
                "and Wow's JSON is verified on Jackson 3.1 and 3.2 only.",
            "The testRuntimeClasspath of project ':' resolves Jackson 3.0.3, asked for by sample:asker:1",
        )
    }

    @Test
    fun `a service at a minor wow-spi names should pass`() {
        val check = service("3.2.3", wowSpi("3.1,3.2"))
        check.check()
        check.passed.get().asFile.isFile.assert().isTrue()
    }

    @Test
    fun `a wow-spi that names no minors should leave the class path unchecked`() {
        service("3.0.3", wowSpi(null)).check()
    }
}
