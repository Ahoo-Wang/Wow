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

package me.ahoo.wow.runtime.admission

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.result.CommandResult
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** What a validator sees of a command ([CommandCandidate]), and that it is built once, and only for a validator. */
class CommandCandidateTest : CommandGatewayFixtures() {
    @Test
    fun `a validator sees the command's type, body, target, scope, operator and the request's caller`(): Unit =
        runBlocking {
            val seen = CopyOnWriteArrayList<CommandCandidate>()
            val recording = CommandValidator { command ->
                seen += command
                emptyList()
            }
            gateway(validators = listOf(recording)).execute(request()).status
                .assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            seen.single().let {
                it.type.assert().isEqualTo(model.commandType(AddItem::class.java))
                (it.body as AddItem).sku.assert().isEqualTo("apple")
                it.ref.assert().isEqualTo(model.type.ref(TENANT, "cart-1"))
                it.scope.assert().isEqualTo(alice.scope)
                it.operator.assert().isEqualTo(alice.toOperator())
                it.caller!!.subject.assert().isEqualTo("alice")
            }
        }

    @Test
    fun `no validator builds no candidate, and several share one`() {
        var built = 0
        val candidate = CommandCandidate.of(
            model.commandType(AddItem::class.java),
            AddItem("apple"),
            model.type.ref(TENANT, "cart-1"),
            Scope.NONE,
            null,
            null,
        )
        emptyList<CommandValidator>().violationsOf {
            built++
            candidate
        }.assert().isEmpty()
        built.assert().isZero()
        val validators = listOf(CommandValidator { emptyList() }, CommandValidator { emptyList() })
        validators.violationsOf {
            built++
            candidate
        }.assert().isEmpty()
        built.assert().isEqualTo(1)
    }

    @Test
    fun `a candidate compares by its values, and a copy keeps what it is not given`() {
        val candidate = CommandCandidate.of(
            model.commandType(AddItem::class.java),
            AddItem("apple"),
            model.type.ref(TENANT, "cart-1"),
            alice.scope,
            alice.toOperator(),
            alice,
        )
        candidate.assert().isEqualTo(candidate.copyWith())
        candidate.hashCode().assert().isEqualTo(candidate.copyWith().hashCode())
        candidate.copyWith(caller = null).caller.assert().isNull()
        candidate.copyWith(caller = null).assert().isNotEqualTo(candidate)
        candidate.toString().assert().contains("CommandCandidate(", "caller=")
    }
}
