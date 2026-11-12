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

package me.ahoo.wow.architecture

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The system commands paired with their classes by their owners' registries alone ([SystemCommandRules]). */
class SystemCommandRulesTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })

    @Test
    fun `production sources should read the system commands of two owners through SystemCommandGroups alone`() {
        SystemCommandRules.violations(repositoryRoot).assert().isEmpty()
        SystemCommandRules.ALLOWED.keys.forEach { File(repositoryRoot, it).isFile.assert().describedAs(it).isTrue() }
    }

    @Test
    fun `a file reading two owners, or pairing an owner's name with a class, should be caught`(@TempDir root: File) {
        val dir = "${ArchitectureRules.RUNTIME.dir}/src/main/kotlin"
        File(root, "$dir/Two.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                // Lifecycle.COMMANDS + Schedules.COMMANDS, in a comment only
                val names = Lifecycle.COMMANDS + ScheduleJobs.COMMANDS
                """.trimIndent(),
            )
        }
        File(root, "$dir/Paired.kt").writeText(
            "val bodies = mapOf(SnapshotRebuilds.REBUILD_SNAPSHOT to RebuildSnapshot::class.java)",
        )
        File(root, "$dir/One.kt").writeText("val type = Ledger.COMMANDS[name] ?: Ledger.nameOf(type)")
        SystemCommandRules.violations(root).assert().containsExactly(
            "$dir/Paired.kt:1: pairs SnapshotRebuilds.REBUILD_SNAPSHOT to RebuildSnapshot::class.java",
            "$dir/Two.kt: reads the commands of Lifecycle, ScheduleJobs: take a set of SystemCommandGroups",
        )
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow-test.repository.root"
    }
}
