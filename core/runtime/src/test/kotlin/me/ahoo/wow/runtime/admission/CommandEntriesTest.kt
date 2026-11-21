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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every place in the runtime that hands a command to a context's aggregates (a wait coordinator's `forward`, `execute`
 * or `call`, or a node's forward endpoint), and the admission that decides its tenant first (#6556): a path added
 * without one fails here, and says which to call.
 */
class CommandEntriesTest {
    private val sources = File("src/main/kotlin/me/ahoo/wow/runtime")

    /** Each entry, `file: call`, with the admission its file holds it to, or why it needs none. */
    private val entries = mapOf(
        "admission/CommandGateway.kt: coordinator.call(" to GATEWAY,
        "admission/CommandGateway.kt: coordinator.execute(" to GATEWAY,
        "binding/DerivedCommands.kt: coordinator.forward(" to SERVICE,
        "command/CommandIntake.kt: coordinator.forward(" to SERVICE,
        "timer/ContextTimers.kt: coordinator.forward(" to SERVICE,
        // The startup commands of the context's built-in job instances, which the job runtime names in their tenant.
        "node/NodeLifecycle.kt: coordinator.forward(" to null,
        // A command another node of the service admitted, forwarded to the owner of its partition.
        "node/NodeAssembly.kt: endpoint?.forward(" to null,
    )

    @Test
    fun `every entry of a command into a context's aggregates is admitted by the one rule of its tenant`() {
        val found = sources.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            val path = file.relativeTo(sources).invariantSeparatorsPath
            ENTRY.findAll(file.readText()).map { "$path: ${it.value}" }
        }.toList()
        found.sorted().assert().describedAs(
            "A new entry decides its command's tenant by CallerAdmission.admit (a caller's request) or by " +
                "ServiceCommandAdmission (a command no caller sent), and is listed here.",
        ).isEqualTo(entries.keys.sorted())
        entries.forEach { (entry, admission) ->
            val file = File(sources, entry.substringBefore(':'))
            admission?.forEach { call -> file.readText().assert().describedAs("$entry admits by $call").contains(call) }
        }
    }

    private companion object {
        val ENTRY = Regex("""\b(coordinator|endpoint\?)\.(forward|execute|call)\(""")
        val GATEWAY = listOf("CallerAdmission.admit(")
        val SERVICE = listOf("admission.refusalOf(", "admission.assign(")
    }
}
