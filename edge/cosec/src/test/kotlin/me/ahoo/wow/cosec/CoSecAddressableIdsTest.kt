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

package me.ahoo.wow.cosec

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateIdLimits
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.AggregateIds
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestConflictException
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.ManifestMerger
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * #6594: every aggregate id admission takes is reachable behind CoSec's pipeline (8.0.0-beta.6), by its command routes,
 * its state read and its trace, and the ids its path check refuses, admission refuses too. The pipeline refuses an
 * encoded `/`, `;` or `\`, a segment of more than 256 characters decoded, and a path of more than 2,048 characters as
 * received; admission refuses an id of more than 512 bytes in UTF-8 itself, so its answer is Wow's, not the gateway's.
 */
class CoSecAddressableIdsTest {
    private val node = CoSecNode(callers = CoSecCallerResolver(CoSecCallerOptions()))

    /** [id] as a client writes it in a path: every character but the unreserved ones percent-encoded, as wow-client. */
    private fun segmentOf(id: String): String = URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20")

    /** Sends [method] [path] as alice of acme; a command with [body]. Answers the status and the JSON body. */
    private fun CoSecNode.send(method: String, path: String, body: String = "{}"): Pair<Int, JsonNode?> {
        val sent = if (method == "GET") {
            client.get().uri(URI.create(path)).header("Authorization", TOKEN).exchange()
        } else {
            client.post().uri(URI.create(path)).header("Authorization", TOKEN)
                .header("Content-Type", "application/json").bodyValue(body).exchange()
        }
        val result = sent.expectBody(String::class.java).returnResult()
        return result.status.value() to result.responseBody?.takeIf { it.isNotBlank() }?.let(json::readTree)
    }

    /** Sends the route [last] of the aggregate [id] under [root]: a command, the state read or its trace. */
    private fun CoSecNode.byId(id: String, last: String, method: String = "POST", root: String = "/shop/cart") =
        send(method, "$root/${segmentOf(id)}/$last", if (last == "add-item") """{"sku":"s"}""" else "{}")

    private fun open(id: String): Int = node.byId(id, "open-cart").first

    @Test
    fun `an id admission takes should be reachable by its commands, its state and its trace behind CoSec's pipeline`() {
        val taken = listOf(
            "plain", "pct%sign", "space inside", "q?mark", "hash#tag", "plus+sign", "é-accent", "nbsp inside",
            "dot.inside", "dots..inside",
            // The longest of each width: 256 characters of one or two bytes, 170 of three, 128 of four.
            "x".repeat(AggregateIds.MAX_LENGTH), "é".repeat(AggregateIds.MAX_LENGTH), "购".repeat(170),
            "购".repeat(170) + "xx", "😀".repeat(128),
        )
        taken.forEach { id ->
            AggregateIds.refused(id).assert().describedAs(id).isNull()
            node.byId(id, "open-cart").first.assert().describedAs(id).isEqualTo(OK)
            node.byId(id, "add-item").first.assert().describedAs(id).isEqualTo(OK)
            val (status, state) = node.byId(id, CommandRoutes.STATE, "GET")
            status.assert().describedAs(id).isEqualTo(OK)
            state!!["version"].asInt().assert().describedAs(id).isEqualTo(2)
            val (traced, trace) = node.byId(id, "${CommandRoutes.STATE}/${CommandRoutes.TRACE}", "GET")
            traced.assert().describedAs(id).isEqualTo(OK)
            trace!!["ref"]["id"].asString().assert().describedAs(id).isEqualTo(id)
            trace["commits"].size().assert().describedAs(id).isEqualTo(2)
        }
    }

    @Test
    fun `an id of more than 512 bytes in UTF-8 should pass CoSec's pipeline and be refused by Wow's admission`() {
        // 171 characters of three bytes, and 128 characters of two units (256 in all) of 514 bytes: the gateway takes
        // each path, admission refuses the id.
        listOf("购".repeat(171), "购".repeat(170) + "xxx", "😀".repeat(127) + "购购").forEach { id ->
            id.length.assert().describedAs(id).isLessThanOrEqualTo(AggregateIds.MAX_LENGTH)
            val (status, problem) = node.byId(id, "open-cart")
            status.assert().describedAs(id).isEqualTo(BAD_REQUEST)
            problem!!["code"].asString().assert().describedAs(id).isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
            problem["detail"].asString().assert().describedAs(id).contains("bytes in UTF-8, more than the 512 bytes")
            node.cosec.asked.map { it.action }.assert().describedAs(id).contains("shop:OpenCart")
            node.cosec.asked.clear()
        }
    }

    @Test
    fun `an id CoSec's pipeline refuses in a path should be one admission refuses`() {
        // Longer than 256 characters decoded, in one or two units each, and what the path check refuses encoded.
        val refused = listOf(
            "semi;colon", "slash/inside", "back\\slash", "x".repeat(AggregateIds.MAX_LENGTH + 1),
            "😀".repeat(128) + "x",
        )
        refused.forEach { id ->
            open(id).assert().describedAs(id).isEqualTo(BAD_REQUEST)
            node.cosec.asked.assert().describedAs(id).isEmpty()
            AggregateIds.refused(id).assert().describedAs(id).isNotNull()
        }
    }

    /**
     * The longest names a Manifest may give a route by id, its rest 512 characters, and the longest id encoded at
     * three characters a byte, 1,536: the path is the 2,048 characters CoSec takes, no more. Each name is a segment of
     * at most 256 characters, the most CoSec takes of one.
     */
    @Test
    fun `the longest route by id the build takes, with the longest id encoded, should be exactly the path CoSec takes`() {
        val aggregate = "a".repeat(AggregateIds.MAX_LENGTH)
        // The trace, "/" context "/" aggregate "/" id "/state/trace": 241 characters of context leave it 512.
        val rest = CommandRoutes.tracePath("", aggregate).replace(CommandRoutes.ID, "").length
        val context = "c".repeat(AggregateIdLimits.MAX_ROUTE_REST - rest)
        context.length.assert().isEqualTo(241)
        val longest = CoSecNode(CoSecCallerResolver(CoSecCallerOptions()), aggregate = aggregate, context = context)
        ManifestMerger.merge(listOf(ManifestFragment("a.json", cartManifest(aggregate, context))))
        // 512 bytes, each written as three characters: the spaces as %20, the characters of Chinese as three escapes.
        val id = "购".repeat(85) + "  " + "购".repeat(85)
        AggregateIdLimits.utf8Length(id).assert().isEqualTo(AggregateIds.MAX_BYTES)
        segmentOf(id).length.assert().isEqualTo(AggregateIds.MAX_BYTES * AggregateIdLimits.MAX_ESCAPE)
        val root = "/$context/$aggregate"
        longest.byId(id, "open-cart", root = root).first.assert().isEqualTo(OK)
        val trace = "$root/${segmentOf(id)}/state/trace"
        trace.length.assert().isEqualTo(AggregateIdLimits.MAX_PATH_LENGTH)
        longest.send("GET", trace).first.assert().isEqualTo(OK)
        // One character more of context: the build refuses the Manifest, and CoSec the path.
        val wider = "$context-"
        assertConflict(cartManifest(aggregate, wider))
        val over = CoSecNode(CoSecCallerResolver(CoSecCallerOptions()), aggregate = aggregate, context = wider)
        val overRoot = "/$wider/$aggregate"
        over.byId(id, "open-cart", root = overRoot).first.assert().isEqualTo(OK)
        over.byId(id, "state/trace", "GET", root = overRoot).first.assert().isEqualTo(BAD_REQUEST)
    }

    private fun assertConflict(manifest: Manifest) {
        val conflicts = runCatching { ManifestMerger.merge(listOf(ManifestFragment("a.json", manifest))) }
            .exceptionOrNull().let { (it as ManifestConflictException).conflicts }
        conflicts.single().assert().contains("/state/trace").contains("takes 513 characters")
    }

    private companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val TOKEN = "Bearer alice;acme"
    }
}
