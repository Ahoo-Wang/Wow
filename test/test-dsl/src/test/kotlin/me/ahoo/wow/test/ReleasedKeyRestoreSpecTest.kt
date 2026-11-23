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

package me.ahoo.wow.test

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.AggregateDeleted
import me.ahoo.wow.api.lifecycle.AggregateRestored
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.lifecycle.TransferSpace
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test

/**
 * An aggregate that gives its unique key up when it is deleted, in a delete command of its own, and takes it back when
 * it is restored, in a restore command of its own: restored when the key is free, refused when another aggregate took
 * it. `documentation/docs/{en,zh}/guide/unique-keys.md` shows the aggregate and the specification.
 */
class ReleasedKeyRestoreSpecTest {
    // The annotations below are inert here: the DSL reads none of them. They are the aggregate as the guide shows it,
    // which the compiler reads in a service's domain; this file's model is built by hand below, calling the class's own
    // functions, as the compiler generates them.
    // region article
    @CreateAggregate
    data class PublishArticle(@AggregateId val id: String, val slug: String)

    data class RemoveArticle(@AggregateId val id: String)

    data class RestoreArticle(@AggregateId val id: String)

    data class ArticlePublished(val slug: String)

    class ArticleState {
        var slug: String = ""
            private set

        fun onSourcing(event: ArticlePublished) {
            slug = event.slug
        }
    }

    /**
     * Its own delete and restore are its only ones: the built-in `DeleteAggregate` releases no key, and the built-in
     * `RestoreAggregate` passes through no code of the aggregate, so it would bring the article back without its slug.
     */
    @AggregateRoot(lifecycle = [TransferOwner::class, TransferSpace::class])
    class Article(private val state: ArticleState) {
        suspend fun onCommand(command: PublishArticle, scope: DecideScope): ArticlePublished {
            take(command.slug, scope)
            return ArticlePublished(command.slug)
        }

        /** Deletes the article and gives its slug up, so that another article may take it. */
        @Suppress("UnusedParameter") // The command names its aggregate alone: its type is what the function handles.
        suspend fun onCommand(command: RemoveArticle, scope: DecideScope): AggregateDeleted {
            scope.release(SLUGS, state.slug)
            return AggregateDeleted()
        }

        /** Restores the article with its slug, or rejects the command when another article took it meanwhile. */
        @OnCommand(onDeleted = true)
        @Suppress("UnusedParameter") // The command names its aggregate alone: its type is what the function handles.
        suspend fun onCommand(command: RestoreArticle, scope: DecideScope): AggregateRestored {
            take(state.slug, scope)
            return AggregateRestored()
        }

        private suspend fun take(slug: String, scope: DecideScope) {
            if (!scope.reserve(SLUGS, slug)) {
                scope.reject(SLUG_TAKEN, "Another article's slug is \"$slug\".")
            }
        }

        companion object {
            const val SLUGS = "article-slugs"
            const val SLUG_TAKEN = "slug-taken"
        }
    }
    // endregion

    private val model = aggregate<ArticleState>("docs", "article") {
        initial { ArticleState() }
        decideSuspending<PublishArticle>(Creation.REQUIRED) { state, body ->
            Decision.events(Article(state).onCommand(body, this))
        }
        decideSuspending<RemoveArticle> { state, body -> Decision.events(Article(state).onCommand(body, this)) }
        decideSuspending<RestoreArticle>(onDeleted = true) { state, body ->
            Decision.events(Article(state).onCommand(body, this))
        }
        aggregateId<PublishArticle> { it.id }
        aggregateId<RemoveArticle> { it.id }
        aggregateId<RestoreArticle> { it.id }
        evolve<ArticlePublished> { onSourcing(it) }
    }

    @Test
    fun `a removed article gives its slug up, and a restored one takes it back`() {
        // region restore-spec
        aggregateSpec(model, id = "article-1") {
            givenCommands(PublishArticle("article-1", "hello-wow"))
            whenCommand(RemoveArticle("article-1")) { expectDeleted() }
            holds(Article.SLUGS, "hello-wow").assert().isFalse()
            fork("the slug is free") {
                whenCommand(RestoreArticle("article-1")) { expectEvents(AggregateRestored()) }
                holds(Article.SLUGS, "hello-wow").assert().isTrue()
                // Restoring an article that is not deleted changes nothing.
                whenCommand(RestoreArticle("article-1")) { expectUnchanged() }
            }
            fork("another article took the slug") {
                givenReservation(Article.SLUGS, "hello-wow")
                whenCommand(RestoreArticle("article-1")) { expectRejected(Article.SLUG_TAKEN) }
            }
        }
        // endregion
    }
}
