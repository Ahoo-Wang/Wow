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

package me.ahoo.wow.query.schema

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.QueryField
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.function.LongSupplier

/** The current schema of one aggregate model, compiled once and recompiled on [refresh]. */
interface QueryModelSchemaProvider {
    suspend fun schema(): QueryModelSchema

    suspend fun refresh(): QueryModelSchema
}

/** A provider of one fixed [schema], for a model whose schema is built in code (tests, tools). */
class FixedQueryModelSchemaProvider(private val schema: QueryModelSchema) : QueryModelSchemaProvider {
    override suspend fun schema(): QueryModelSchema = schema

    override suspend fun refresh(): QueryModelSchema = schema
}

/**
 * The schema provider of an aggregate model that has no query backend: every load fails with [message].
 * [QuerySchemaCatalog] skips it, since there is nothing to load or revalidate.
 */
internal class UnavailableQueryModelSchemaProvider(
    private val message: String,
) : QueryModelSchemaProvider {
    override suspend fun schema(): QueryModelSchema = throw QuerySchemaUnavailableException(message)

    override suspend fun refresh(): QueryModelSchema = schema()
}

/**
 * The Catalog's compilation of one aggregate model (design §5.2): merges the declarations of [sources] into the
 * logical model, asks the storage [adapter] for its facts about it, and compiles them into the published
 * [QueryModelSchema]. The first load is shared by concurrent callers; a refresh reloads the sources and the storage's
 * native structures and replaces the published schema only when it compiles to a different schema: when the
 * [version][QueryModelSchema.version] and the native facts are unchanged, the published instance is kept. A
 * [provisional][QueryModelSchema.provisional] schema is never published: it is answered for `provisionalTtl`, so a
 * storage that is never written is not asked again on every query, and the first load after that compiles the
 * storage again. Once the storage exists its schema is the one published, at most `provisionalTtl` after it does.
 */
@InternalWowApi
class DefaultQueryModelSchemaProvider(
    private val context: QuerySchemaContext,
    sources: List<QuerySchemaSource>,
    private val adapter: QueryStorageAdapter,
    private val provisionalTtl: Duration = DEFAULT_PROVISIONAL_TTL,
    /** The monotonic clock, in nanoseconds, `provisionalTtl` is measured with. */
    private val nanoTime: LongSupplier = LongSupplier(System::nanoTime),
    /** The record layout of the model: a built-in model's, or a dataset's own (6.1d). */
    private val profile: QueryModelProfile? = QueryModelProfile.of(context.model),
    /** Which protected values filters and sorts may still compare. */
    private val sensitivity: QuerySensitivityPolicy = QuerySensitivityPolicy.DEFAULT,
) : QueryModelSchemaProvider {
    private val sources = sources.toList()
    private val published = AtomicReference<QueryModelSchema>()
    private val kept = AtomicReference<Provisional?>()
    private val firstLoad = AtomicReference<CompletableDeferred<QueryModelSchema>?>()
    private val refreshLoad = AtomicReference<CompletableDeferred<QueryModelSchema>?>()
    private val merger = QuerySchemaMerger()

    override suspend fun schema(): QueryModelSchema {
        published.get()?.let { return it }
        currentProvisional()?.let { return it }
        return shared(firstLoad) {
            published.get() ?: resolve(refresh = false).also { schema ->
                if (schema.provisional) keepProvisional(schema) else published.compareAndSet(null, schema)
            }
        }
    }

    override suspend fun refresh(): QueryModelSchema = shared(refreshLoad) {
        keepUnchanged(resolve(refresh = true)).also { schema ->
            published.set(schema.takeUnless(QueryModelSchema::provisional))
            if (schema.provisional) keepProvisional(schema) else kept.set(null)
        }
    }

    /**
     * Runs [load] once for every caller that arrives while it runs: the first caller loads, the others await its
     * result, and the next caller after it finished loads again. When the loading caller is cancelled, the others
     * load again instead of failing with its cancellation.
     */
    @Suppress("TooGenericExceptionCaught") // Every failure reaches the waiting callers, then propagates.
    private suspend fun shared(
        inFlight: AtomicReference<CompletableDeferred<QueryModelSchema>?>,
        load: suspend () -> QueryModelSchema,
    ): QueryModelSchema {
        while (true) {
            val running = inFlight.get()
            if (running != null) {
                awaitShared(running)?.let { return it }
                continue
            }
            val candidate = CompletableDeferred<QueryModelSchema>()
            if (!inFlight.compareAndSet(null, candidate)) continue
            try {
                return load().also(candidate::complete)
            } catch (error: Throwable) {
                // Leave the in-flight slot before failing the waiters, so a waiter that loads again finds it free.
                inFlight.compareAndSet(candidate, null)
                candidate.completeExceptionally(error)
                throw error
            } finally {
                inFlight.compareAndSet(candidate, null)
            }
        }
    }

    /** The published schema when [compiled] is the same schema, so its instance survives a refresh. */
    private fun keepUnchanged(compiled: QueryModelSchema): QueryModelSchema {
        if (compiled.provisional) return compiled
        val current = published.get() ?: return compiled
        return if (current.compilesSameAs(compiled)) current else compiled
    }

    private fun currentProvisional(): QueryModelSchema? =
        kept.get()?.takeIf { nanoTime.asLong - it.expiresAt < 0 }?.schema

    private fun keepProvisional(schema: QueryModelSchema) {
        kept.set(Provisional(schema, nanoTime.asLong + provisionalTtl.toNanos()))
    }

    private class Provisional(val schema: QueryModelSchema, val expiresAt: Long)

    private suspend fun resolve(refresh: Boolean): QueryModelSchema {
        val loaded = sources.flatMap { source ->
            val declarations = if (refresh) source.refresh(context) else source.load(context)
            declarations.map { PrioritizedQuerySchemaDeclaration(source.priority, it) }
        }
        val profile = profile
            ?: throw QuerySchemaValidationException("Unsupported System query model: [${context.model}].")
        val logicalSchema = merger.merge(profile.systemDeclaration, loaded, sensitivity, profile.payloadField?.path)
        val facts = if (refresh) adapter.refresh(logicalSchema) else adapter.facts(logicalSchema)
        return facts.compile(context.model, logicalSchema, profile)
    }

    companion object {
        /** How long a provisional schema is answered before the storage is asked again. */
        @JvmField
        val DEFAULT_PROVISIONAL_TTL: Duration = Duration.ofSeconds(2)
    }
}

/**
 * The result of [running], a load another caller runs; `null` when that caller was cancelled, so this one loads again.
 * This caller's own cancellation still propagates.
 */
internal suspend fun <T> awaitShared(running: Deferred<T>): T? = try {
    running.await()
} catch (error: CancellationException) {
    currentCoroutineContext().ensureActive()
    null
}

/**
 * The fields a query on [this] model can name as written: the system fields and the declarations of [sources], merged
 * into the logical model as [DefaultQueryModelSchemaProvider] merges them, before any storage facts. Fields under a
 * map, whose keys are the caller's, are left out.
 */
@InternalWowApi
suspend fun QuerySchemaContext.declaredFields(
    sources: List<QuerySchemaSource>,
    sensitivity: QuerySensitivityPolicy = QuerySensitivityPolicy.DEFAULT,
): Set<QueryField> {
    val declarations = sources.flatMap { source ->
        source.load(this).map { PrioritizedQuerySchemaDeclaration(source.priority, it) }
    }
    return QuerySchemaMerger().merge(SystemQuerySchemaSource.declaration(model), declarations, sensitivity)
        .staticMatches.keys
}
