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

class KotlinSignatureRulesTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })
            .canonicalFile

    @Test
    fun `the public API should name no type that is not public itself, in Kotlin's signatures either`() {
        KotlinSignatureRules.closureViolations(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `the rule should see a type a suspend result or a generic argument names`(@TempDir root: File) {
        val module = ArchitectureRules.KERNEL
        val fixture = "me/ahoo/wow/architecture/fixture"
        val dump = "${module.dir}/api/${module.name}.api"
        File(root, "${module.dir}/api").mkdirs()
        File(root, dump).writeText(
            """
            public final class $fixture/Exposed {
            	public fun <init> ()V
            	public final fun hiddenLater (Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
            	public final fun hiddenList ()Ljava/util/List;
            	public final fun shownList ()Ljava/util/List;
            }

            public final class $fixture/Shown {
            	public fun <init> ()V
            }

            public final class $fixture/Missing {
            }
            """.trimIndent(),
        )
        val classBytes = { _: WowProject, name: String ->
            javaClass.classLoader.getResourceAsStream("$name.class")?.use { it.readBytes() }
        }
        KotlinSignatureRules.closureViolations(root, classBytes = classBytes).assert().containsExactlyInAnyOrder(
            "$dump: $fixture/Exposed.hiddenLater: $fixture/Hidden",
            "$dump: $fixture/Exposed.hiddenList: $fixture/Hidden",
            "$dump: $fixture/Missing: no compiled class; build the module first",
        )
        // An allowed leak is no violation; an allowed type no member names any more is a stale entry.
        val allowed = mapOf("$fixture/Hidden" to "a fix", "$fixture/Gone" to "a fix")
        KotlinSignatureRules.closureViolations(root, allowed, classBytes).assert().containsExactlyInAnyOrder(
            "$dump: $fixture/Missing: no compiled class; build the module first",
            "stale entry: $fixture/Gone",
        )
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow-test.repository.root"
    }
}
