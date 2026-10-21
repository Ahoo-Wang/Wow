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

package me.ahoo.wow.rehearsal.driver

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Where the driver left a view: its owner after any share or claim, its title and version, whether it is deleted. */
data class BookedView(val ref: ViewRef, val title: String, val version: Int, val deleted: Boolean) {
    fun line(): String = listOf(ref.id, ref.tenant, ref.owner, title, version, if (deleted) 1 else 0).joinToString("\t")

    companion object {
        fun parse(line: String): BookedView = line.split('\t').let {
            BookedView(ViewRef(it[0], it[1], it[2]), it[3], it[4].toInt(), it[5] == "1")
        }
    }
}

/**
 * The view store's part of the rehearsal (`driver views …`), against the 9.x view store the example-server embeds or
 * the V10 view store service:
 *
 * ```text
 * driver views seed    --base=<9.x url> --book=<file> --views=N [--tenants=T] [--users=U]
 * driver views traffic --version=9x|v10 --base=<url> --book=<file> --seconds=S [--touched=<file>] [--create=K]
 * driver views await   --base=<v10 url> --touched=<file> [--timeout=S]
 * driver views states  --version=9x|v10 --base=<url> --book=<file> --out=<file> [--sample=N]
 * driver views verify  --base=<9.x url> --touched=<file>
 * ```
 *
 * - `seed` creates N views in 9.x, each taken through what its index draws: saved, renamed, shared (its owner becomes
 *   `(shared)`) and claimed by another user (a 9.x `ViewAudienceChanged`, an owner transfer), or deleted; each user's
 *   preferences for a definition; each tenant's wording of three datasets, one of them cleared again.
 * - `traffic` renames personal views for S seconds; with `--create`, it creates K views instead (on V10 a
 *   `ViewCreated` at revision 2, which 9.x cannot read), and nothing else.
 * - `await` waits until V10 holds every touched view at its version; `states` reads every N-th view that is not
 *   deleted, its version and its state without what one version derives (`id`, V10's `kind`), for `driver compare`.
 * - `verify` renames, in 9.x after the rollback, every view V10 touched, and checks that 9.x applies it on the version
 *   V10 left: 9.x reads what export wrote back.
 */
class ViewsDriver(private val args: List<String>, private val concurrency: Int) {
    private val options: Map<String, String> = args.filter { it.startsWith("--") }.associate { option ->
        option.removePrefix("--").split('=', limit = 2).let { it[0] to it.getOrElse(1) { "true" } }
    }

    fun run(): Int = when (args.firstOrNull()) {
        "seed" -> seed()
        "traffic" -> traffic()
        "await" -> await()
        "states" -> states()
        "verify" -> verify()
        else -> {
            System.err.println("Usage: driver views seed|traffic|await|states|verify … (see ViewsDriver)")
            USAGE
        }
    }

    private fun required(name: String): String = options[name] ?: throw IllegalArgumentException("--$name is required.")

    private fun book(): List<BookedView> = readViews(File(required("book")))

    private fun seed(): Int {
        val endpoints = ViewEndpoints.of("9x", required("base"))
        val views = required("views").toInt()
        val tenants = options["tenants"]?.toInt() ?: DEFAULT_TENANTS
        val users = options["users"]?.toInt() ?: (views / VIEWS_PER_USER).coerceAtLeast(1)
        val stats = Stats()
        val book = ConcurrentHashMap<String, BookedView>()
        parallel(0 until views) { index ->
            lifecycle(endpoints, index, tenants, users, stats)?.let { book[it.ref.id] = it }
        }
        parallel(0 until users) { index ->
            val body = """{"order":["${definition(index)}-a","${definition(index)}-b"],"autoRun":${index % 2 == 0},""" +
                """"lastTabs":{"main":"t$index"}}"""
            stats.count(endpoints.setPreferences(tenant(index, tenants), user(index), definition(index), body))
        }
        parallel(0 until tenants) { index ->
            DATASETS.forEach { dataset ->
                val body = """{"title":"$dataset of t$index","recordNoun":"$dataset","fields":{"id":"Id"}}"""
                stats.count(endpoints.setWording("t$index", dataset, body))
            }
            stats.count(endpoints.clearWording("t$index", DATASETS.last()))
        }
        writeViews(File(required("book")), book.values)
        val deleted = book.values.count { it.deleted }
        val shared = book.values.count { it.ref.owner == ViewEndpoints.SHARED }
        println(
            stats.json(
                "views-seed",
                "\"views\":${book.size},\"shared\":$shared,\"deleted\":$deleted,\"preferences\":$users," +
                    "\"wordings\":${tenants * DATASETS.size}",
            )
        )
        return if (stats.failed.get() == 0L) OK else FAILED
    }

    /** Creates view [index] and takes it through what its index draws; null when it could not be created. */
    private fun lifecycle(endpoints: ViewEndpoints, index: Int, tenants: Int, users: Int, stats: Stats): BookedView? {
        val random = Random(index)
        val owner = user(index % users)
        val kind = KINDS[index % KINDS.size]
        val title = "View $index"
        val created = endpoints.create(
            tenant(index % users, tenants),
            owner,
            """{"definitionId":"${definition(index)}","title":"$title","config":${config(kind, index)}}""",
        )
        stats.count(created)
        val id = created.id ?: return null
        var booked = BookedView(ViewRef(id, tenant(index % users, tenants), owner), title, created.version ?: 1, false)
        fun step(outcome: Outcome, next: (BookedView) -> BookedView): Boolean {
            stats.count(outcome)
            if (outcome.ok) booked = next(booked).copy(version = outcome.version ?: (booked.version + 1))
            return outcome.ok
        }
        if (random.nextInt(PERCENT) < SAVED) step(endpoints.save(booked.ref, config(kind, index + 1))) { it }
        if (random.nextInt(PERCENT) < RENAMED) {
            val renamed = "$title renamed"
            step(endpoints.rename(booked.ref, renamed)) { it.copy(title = renamed) }
        }
        when (random.nextInt(PERCENT)) {
            in 0 until SHARED -> if (step(endpoints.share(booked.ref)) {
                    it.copy(ref = it.ref.copy(owner = ViewEndpoints.SHARED))
                } &&
                random.nextInt(PERCENT) < CLAIMED
            ) {
                val claimer = user((index + 1) % users)
                step(endpoints.claim(booked.ref, claimer)) { it.copy(ref = it.ref.copy(owner = claimer)) }
            }

            in SHARED until SHARED + DELETED -> step(endpoints.delete(booked.ref)) { it.copy(deleted = true) }
        }
        return booked
    }

    private fun traffic(): Int {
        val endpoints = ViewEndpoints.of(required("version"), required("base"))
        val bookFile = File(required("book"))
        val run = Traffic(endpoints, readViews(bookFile))
        val toCreate = options["create"]?.toInt() ?: 0
        if (toCreate > 0) run.create(toCreate) else run.rename(required("seconds").toLong())
        writeViews(bookFile, run.book.values)
        options["touched"]?.let { writeViews(File(it), run.touched.values) }
        println(
            run.stats.json(
                "views-traffic",
                "\"touched\":${run.touched.size},\"created\":${if (toCreate > 0) run.touched.size else 0}",
            )
        )
        return OK
    }

    /** One run of `traffic` over the booked views: it renames personal views, or creates some. */
    private inner class Traffic(val endpoints: ViewEndpoints, views: List<BookedView>) {
        val book = ConcurrentHashMap(views.associateBy { it.ref.id })
        val touched = ConcurrentHashMap<String, BookedView>()
        val stats = Stats()
        private val personal = views.filter { !it.deleted && it.ref.owner != ViewEndpoints.SHARED }.map { it.ref.id }
            .sorted()
        private val sequence = AtomicLong()

        fun create(count: Int) = parallel(0 until count) { index ->
            val owner = user(0)
            val title = "New view $index"
            val outcome = endpoints.create(
                tenant(0, 1),
                owner,
                """{"definitionId":"${definition(
                    index
                )}","title":"$title","config":${config(KINDS[index % KINDS.size], index)}}""",
            )
            stats.count(outcome)
            outcome.id?.let { booked(BookedView(ViewRef(it, tenant(0, 1), owner), title, outcome.version ?: 1, false)) }
        }

        fun rename(seconds: Long) {
            val deadline = System.nanoTime() + seconds * NANOS_PER_SECOND
            parallel(0 until concurrency) { worker ->
                val random = Random(worker * WORKER_SEED + System.nanoTime())
                while (System.nanoTime() < deadline && personal.isNotEmpty()) {
                    renameOne(personal[random.nextInt(personal.size)])
                }
            }
        }

        /** Renames view [id], unless another worker is renaming it: one command at a time per view. */
        private fun renameOne(id: String) {
            val view = book.remove(id) ?: return
            val title = "View $id #${sequence.incrementAndGet()}"
            val outcome = endpoints.rename(view.ref, title)
            stats.count(outcome)
            if (outcome.ok) {
                booked(
                    view.copy(title = title, version = outcome.version ?: (view.version + 1))
                )
            } else {
                book[id] = view
            }
        }

        private fun booked(view: BookedView) {
            book[view.ref.id] = view
            touched[view.ref.id] = view
        }
    }

    private fun await(): Int {
        val endpoints = ViewEndpoints.of("v10", required("base"))
        val touched = readViews(File(required("touched")))
        val started = System.nanoTime()
        val deadline = started + (options["timeout"]?.toLong() ?: DEFAULT_AWAIT_SECONDS) * NANOS_PER_SECOND
        var waiting = touched
        while (waiting.isNotEmpty() && System.nanoTime() < deadline) {
            val states = endpoints.states(waiting.map { it.ref })
            waiting = waiting.filter { (states[it.ref.id]?.first ?: 0) < it.version }
            if (waiting.isNotEmpty()) Thread.sleep(POLL_MILLIS)
        }
        val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
        println(
            """{"command":"views-await","views":${touched.size},"behind":${waiting.size},"caughtUpMillis":$millis}"""
        )
        return if (waiting.isEmpty()) OK else FAILED
    }

    private fun states(): Int {
        val endpoints = ViewEndpoints.of(required("version"), required("base"))
        val every = options["sample"]?.toInt() ?: 1
        val views = book().filter { !it.deleted }.filterIndexed { index, _ -> index % every == 0 }
        val states = endpoints.states(views.map { it.ref })
        val out = Endpoints.MAPPER.createObjectNode()
        states.toSortedMap().forEach { (id, versioned) ->
            out.set(id, normalized(versioned.second).put("version", versioned.first))
        }
        File(required("out")).writeText(Endpoints.MAPPER.writeValueAsString(out))
        println("""{"command":"views-states","sampled":${views.size},"read":${states.size}}""")
        return if (states.size == views.size) OK else FAILED
    }

    private fun verify(): Int {
        val endpoints = ViewEndpoints.of("9x", required("base"))
        val touched = readViews(File(required("touched")))
        val mismatched = ConcurrentHashMap<String, String>()
        val stats = Stats()
        parallel(touched.indices) { index ->
            val view = touched[index]
            val outcome = endpoints.rename(view.ref, "${view.title} (9.x again)")
            stats.count(outcome)
            if (outcome.version != view.version + 1) {
                mismatched[view.ref.id] = "${outcome.version ?: outcome.error} after V10's ${view.version}"
            }
        }
        mismatched.entries.take(MAX_PRINTED).forEach { (id, why) -> System.err.println("MISMATCH $id: $why") }
        println(
            stats.json("views-verify", "\"views\":${touched.size},\"mismatched\":${mismatched.size}")
        )
        return if (mismatched.isEmpty()) OK else DIFFERS
    }

    /** [state] without what one version derives: 9.x's `id`, which V10's ref holds, and V10's `kind`, from `config`. */
    private fun normalized(state: JsonNode): ObjectNode = (state.deepCopy() as ObjectNode).apply {
        remove("id")
        remove("kind")
    }

    private fun parallel(range: Iterable<Int>, task: (Int) -> Unit) {
        val permits = Semaphore(concurrency)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            range.forEach { index ->
                permits.acquire()
                executor.submit {
                    try {
                        task(index)
                    } catch (failure: Exception) {
                        System.err.println("task $index failed: $failure")
                    } finally {
                        permits.release()
                    }
                }
            }
        }
    }

    /** The commands a run sent, applied and failed, and how fast. */
    private class Stats {
        val sent = AtomicLong()
        val failed = AtomicLong()
        private val errors = ConcurrentHashMap<String, AtomicLong>()
        private val started = System.nanoTime()

        fun count(outcome: Outcome) {
            sent.incrementAndGet()
            if (!outcome.ok) {
                failed.incrementAndGet()
                errors.computeIfAbsent(outcome.error.orEmpty().take(ERROR_KEY)) { AtomicLong() }.incrementAndGet()
            }
        }

        fun json(command: String, extra: String): String {
            val seconds = (System.nanoTime() - started).toDouble() / NANOS_PER_SECOND
            val errorList = errors.entries.sortedByDescending { it.value.get() }.take(MAX_PRINTED)
                .joinToString(",") { (error, count) -> "\"${error.replace("\"", "'")}\":${count.get()}" }
            return """{"command":"$command",$extra,"commands":${sent.get()},"failed":${failed.get()},""" +
                """"seconds":${"%.1f".format(seconds)},"perSecond":${"%.0f".format(sent.get() / seconds)},"errors":{$errorList}}"""
        }
    }

    private companion object {
        const val OK = 0
        const val FAILED = 1
        const val USAGE = 2
        const val DIFFERS = 3
        const val DEFAULT_TENANTS = 4
        const val VIEWS_PER_USER = 4
        const val DEFAULT_AWAIT_SECONDS = 300L
        const val DEFINITIONS = 5
        const val PERCENT = 100
        const val SAVED = 50
        const val RENAMED = 50
        const val SHARED = 20
        const val CLAIMED = 50
        const val DELETED = 10
        const val WORKER_SEED = 7919
        const val POLL_MILLIS = 200L
        const val MAX_PRINTED = 20
        const val ERROR_KEY = 120
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
        val KINDS = listOf("record", "analysis")
        val DATASETS = listOf("orders", "carts", "payments")

        fun user(index: Int): String = "u-%04d".format(index)

        /** A user's tenant: each user, and so each view of theirs, is in one tenant. */
        fun tenant(user: Int, tenants: Int): String = "t${user % tenants}"

        fun definition(index: Int): String = "def-${index % DEFINITIONS}"

        fun config(kind: String, seed: Int): String = """{"kind":"$kind","columns":["c${seed % 7}","c${(seed + 3) % 7}"]}"""

        fun readViews(file: File): List<BookedView> = file.readLines().filter { it.isNotBlank() }.map(BookedView::parse)

        fun writeViews(file: File, views: Collection<BookedView>) {
            file.writeText(views.sortedBy { it.ref.id }.joinToString("\n", postfix = "\n") { it.line() })
        }
    }
}
