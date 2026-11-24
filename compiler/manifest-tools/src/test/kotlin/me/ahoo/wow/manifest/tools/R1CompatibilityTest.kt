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

package me.ahoo.wow.manifest.tools

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.BASELINE
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.addItem
import org.junit.jupiter.api.Test

/**
 * Deep review r1 (compiler, the compatibility check): changes the check misses or misreads. Each test states the
 * expected verdict and fails on `ac58da98ac`.
 */
class R1CompatibilityTest {
    private fun changes(baseline: Manifest, current: Manifest) =
        ManifestCompatibility.compare(baseline, current).changes.map { it.toString() }

    /**
     * `AggregateCompatibility.compareCommand` (`AggregateCompatibility.kt:188`) never compares `idProperty`, the
     * body field clients read the aggregate id from (`CommandIndex.idPropertyOf`, the TypeScript client, the HTTP
     * reference). Moving `@AggregateId` to another field is reported as no change at all.
     */
    @Test
    fun `a command whose aggregate id moves to another field should be a breaking change`() {
        val baseline = BASELINE.addItem { it.copyWith(idProperty = "cartId") }
        val moved = BASELINE.addItem { it.copyWith(idProperty = "ownerCartId") }
        changes(baseline, moved).filter { it.startsWith("BREAKING") }.assert().isNotEmpty()
    }

    /**
     * A rename keeps the old name as an alias. `ManifestCompatibility` looks the old name up among the aliases as
     * written (`ManifestCompatibility.kt:160`), while `AliasCheck` and the kernel take the full name
     * `shop.cart.AddItem` for `AddItem`: the same rename, aliased by its full name, is reported "command removed".
     */
    @Test
    fun `a command renamed with its full name as an alias should not be removed`() {
        val renamed = BASELINE.addItem { it.copyWith(name = "PutItem", aliases = listOf("shop.cart.AddItem")) }
        changes(BASELINE, renamed).filter { it.startsWith("BREAKING") }.assert().isEmpty()
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(
        JsonSubTypes.Type(Choice.First::class, name = "first"),
        JsonSubTypes.Type(Choice.Second::class, name = "second"),
    )
    sealed interface Choice {
        data class First(val first: String) : Choice

        data class Second(val second: Int) : Choice
    }

    data class Required(val choice: Choice)

    data class Optional(val choice: Choice? = null)

    class State {
        var count: Int = 0
    }

    /**
     * Making a command's field optional accepts every body it accepted. For a polymorphic field, victools writes the
     * nullable form `anyOf: [{type: null}, X]`, and `SchemaShapes.nullable` (`SchemaShapes.kt:139`) normalizes it only
     * when X has a `type`; the polymorphic definition is a bare `anyOf` of its subtypes, so each old subtype is
     * matched alternative by alternative and the check reports them "no longer accepted".
     */
    @Test
    fun `a polymorphic command field made optional should not be a breaking change`() {
        val introspected = SchemaIntrospector(javaClass.classLoader).complete(
            Manifest(
                service = ServiceInfo("poly-service", "10.0.1"),
                contexts = listOf(
                    ContextManifest(
                        "poly",
                        aggregates = listOf(
                            AggregateManifest(
                                "choice",
                                state = StateManifest(Manifest.schemaRef(State::class.java.name)),
                                commands = listOf(
                                    CommandManifest("Required", schema = Manifest.schemaRef(Required::class.java.name)),
                                    CommandManifest("Optional", schema = Manifest.schemaRef(Optional::class.java.name)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val required = introspected.defs.getValue(Required::class.java.name)
        val optional = introspected.defs.getValue(Optional::class.java.name)
        fun withAddItem(schema: Any?) = BASELINE.copyWith(
            defs = BASELINE.defs + introspected.defs + ("shop.AddItem" to schema)
        )
        changes(withAddItem(required), withAddItem(optional)).filter { it.startsWith("BREAKING") }.assert()
            .withFailMessage {
                "required $required, optional $optional: ${changes(
                    withAddItem(required),
                    withAddItem(optional)
                )}"
            }
            .isEmpty()
    }

    /**
     * A schema compared with itself is no change. For an input, `compareAlternatives` matches each old alternative with
     * the first new one it is compatible with, not the identical one: of `anyOf: [number, integer]`, the old `number`
     * finds the new `number` and the old `integer` finds the new `number` too (a number accepts an integer), so the new
     * `integer` is left over and reported "alternative added". Fails on `ac58da98ac`.
     */
    @Test
    fun `an anyOf of number and integer compared with itself should be no change`() {
        val schema = mapOf("anyOf" to listOf(mapOf("type" to "number"), mapOf("type" to "integer")))
        SchemaCompatibility(mapOf("t" to schema), mapOf("t" to schema))
            .compare("s", "#/\$defs/t", "#/\$defs/t", false).map { it.toString() }.assert().isEmpty()
    }
}
