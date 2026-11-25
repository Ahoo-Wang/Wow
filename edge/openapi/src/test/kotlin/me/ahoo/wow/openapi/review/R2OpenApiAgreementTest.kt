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

package me.ahoo.wow.openapi.review

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.openapi.ManifestOpenApi
import me.ahoo.wow.openapi.OpenApiChecks
import me.ahoo.wow.openapi.OpenApiFixtures.MANIFEST
import me.ahoo.wow.openapi.OpenApiFixtures.components
import me.ahoo.wow.openapi.OpenApiFixtures.content
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test

/**
 * Review r2 (REST and OpenAPI): the document tells a client two things the server contradicts.
 *
 * - `wow.AggregateRef` requires `id` as a string (`WowSchemas.kt:296-303`), which `CommandResult.ref` and a problem's
 *   `result.ref` point at. A creation that names no id and is refused before admission chooses one (a validator's
 *   `wow.command.invalid`, an authorizer's 403, `CommandGateway.kt:560-570`) answers its result with `"id": null`,
 *   as `WowJson` writes an unassigned ref (`WowJson.kt:66-67`).
 * - A command's event stream always ends with the event `end`, `{"reason":"complete"}` (`CommandHandler.kt:201-204`;
 *   D480 ⑤: a stream without it is `wow.command.unknown`), but the command's `200` lists only the stages as its events,
 *   each one a `CommandResult` (`OpenApiPaths.kt:365-373`); the query streams list their `end` and its data.
 */
class R2OpenApiAgreementTest {
    private val document = ManifestOpenApi.write(MANIFEST)

    @Test
    fun `an unassigned aggregate ref, as a refused creation's result holds it, should match its schema`() {
        val ref = WowJson.mapper.writeValueAsString(AggregateType("shop", "cart").unassigned("(0)"))
        ref.assert().contains("\"id\":null")
        OpenApiChecks.validate(document, "wow.AggregateRef", ref).assert().isEmpty()
    }

    @Test
    fun `a command's event stream should list the end event it always ends with`() {
        @Suppress("UNCHECKED_CAST")
        val stream = content(document.components("responses")["wow.command.200"] as Map<String, Any?>)
            .getValue("text/event-stream") as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val events = stream["x-wow-events"] as Map<String, Any?>
        (events["event"] as List<*>).assert().contains("end")
        events.keys.assert().contains("end")
    }
}
