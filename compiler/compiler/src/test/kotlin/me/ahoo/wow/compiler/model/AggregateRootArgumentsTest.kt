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

package me.ahoo.wow.compiler.model

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import me.ahoo.wow.compiler.model.ManifestFragmentFixtures.context
import me.ahoo.wow.compiler.model.ManifestFragmentFixtures.fragments
import me.ahoo.wow.compiler.model.ManifestFragmentFixtures.read
import me.ahoo.wow.compiler.model.aggregate.Publication
import me.ahoo.wow.manifest.PublishedManifest
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/** What `@AggregateRoot` declares ([AggregateRootArguments]): the published stream, its state and its topic. */
@OptIn(ExperimentalCompilerApi::class)
class AggregateRootArgumentsTest {
    @Test
    fun `@AggregateRoot should declare the aggregate's published stream, its state and its topic`() {
        val streams = SourceFile.kotlin(
            "Streams.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot

            data class Open(val id: String)
            data class Opened(val id: String)

            @AggregateRoot(published = false)
            class Ledger(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }

            @AggregateRoot(publishState = false, topic = "shop.accounts-v2")
            class Account(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }

            @AggregateRoot(topic = "")
            class Wallet(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", context, streams)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val published = read(fragments(compilation).single()).contexts.single().published
        // Decision D156: no stream of the ledger; the account's without its state, on its own topic.
        published.assert().containsExactly(
            PublishedManifest("account", "shop.accounts-v2", includeState = false),
            PublishedManifest("wallet", "wow.shop.wallet.state", includeState = true),
        )
    }

    @Test
    fun `a topic Kafka refuses should fail the compilation`() {
        val long = "t".repeat(Publication.MAX_TOPIC_LENGTH + 1)
        val broken = SourceFile.kotlin(
            "Topics.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot

            data class Open(val id: String)
            data class Opened(val id: String)

            @AggregateRoot(topic = "shop/accounts")
            class Account(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }

            @AggregateRoot(topic = "$long")
            class Wallet(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }

            @AggregateRoot(topic = "..")
            class Purse(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", context, broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("@AggregateRoot(topic) \"shop/accounts\" is not a Kafka topic name")
            .contains("@AggregateRoot(topic) is 250 characters long: a Kafka topic has at most 249")
            .contains("@AggregateRoot(topic) \"..\" is not a Kafka topic name")
        fragments(compilation).assert().isEmpty()
        Publication.topicProblem("wow.shop.cart.state").assert().isNull()
        Publication.topicProblem("t".repeat(Publication.MAX_TOPIC_LENGTH)).assert().isNull()
    }
}
