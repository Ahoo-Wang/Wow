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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AccessAttributeManifest.Type
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper

/** An aggregate's access attributes in the Manifest, and the rule of their names. */
class AccessAttributeManifestTest {
    private val mapper = jacksonObjectMapper()

    private val document = Manifests.cart(aggregate = "document").copyWith(
        access = listOf(
            AccessAttributeManifest("department", Type.STRING),
            AccessAttributeManifest("sharedWith", Type.STRING, multiple = true),
            AccessAttributeManifest("level", Type.INTEGER),
            AccessAttributeManifest("published", Type.BOOLEAN),
        ),
    )

    /** The Manifest of a document whose access attributes are [access] as written. */
    private fun withAccess(vararg access: Map<String, Any?>): Map<String, Any?> {
        val tree = ManifestTree.write(Manifests.fragment(aggregates = listOf(document)))
        val contexts = (tree["contexts"] as List<*>).map { context ->
            val map = context as Map<*, *>
            val aggregates = (map["aggregates"] as List<*>).map { (it as Map<*, *>) + ("access" to access.toList()) }
            map + ("aggregates" to aggregates)
        }
        return tree + ("contexts" to contexts)
    }

    @Test
    fun `an aggregate's access attributes should come back, and none and a single value be left out`() {
        val manifest = Manifests.fragment(aggregates = listOf(Manifests.cart(), document))
        val tree = ManifestTree.write(manifest)
        val aggregates = (((tree["contexts"] as List<*>).single() as Map<*, *>)["aggregates"] as List<*>)
            .map { it as Map<*, *> }
        aggregates[0].containsKey("access").assert().isFalse()
        aggregates[1]["access"].assert().isEqualTo(
            listOf(
                mapOf("name" to "department", "type" to "string"),
                mapOf("name" to "sharedWith", "type" to "string", "multiple" to true),
                mapOf("name" to "level", "type" to "integer"),
                mapOf("name" to "published", "type" to "boolean"),
            ),
        )
        val read = ManifestTree.read(
            mapper.readValue(mapper.writeValueAsString(tree), Map::class.java),
            ManifestTree.REFUSE
        )
        read.assert().isEqualTo(manifest)
        read.contexts.single().aggregates[1].access.assert().isEqualTo(document.access)
        document.access[1].toString()
            .assert().isEqualTo("AccessAttributeManifest(name=sharedWith, type=STRING, multiple=true)")
        document.copyWith(access = emptyList()).assert().isNotEqualTo(document)
    }

    @Test
    fun `an aggregate of an access attribute type a newer writer added is left out, and is told`() {
        val warnings = mutableListOf<String>()
        val read = ManifestTree.read(withAccess(mapOf("name" to "region", "type" to "geo-point"))) { warnings += it }
        read.contexts.single().aggregates.assert().isEmpty()
        warnings.assert().anyMatch { it.contains("type: geo-point is unknown to this reader") }
        warnings.assert().anyMatch { it.contains("(document) is left out") }
    }

    @Test
    fun `an access attribute whose name breaks the rule refuses the Manifest`() {
        listOf("Region", "region_code", "region-code", "owner", "space", "x".repeat(41)).forEach { name ->
            assertThrows<IllegalArgumentException> {
                ManifestTree.read(withAccess(mapOf("name" to name, "type" to "string")), ManifestTree.REFUSE)
            }.message.assert().contains("access attribute name \"$name\"")
        }
    }

    @Test
    fun `a name is lower camel case of letters and digits, at most 40 characters, and not built in`() {
        listOf("department", "orgPath", "level2", "a", "x".repeat(AccessAttributeName.MAX_LENGTH)).forEach {
            AccessAttributeName.isValid(it).assert().describedAs(it).isTrue()
            AccessAttributeName.problem(it).assert().isNull()
            QueryFieldNames.isSegment(it).assert().isTrue()
        }
        listOf("", "Department", "org_path", "org-path", "org.path", "2nd", "名字", "x".repeat(41)).forEach {
            AccessAttributeName.isValid(it).assert().describedAs(it).isFalse()
            AccessAttributeName.problem(it)!!.assert().contains("is not valid", "lower camel case")
        }
        AccessAttributeName.BUILT_IN.forEach {
            AccessAttributeName.isValid(it).assert().isFalse()
            AccessAttributeName.problem(it)!!.assert().contains("is a built-in attribute's")
        }
    }
}
