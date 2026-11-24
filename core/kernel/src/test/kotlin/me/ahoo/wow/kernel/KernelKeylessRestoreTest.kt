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

package me.ahoo.wow.kernel

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.ReservationTenancy
import me.ahoo.wow.api.lifecycle.AggregateDeleted
import me.ahoo.wow.api.lifecycle.AggregateRestored
import me.ahoo.wow.api.lifecycle.RestoreAggregate
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.lifecycle.TransferSpace
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A decision that deletes its aggregate and releases keys fails, committing nothing, while the kernel restores that
 * aggregate by its own function: `RestoreAggregate` runs no code of the model, so it would bring the aggregate back
 * without the keys, which another aggregate may meanwhile hold. Releasing without deleting, deleting without releasing,
 * and an aggregate that restores itself by its own code all pass.
 */
class KernelKeylessRestoreTest {
    data class Publish(val slug: String, val alias: String = "")
    data class Published(val slug: String, val alias: String)

    /** Deletes the article, releasing its slug, and its alias too when [alias]. */
    data class Remove(val alias: Boolean = false)

    /** Deletes the article and releases nothing. */
    data object Retire

    /** Releases the slug without deleting the article. */
    data object Unlist
    data object Unlisted

    /** Releases the slug and deletes, on a deleted article: the deletion changes nothing. */
    data object Purge

    /** Restores the article under another slug, releasing the old one: the decision ends with the article restored. */
    data class Revive(val slug: String)

    /** Deletes and restores the article in one decision, releasing its slug: it ends live. */
    data object Cycle

    class Article {
        var slug = ""
        var alias = ""
    }

    private val log = InMemoryEventLog(partitions = 1)
    private val kernel = Kernel(log, reservations = InMemoryReservationStore())
    private var sequence = 0

    private fun article(block: AggregateModelBuilder<Article>.() -> Unit = {}) = aggregate<Article>("docs", "article") {
        initial { Article() }
        decideSuspending<Publish>(Creation.REQUIRED) { _, body ->
            reserve(SLUGS, body.slug)
            if (body.alias.isNotEmpty()) reserve(ALIASES, body.alias, ReservationTenancy.SYSTEM)
            Decision.events(Published(body.slug, body.alias))
        }
        decide<Remove> { state, body ->
            release(SLUGS, state.slug)
            if (body.alias) release(ALIASES, state.alias, ReservationTenancy.SYSTEM)
            Decision.events(AggregateDeleted())
        }
        decide<Retire> { _, _ -> Decision.events(AggregateDeleted()) }
        decide<Unlist> { state, _ ->
            release(SLUGS, state.slug)
            Decision.events(Unlisted)
        }
        decide<Purge>(onDeleted = true) { state, _ ->
            release(SLUGS, state.slug)
            Decision.events(AggregateDeleted())
        }
        decide<Cycle> { state, _ ->
            release(SLUGS, state.slug)
            Decision.events(AggregateDeleted(), AggregateRestored())
        }
        decideSuspending<Revive>(onDeleted = true) { state, body ->
            release(SLUGS, state.slug)
            reserve(SLUGS, body.slug)
            Decision.events(AggregateRestored())
        }
        evolve<Published> {
            slug = it.slug
            alias = it.alias
        }
        evolve<Unlisted> { }
        block()
    }

    private suspend fun send(model: AggregateModel<*>, body: Any) = kernel.execute(
        model,
        Command("c-${++sequence}", "r-$sequence", model.type.ref("tenant", "a-1"), body),
    )

    private suspend fun versions(model: AggregateModel<*>) =
        log.read(model.type.ref("tenant", "a-1")).toList().map { it.version }

    /** Publishes the article of [model], then sends [body], which fails; returns the failure. */
    private suspend fun refused(model: AggregateModel<*>, body: Any, publish: Publish = Publish("hello")): String {
        send(model, publish)
        val failure = assertThrows<UndeclaredEventException> { send(model, body) }
        failure.event.assert().isEqualTo(AggregateDeleted::class.java)
        failure.command.assert().isEqualTo(body.javaClass)
        // Nothing of the decision is committed: the publication alone is in the log.
        versions(model).assert().containsExactly(1)
        return checkNotNull(failure.message)
    }

    @Test
    fun `a deletion that releases a key should fail while the built-in restore is handled`(): Unit = runTest {
        refused(article(), Remove()).assert().isEqualTo(
            "docs.article handles the built-in RestoreAggregate, which restores it without running its code, so its " +
                "decision of Remove may not produce AggregateDeleted while it releases article-slugs/hello: " +
                "restored, the aggregate would not hold them. Leave RestoreAggregate out of @AggregateRoot(lifecycle) " +
                "and restore it by a command of its own, @OnCommand(onDeleted = true), that reserves them again; it " +
                "committed nothing.",
        )
    }

    @Test
    fun `a deletion that releases two keys should name both`(): Unit = runTest {
        refused(article(), Remove(alias = true), Publish("hello", "hi")).assert()
            .contains("releases article-slugs/hello, article-aliases/hi (across tenants):")
    }

    @Test
    fun `a deletion that releases nothing should pass with the built-in restore`(): Unit = runTest {
        val model = article()
        send(model, Publish("hello"))
        send(model, Retire).assert().isInstanceOf(CommandOutcome.Committed::class.java)
        send(model, RestoreAggregate()).assert().isInstanceOf(CommandOutcome.Committed::class.java)
    }

    @Test
    fun `a release that deletes nothing should pass with the built-in restore`(): Unit = runTest {
        val model = article()
        send(model, Publish("hello"))
        send(model, Unlist).assert().isInstanceOf(CommandOutcome.Committed::class.java)
    }

    @Test
    fun `a deletion that releases a key should pass when the built-in restore is left out`(): Unit = runTest {
        val model = article { lifecycle(TransferOwner::class.java, TransferSpace::class.java) }
        send(model, Publish("hello"))
        send(model, Remove()).assert().isInstanceOf(CommandOutcome.Committed::class.java)
    }

    @Test
    fun `a deletion that releases a key should pass when the model restores by its own code`(): Unit = runTest {
        val model = article {
            decide<RestoreAggregate> { _, _ -> Decision.events(AggregateRestored()) }
        }
        send(model, Publish("hello"))
        send(model, Remove()).assert().isInstanceOf(CommandOutcome.Committed::class.java)
    }

    @Test
    fun `a deletion that changes nothing should not fail, its release with it`(): Unit = runTest {
        val model = article()
        send(model, Publish("hello"))
        send(model, Retire)
        // On a deleted article the deletion is folded out: the decision deletes nothing, and appends nothing.
        send(model, Purge).assert().isInstanceOf(CommandOutcome.Unchanged::class.java)
    }

    @Test
    fun `a decision that releases a key and ends restored should pass`(): Unit = runTest {
        val model = article()
        send(model, Publish("hello"))
        send(model, Retire)
        send(model, Revive("hello-again")).assert().isInstanceOf(CommandOutcome.Committed::class.java)
    }

    @Test
    fun `a decision that deletes and restores, releasing a key, should pass, as it ends live`(): Unit = runTest {
        val model = article()
        send(model, Publish("hello"))
        send(model, Cycle).assert().isInstanceOf(CommandOutcome.Committed::class.java)
    }

    private companion object {
        const val SLUGS = "article-slugs"
        const val ALIASES = "article-aliases"
    }
}
