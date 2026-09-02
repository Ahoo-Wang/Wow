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

package me.ahoo.wow.configuration

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

class WowResourceLocatorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `working directory resource should use unified path`() {
        val file = tempDir.resolve("wow/query-schema/sales.order.snapshot.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "working")

        val resource = WowResourceLocator(configDirectory = tempDir)
            .findWorkingDirectory("query-schema", "sales.order.snapshot")

        resource.assert().isNotNull()
        resource!!.location.assert().isEqualTo(file.toString())
        resource.readText().assert().isEqualTo("working")
    }

    @Test
    fun `classpath resources should use unified path and stable order`() {
        val firstRoot = tempDir.resolve("a")
        val secondRoot = tempDir.resolve("z")
        val relative = "META-INF/wow/query-schema/sales.order.snapshot.json"
        listOf(firstRoot to "first", secondRoot to "second").forEach { (root, value) ->
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, value)
        }

        URLClassLoader(arrayOf(secondRoot.toUri().toURL(), firstRoot.toUri().toURL()), null).use { loader ->
            WowResourceLocator(configDirectory = tempDir, classLoader = loader)
                .findClasspath("query-schema", "sales.order.snapshot")
                .map(WowResource::readText)
                .assert().containsExactly("first", "second")
        }
    }

    @Test
    fun `invalid feature and resource keys should be rejected`() {
        val locator = WowResourceLocator(configDirectory = tempDir)
        listOf("", ".", "..", "query/schema", "query\\schema").forEach { invalid ->
            assertThrows<IllegalArgumentException> {
                locator.findWorkingDirectory(invalid, "sales.order.snapshot")
            }
            assertThrows<IllegalArgumentException> {
                locator.findWorkingDirectory("query-schema", invalid)
            }
        }
    }

    @Test
    fun `classpath listing failure should retain its cause`() {
        val expected = IOException("unavailable")
        val loader = object : ClassLoader(null) {
            override fun getResources(name: String): java.util.Enumeration<java.net.URL> = throw expected
        }

        val actual = assertThrows<IllegalStateException> {
            WowResourceLocator(configDirectory = tempDir, classLoader = loader)
                .findClasspath("query-schema", "sales.order.snapshot")
        }

        actual.cause.assert().isSameAs(expected)
    }

    @Test
    fun `resource read failure should include location and retain cause`() {
        val file = tempDir.resolve("wow/elasticsearch/wow.order.snapshot.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "{}")
        val expected = IOException("unreadable")
        val resource = WowResourceLocator(
            configDirectory = tempDir,
            pathReader = { throw expected },
        ).findWorkingDirectory("elasticsearch", "wow.order.snapshot")!!

        val actual = assertThrows<IllegalStateException>(resource::readText)

        actual.message.assert().contains(resource.location)
        actual.cause.assert().isSameAs(expected)
    }
}
