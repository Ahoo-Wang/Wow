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

package me.ahoo.wow.http.problem

import me.ahoo.test.asserts.assert
import me.ahoo.wow.http.caller.CallPermits
import me.ahoo.wow.http.cartManifest
import me.ahoo.wow.kernel.KernelRejections
import me.ahoo.wow.kernel.schedule.ScheduleJobModel
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.query.QueryErrors
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.wait.ResultCodes
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * The framework's error table ([FrameworkErrors]) is the one source of truth of its codes (review R2.5-4): every code
 * a module produces is in it, and every code in it is produced. The snapshot `framework-errors.json`, which the
 * TypeScript client checks its `FrameworkErrorCodes` against, is the table's (`FrameworkErrorsTest` of wow-manifest).
 */
class FrameworkErrorTableTest {
    /** The classes that hold the codes the framework answers with, each a constant. */
    private val producers: List<Class<*>> = listOf(
        FrameworkCodes::class.java,
        ResultCodes::class.java,
        KernelRejections::class.java,
        ScheduleJobModel::class.java,
        QueryErrors::class.java,
        CallPermits::class.java,
    )

    /** The codes [type] declares: its static string constants shaped as a code (`wow.<area>.<name>`). */
    private fun codesOf(type: Class<*>): Map<String, String> = type.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .onEach { it.isAccessible = true }
        .associate { "${type.simpleName}.${it.name}" to it.get(null) as String }
        .filterValues { CODE.matches(it) }

    private val table: Set<String> = FrameworkErrors.ALL.mapTo(LinkedHashSet()) { it.code }

    @Test
    fun `every code a module produces should be in the table`() {
        val produced = producers.flatMap { codesOf(it).entries }
        produced.assert().isNotEmpty()
        produced.filterNot { it.value in table }.map { "${it.key} = ${it.value}" }.assert()
            .describedAs("Add each code to FrameworkErrors with its status, then the client's FrameworkErrorCodes.")
            .isEmpty()
    }

    @Test
    fun `every code in the table should be produced by a module`() {
        val produced = producers.flatMap { codesOf(it).values }.toSet()
        (table - produced).assert().describedAs("A code in FrameworkErrors that nothing answers with.").isEmpty()
    }

    @Test
    fun `the runtime's list should be every command code of the table`() {
        FrameworkCodes.ALL.assert().isEqualTo(
            table.filterNot { it.startsWith(QUERY_PREFIX) || it == FrameworkErrors.CALL_COMMAND_FORBIDDEN }.toSet(),
        )
    }

    @Test
    fun `the edge should answer every code of the table with its status`() {
        val statuses = ErrorStatuses(cartManifest())
        FrameworkErrors.ALL.forEach { statuses.status(it.code).assert().describedAs(it.code).isEqualTo(it.status) }
    }

    private companion object {
        val CODE = Regex("""wow\.[a-z]+\.[a-z-]+""")
        const val QUERY_PREFIX = "wow.query."
    }
}
