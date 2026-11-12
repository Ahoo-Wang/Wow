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

/** The runtime's background loops go through `launchLoop` ([BackgroundLoopRules]). */
class BackgroundLoopRulesTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })

    @Test
    fun `the runtime should write no unbounded loop but the bounded ones listed`() {
        BackgroundLoopRules.unboundedLoops(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `each listed bounded loop should still be in the runtime`() {
        val listed = BackgroundLoopRules.BOUNDED.keys
        val found = BackgroundLoopRules.unboundedLoops(repositoryRoot, listedToo = true)
            .map { it.substringBefore(":") to it.substringAfterLast(": ") }.toSet()
        (listed - found).assert().describedAs("Listed loops no longer in the runtime").isEmpty()
    }

    @Test
    fun `each scope of the runtime should carry the node's handler, or be listed`() {
        BackgroundLoopRules.unhandledScopes(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `a background loop written as a while should be caught, a bounded one and a comment not`(@TempDir root: File) {
        File(root, "${ArchitectureRules.RUNTIME.dir}/src/main/kotlin/me/ahoo/wow/runtime/New.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                class New {
                    // while (true) in a comment
                    fun start() = scope.launch {
                        while (isActive) {
                            delay(interval)
                        }
                    }
                    private suspend fun drain() {
                        while (queued.size < max) take()
                    }
                    val scope = CoroutineScope(Dispatchers.Default)
                    val child = CoroutineScope(parent.coroutineContext + job)
                }
                """.trimIndent(),
            )
        }
        BackgroundLoopRules.unboundedLoops(root).assert().containsExactly("New.kt:4: start")
        BackgroundLoopRules.unhandledScopes(root).assert().containsExactly("New.kt:11")
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow-test.repository.root"
    }
}
