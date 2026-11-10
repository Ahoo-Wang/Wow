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

@file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

package me.ahoo.wow.manifest.tools

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryManifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.copyWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The generated schemas of polymorphic classes (`@JsonTypeInfo`): each subtype an `allOf` of its own schema and its
 * discriminator, whose members, and the definitions they refer to, are compared like any other schema.
 */
class PolymorphicSchemaCompatibilityTest {
    data class Payload(val secret: String)

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(
        JsonSubTypes.Type(Choice.First::class, name = "first"),
        JsonSubTypes.Type(Choice.Second::class, name = "second"),
    )
    sealed interface Choice {
        data class First(val payload: Payload) : Choice

        data class Second(val inner: Inner) : Choice
    }

    /** A polymorphic class inside a subtype of another. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(
        JsonSubTypes.Type(Inner.Leaf::class, name = "leaf"),
        JsonSubTypes.Type(Inner.Node::class, name = "node")
    )
    sealed interface Inner {
        data class Leaf(val payload: Payload) : Inner

        /** A recursive subtype: a node of nodes. */
        data class Node(val children: List<Inner>) : Inner
    }

    class State {
        var choice: Choice = Choice.First(Payload("secret"))
    }

    @TempDir
    lateinit var directory: File

    private val manifest = SchemaIntrospector(javaClass.classLoader).complete(
        Manifest(
            service = ServiceInfo("polymorphic-service", "10.0.1"),
            contexts = listOf(
                ContextManifest(
                    "poly",
                    aggregates = listOf(
                        AggregateManifest(
                            "choice",
                            state = StateManifest(Manifest.schemaRef(State::class.java.name)),
                            queries = listOf(QueryManifest("snapshot")),
                        ),
                    ),
                ),
            ),
        ),
    )

    /** The manifest with [Payload]'s required `secret` gone, the references to it unchanged. */
    private fun withoutSecret(): Manifest {
        val key = Payload::class.java.name

        @Suppress("UNCHECKED_CAST")
        val payload = manifest.defs.getValue(key) as Map<String, Any?>
        (payload["properties"] as Map<*, *>).keys.assert().containsExactly("secret")
        val changed = payload + mapOf("properties" to emptyMap<String, Any?>(), "required" to emptyList<String>())
        return manifest.copyWith(defs = manifest.defs + (key to changed))
    }

    @Test
    fun `a generated polymorphic schema should be its subtypes, each an allOf`() {
        manifest.defs.toString().assert().contains("allOf")
    }

    @Test
    fun `a polymorphic schema compared with itself should be no change, recursive subtypes included`() {
        ManifestCompatibility.compare(manifest, manifest).changes.assert().isEmpty()
    }

    @Test
    fun `a breaking change behind an allOf member's reference should break, nested polymorphism included`() {
        val report = ManifestCompatibility.compare(manifest, withoutSecret())
        // Payload is read by the first subtype and by the leaf of the second's nested polymorphic class.
        report.breaking.map { it.path }.assert().containsExactlyInAnyOrder(
            "contexts[poly].aggregates[choice].state.choice.anyOf[0]",
            "contexts[poly].aggregates[choice].state.choice.anyOf[1]",
        )
        report.breaking.map { it.description }.assert().allMatch { it.startsWith("alternative no longer accepted") }
    }

    @Test
    fun `a change to a recursive subtype's definition should break`() {
        val key = "${Inner.Node::class.java.name}-1"

        @Suppress("UNCHECKED_CAST")
        val node = manifest.defs.getValue(key) as Map<String, Any?>
        (node["properties"] as Map<*, *>).keys.assert().containsExactly("children")
        val changed = node + mapOf("properties" to emptyMap<String, Any?>(), "required" to emptyList<String>())
        // The node is written as a reference to its class's schema beside its discriminator: both hold.
        ManifestCompatibility.compare(manifest, manifest.copyWith(defs = manifest.defs + (key to changed)))
            .breaking.map { it.path }.assert()
            .containsExactly("contexts[poly].aggregates[choice].state.choice.anyOf[1]")
    }

    @Test
    fun `a stable patch should refuse to record a baseline that breaks a polymorphic schema`() {
        val baselineFile = File(directory, "baseline.json")
        val currentFile = File(directory, "current.json")
        val reportFile = File(directory, "report.txt")
        Baseline.of(manifest, 10).write(baselineFile)
        ManifestJson.write(withoutSecret(), currentFile)
        assertThrows<IllegalStateException> {
            ManifestTools.recordBaseline(currentFile, baselineFile, false, reportFile, "poly")
        }.message.assert().contains("refuses to record", "state.choice.anyOf[0]")
        Baseline.read(baselineFile).manifest.defs.assert().isEqualTo(manifest.defs)
    }
}
