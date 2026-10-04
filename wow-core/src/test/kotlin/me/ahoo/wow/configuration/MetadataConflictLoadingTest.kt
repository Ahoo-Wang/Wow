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
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Two `META-INF/wow-metadata.json` resources on one classpath, as a jar of the api module and one of the domain
 * module would give.
 */
class MetadataConflictLoadingTest {

    private fun aggregate(type: String, tenantId: String? = null): String {
        val tenant = tenantId?.let { ""","tenantId":"$it"""" }.orEmpty()
        return """{"contexts":{"shop":{"aggregates":{"cart":{"type":"$type"$tenant}}}}}"""
    }

    private fun classpath(root: Path, vararg jsons: String): List<URL> {
        val roots = jsons.mapIndexed { index, json ->
            root.resolve("jar-$index").also {
                it.resolve("META-INF").createDirectories()
                it.resolve(WOW_METADATA_RESOURCE_NAME).writeText(json)
            }
        }
        val loader = URLClassLoader(roots.map { it.toUri().toURL() }.toTypedArray(), null)
        return loader.getResources(WOW_METADATA_RESOURCE_NAME).toList().also {
            it.assert().hasSize(jsons.size)
        }
    }

    @Test
    fun `agreeing resources merge`(@TempDir root: Path) {
        val metadata = MetadataSearcher.loadMetadata(
            classpath(root, aggregate("shop.Cart", "tenant-a"), aggregate("shop.Cart"))
        )
        metadata.contexts["shop"]!!.aggregates["cart"]!!.tenantId.assert().isEqualTo("tenant-a")
    }

    @Test
    fun `conflicting static tenants fail naming both resources`(@TempDir root: Path) {
        val resources = classpath(root, aggregate("shop.Cart", "tenant-a"), aggregate("shop.Cart", "tenant-b"))
        val error = assertThrows<IllegalStateException> {
            MetadataSearcher.loadMetadata(resources)
        }
        error.message.assert().contains(
            resources[0].toString(),
            resources[1].toString(),
            "[cart]",
            "tenant-a",
            "tenant-b"
        )
    }

    @Test
    fun `conflicting types fail naming both resources`(@TempDir root: Path) {
        val resources = classpath(root, aggregate("shop.Cart"), aggregate("shop.OtherCart"))
        val error = assertThrows<IllegalStateException> {
            MetadataSearcher.loadMetadata(resources)
        }
        error.message.assert().contains(resources[0].toString(), resources[1].toString(), "shop.OtherCart")
    }

    @Test
    fun `an unreadable resource is skipped`(@TempDir root: Path) {
        val metadata = MetadataSearcher.loadMetadata(classpath(root, aggregate("shop.Cart"), "{not json"))
        metadata.contexts["shop"]!!.aggregates.assert().containsKey("cart")
    }
}
