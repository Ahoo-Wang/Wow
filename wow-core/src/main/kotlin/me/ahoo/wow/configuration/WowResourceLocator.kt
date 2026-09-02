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

import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

class WowResource internal constructor(
    val location: String,
    private val reader: () -> String,
) {
    @Suppress("TooGenericExceptionCaught")
    fun readText(): String = try {
        reader()
    } catch (error: Exception) {
        throw IllegalStateException("Unable to read Wow resource [$location].", error)
    }
}

class WowResourceLocator(
    private val configDirectory: Path = Path.of("config"),
    private val classLoader: ClassLoader =
        Thread.currentThread().contextClassLoader ?: WowResourceLocator::class.java.classLoader,
    private val pathReader: (Path) -> String = Files::readString,
) {
    fun findWorkingDirectory(feature: String, resourceKey: String): WowResource? {
        val file = configDirectory.resolve("wow").resolve(relativePath(feature, resourceKey))
        if (Files.notExists(file)) return null
        return WowResource(file.toString()) { pathReader(file) }
    }

    @Suppress("TooGenericExceptionCaught")
    fun findClasspath(feature: String, resourceKey: String): List<WowResource> {
        val resourcePath = "META-INF/wow/${relativePath(feature, resourceKey)}"
        val resources = try {
            Collections.list(classLoader.getResources(resourcePath)).sortedBy(URL::toExternalForm)
        } catch (error: Exception) {
            throw IllegalStateException("Unable to list Wow resources [$resourcePath].", error)
        }
        return resources.map { resource ->
            WowResource(resource.toExternalForm()) {
                resource.openStream().bufferedReader().use { it.readText() }
            }
        }
    }

    private fun relativePath(feature: String, resourceKey: String): String {
        listOf(feature, resourceKey).forEach { segment ->
            require(
                segment.isNotBlank() && '/' !in segment && '\\' !in segment &&
                    segment != "." && segment != ".."
            ) { "Wow resource path segment is invalid: [$segment]." }
        }
        return "$feature/$resourceKey.json"
    }
}
