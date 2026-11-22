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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.StateManifest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** A stream declared to reveal its `DISPLAY` values must publish one (#6586). */
class RevealedDisplaysTest {
    @TempDir
    lateinit var directory: File

    private fun level(level: String) = mapOf("type" to "string", "x-wow-sensitive" to mapOf("level" to level))

    private fun objectOf(vararg properties: Pair<String, Any?>) =
        mapOf("type" to "object", "properties" to properties.toMap())

    /** The shop's card, its state [state] and its event [event], published as [stream] says. */
    private fun contract(
        state: Map<String, Any?>,
        event: Map<String, Any?>,
        stream: PublishedManifest = PublishedManifest("card", "wow.shop.card.state", revealsDisplay = true),
        extra: Map<String, Any?> = emptyMap(),
    ) = Manifest(
        contexts = listOf(
            ContextManifest(
                name = "shop",
                aggregates = listOf(
                    AggregateManifest(
                        name = "card",
                        state = StateManifest(Manifest.schemaRef("shop.Card")),
                        events = listOf(EventManifest("CardIssued", schema = Manifest.schemaRef("shop.CardIssued"))),
                    ),
                ),
                published = listOf(stream),
            ),
        ),
        defs = mapOf("shop.Card" to state, "shop.CardIssued" to event) + extra,
    )

    @Test
    fun `a revealing stream that publishes a DISPLAY value anywhere should pass`() {
        // In the state, in an event's body, and through a reference to a value type.
        RevealedDisplays.problems(contract(objectOf("phone" to level("display")), objectOf())).assert().isEmpty()
        RevealedDisplays.problems(contract(objectOf(), objectOf("phone" to level("display")))).assert().isEmpty()
        val referring = objectOf("phones" to mapOf("type" to "array", "items" to Manifest.schemaRef("shop.Phone")))
        RevealedDisplays.problems(contract(objectOf(), referring, extra = mapOf("shop.Phone" to level("display"))))
            .assert().isEmpty()
        // A stream that does not reveal is not checked.
        val masked = PublishedManifest("card", "wow.shop.card.state")
        RevealedDisplays.problems(contract(objectOf(), objectOf(), masked)).assert().isEmpty()
    }

    @Test
    fun `a revealing stream that publishes no DISPLAY value should be a problem, naming the aggregate`() {
        val confidential = objectOf("pin" to level("confidential"))
        RevealedDisplays.problems(contract(confidential, objectOf())).assert().containsExactly(
            "shop.card declares @AggregateRoot(revealsDisplay = true), but its published state and events hold no " +
                "DISPLAY value: remove revealsDisplay, or mark the value @Sensitive(SensitivityLevel.DISPLAY).",
        )
        // A state the stream leaves out reveals nothing, whatever it holds.
        val stateless = PublishedManifest("card", "t", includeState = false, revealsDisplay = true)
        RevealedDisplays.problems(contract(objectOf("phone" to level("display")), objectOf(), stateless)).single()
            .assert().contains("its published events hold (its state is not published) no DISPLAY value")
        // A reference that cycles back is followed once.
        val cyclic = objectOf("next" to Manifest.schemaRef("shop.Card"))
        RevealedDisplays.problems(contract(cyclic, objectOf())).assert().hasSize(1)
    }

    @Test
    fun `a domain module's contract declaring a stream that reveals nothing should fail its generation`() {
        val fragment = File(directory, "fragment/shop.json")
        ManifestJson.write(contract(objectOf(), objectOf()), fragment)
        assertThrows<IllegalStateException> {
            ManifestTools.generateContract(
                listOf(fragment),
                null,
                "com.example.shop.api",
                ":shop-domain",
                emptyMap(),
                File(directory, "domain/contract.json"),
                File(directory, "domain/contract-package.properties"),
            )
        }.message.assert()
            .startsWith("The contract of project ':shop-domain' declares streams that reveal nothing:\n- shop.card")
        File(directory, "domain/contract.json").exists().assert().isFalse()
    }
}
