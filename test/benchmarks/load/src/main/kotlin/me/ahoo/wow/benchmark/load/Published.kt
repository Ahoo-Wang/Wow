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

package me.ahoo.wow.benchmark.load

/**
 * A published stream through a run (#7109): how fast the commits of its [aggregate] of [context] reach the transport
 * sink's topic and how far behind they fall. Every second, for each partition of the context's log, one store session
 * reads the log's head, the transport sink's checkpoint there (`wow.transport-sink.<aggregate>`, the highest of its
 * groups) and the oldest commit of the aggregate above that checkpoint, its commit time and its aggregate: on
 * PostgreSQL in one statement, so one snapshot ([postgresQuery]); on MongoDB in one round of reads by position, the
 * log's `_id` ([mongoLoop]). [summary] turns the rounds into the run's figures.
 *
 * The checkpoint is saved once a second (`wow.subscription.checkpoint-interval`), after the topic took the records:
 * what it says is published was, and up to that interval more may be. So the lag it gives is an upper bound by up to
 * the interval, and so is the catch-up. Not read each second: the topic's end offsets (`kafka-get-offsets` is a JVM of
 * its own a read, so [Resources] reads them before and after the run), and each aggregate's count above the
 * checkpoint (a scan of the whole unpublished tail each second); with `--distribution=single` the one aggregate's lag
 * is its partition's.
 */
class PublishedStream(val context: String, val aggregate: String) {
    init {
        require(NAME.matches(context) && NAME.matches(aggregate)) {
            "A published stream is <context>.<aggregate>, each of letters, digits and underscores: $context.$aggregate."
        }
    }

    /** The transport sink's subscription of the stream. */
    val subscription: String = "wow.transport-sink.$aggregate"

    private val log = "wow_log__${context}__$aggregate"
    private val counters = "wow_log__${context}__partition"

    /**
     * The statement psql repeats: one row a partition, `epoch|partition|head|checkpoint|commit time|aggregate`, the
     * checkpoint -1 before the sink's first save there and the commit time -1 (its aggregate empty) when nothing of the
     * aggregate lies above it. The partition's counter is its head (it commits with the append); the oldest commit is
     * one read of the log's key, `(partition, sequence)`.
     */
    fun postgresQuery(): String =
        "select extract(epoch from now()), p.partition, p.sequence, coalesce(c.position, -1), " +
            "coalesce(n.commit_time, -1), coalesce(n.aggregate_id, '') from \"$counters\" p " +
            "left join (select partition, max(position) as position from wow_checkpoint " +
            "where subscription = '$subscription' group by partition) c on c.partition = p.partition " +
            "left join lateral (select l.commit_time, l.aggregate_id from \"$log\" l where l.partition = p.partition " +
            "and l.sequence > coalesce(c.position, 0) order by l.sequence limit 1) n on true order by p.partition;"

    /**
     * The mongosh loop: [postgresQuery]'s rows, a line a partition a second. A partition's head is its highest stream
     * (a placeholder has no `version`), as the log reads it; positions are `_id`s, the partition above
     * [SEQUENCE_BITS] bits of sequence.
     */
    fun mongoLoop(): String =
        "const log = db.getCollection('$log'), saved = db.getCollection('wow_checkpoint'); " +
            "const parts = db.getCollection('$counters').find({}, {_id: 1}).toArray().map(d => d._id)" +
            ".filter(id => typeof id === 'number').sort((a, b) => a - b); " +
            "const id = (p, s) => Long.fromNumber(p).shiftLeft($SEQUENCE_BITS).or(Long.fromNumber(s)); " +
            "const last = p => Long.fromNumber(p).shiftLeft($SEQUENCE_BITS).or(Long.fromNumber(1).shiftLeft($SEQUENCE_BITS).subtract(1)); " +
            "while (true) { const now = Date.now() / 1000, at = {}; " +
            "saved.find({subscription: '$subscription'}).forEach(d => { const p = Number(d.partition); " +
            "at[p] = Math.max(at[p] ?? -1, Number(d.position)); }); " +
            "for (const p of parts) { const c = at[p] ?? -1; " +
            "const top = log.find({_id: {\$gte: id(p, 0), \$lte: last(p)}, version: {\$exists: true}}, {sequence: 1})" +
            ".sort({_id: -1}).limit(1).toArray()[0]; " +
            "const next = log.find({_id: {\$gt: id(p, Math.max(c, 0)), \$lte: last(p)}, version: {\$exists: true}}, " +
            "{commit_time: 1, aggregate_id: 1}).sort({_id: 1}).limit(1).toArray()[0]; " +
            "print([now, p, top ? Number(top.sequence) : 0, c, next ? Number(next.commit_time) : -1, " +
            "next ? next.aggregate_id : ''].join('|')); } sleep(1000); }"

    override fun toString(): String = "$context.$aggregate"

    /**
     * One partition's read at [at] (epoch milliseconds): the log's [head], the sink's [checkpoint] (null before its
     * first save there), and the commit time ([oldestCommit], epoch milliseconds) and aggregate of the oldest commit of
     * the stream above the checkpoint (null when there is none).
     */
    data class Sample(
        val at: Long,
        val partition: Int,
        val head: Long,
        val checkpoint: Long?,
        val oldestCommit: Long?,
        val oldestAggregate: String?,
    ) {
        /** The entries above the checkpoint. */
        val lag: Long get() = (head - (checkpoint ?: 0L)).coerceAtLeast(0L)

        /** How long the oldest commit above the checkpoint has waited, 0 when there is none. */
        val lagSeconds: Double get() = oldestCommit?.let { ((at - it) / MILLIS_PER_SECOND).coerceAtLeast(0.0) } ?: 0.0
    }

    /** One round of reads: each partition's at one time. */
    private class Round(val at: Long, val partitions: Map<Int, Sample>) {
        fun head(partition: Int): Long = partitions[partition]?.head ?: 0L
        fun checkpoint(partition: Int): Long = partitions[partition]?.checkpoint ?: 0L
    }

    companion object {
        private val NAME = Regex("[A-Za-z0-9_]+")
        private const val MILLIS_PER_SECOND = 1_000.0
        private const val FIELDS = 6

        /** The bits of a MongoDB log position's sequence, below its partition (the Mongo log's `SEQUENCE_BITS`). */
        const val SEQUENCE_BITS = 51

        /** `--published=<context>.<aggregate>`. */
        fun of(option: String): PublishedStream {
            require(option.count { it == '.' } == 1) { "--published is <context>.<aggregate>, not '$option'." }
            return PublishedStream(option.substringBefore('.'), option.substringAfter('.'))
        }

        /** A line of [postgresQuery] or [mongoLoop], or null when it is not one. */
        fun parse(line: String): Sample? {
            val fields = line.trim().split('|', limit = FIELDS)
            if (fields.size != FIELDS) return null
            val epoch = fields[0].toDoubleOrNull() ?: return null
            val partition = fields[1].toIntOrNull() ?: return null
            val head = fields[2].toLongOrNull() ?: return null
            val checkpoint = fields[3].toLongOrNull() ?: return null
            val commit = fields[4].toLongOrNull() ?: return null
            return Sample(
                at = (epoch * MILLIS_PER_SECOND).toLong(),
                partition = partition,
                head = head,
                checkpoint = checkpoint.takeIf { it >= 0 },
                oldestCommit = commit.takeIf { it >= 0 },
                oldestAggregate = fields[5].takeIf { commit >= 0 && it.isNotEmpty() },
            )
        }

        private fun rounds(samples: List<Sample>): List<Round> =
            samples.groupBy { it.at }.toSortedMap().map { (at, reads) -> Round(at, reads.associateBy { it.partition }) }

        /**
         * When the stream caught up after the load: the first round from [stoppedAt] on in which every partition's
         * checkpoint reached the head it had in the first round once the drain ended ([drainedAt]), so every commit of
         * the load was published; null while none did.
         */
        fun caughtUpAt(samples: List<Sample>, stoppedAt: Long, drainedAt: Long): Long? {
            val rounds = rounds(samples)
            val target = rounds.firstOrNull { it.at >= drainedAt } ?: return null
            return rounds.firstOrNull { round ->
                round.at >= stoppedAt && target.partitions.keys.all { round.checkpoint(it) >= target.head(it) }
            }?.at
        }

        /**
         * The run's figures from [samples], against the measured phase from [startedAt] to [stoppedAt]:
         *
         * - `series`: from the last round at or before the phase's start to the first at or after its end, each
         *   partition's entries appended and published a second (the head's and the checkpoint's growth) and, at each
         *   round, its lag in entries (head less checkpoint) and in seconds (the oldest commit above the checkpoint);
         *   `t` is the round's second in the phase;
         * - `appendedPerSecond` and `publishedPerSecond` over that span, all partitions together, and the
         *   `busiestPartition`'s (the most appended): one aggregate's when a run loads one;
         * - `maxLag`, the worst partition's lag in entries, and `maxLagSeconds`, the oldest commit waiting, with its
         *   partition and aggregate: the worst aggregate's lag;
         * - `catchUpSeconds`: from the phase's end to [caughtUpAt], null when the stream had not caught up when the
         *   reads stopped, `lagAtEnd` then saying how many entries were still above the checkpoints;
         * - `checkpointed`: whether the sink saved any checkpoint; without one every lag is the head.
         */
        fun summary(samples: List<Sample>, startedAt: Long, stoppedAt: Long, drainedAt: Long): Map<String, Any?> {
            val rounds = rounds(samples)
            if (rounds.isEmpty()) return mapOf("missing" to "no read of the published stream answered")
            val first = rounds.lastOrNull { it.at <= startedAt } ?: rounds.first()
            val last = rounds.firstOrNull { it.at >= stoppedAt } ?: rounds.last()
            val span = rounds.filter { it.at in first.at..last.at }
            val partitions = rounds.flatMap { it.partitions.keys }.distinct().sorted()
            val seconds = (last.at - first.at) / MILLIS_PER_SECOND
            fun t(round: Round) = (round.at - startedAt) / MILLIS_PER_SECOND
            fun rate(grown: Long) = if (seconds > 0) grown / seconds else 0.0
            val steps = span.zipWithNext().filter { (earlier, later) -> later.at > earlier.at }
            val reads = span.drop(1).flatMap { round -> round.partitions.values.map { round to it } }
            val busiest = partitions.maxByOrNull { last.head(it) - first.head(it) }
            val caughtUp = caughtUpAt(samples, stoppedAt, drainedAt)
            return buildMap {
                put("partitions", partitions.size)
                put("checkpointed", samples.any { it.checkpoint != null })
                put("appendedPerSecond", rate(partitions.sumOf { last.head(it) - first.head(it) }))
                put("publishedPerSecond", rate(partitions.sumOf { last.checkpoint(it) - first.checkpoint(it) }))
                busiest?.let {
                    put(
                        "busiestPartition",
                        mapOf(
                            "partition" to it,
                            "appendedPerSecond" to rate(last.head(it) - first.head(it)),
                            "publishedPerSecond" to rate(last.checkpoint(it) - first.checkpoint(it)),
                        ),
                    )
                }
                putAll(worst(reads, startedAt))
                put("catchUpSeconds", caughtUp?.let { (it - stoppedAt) / MILLIS_PER_SECOND })
                if (caughtUp == null) put("lagAtEnd", rounds.last().partitions.values.sumOf { it.lag })
                put("readsUntilSecond", t(rounds.last()))
                put("series", series(steps, partitions, startedAt))
            }
        }

        /** The worst partition's lag in entries, and the oldest commit waiting with its partition and aggregate. */
        private fun worst(reads: List<Pair<Round, Sample>>, startedAt: Long): Map<String, Any?> = buildMap {
            fun t(round: Round) = (round.at - startedAt) / MILLIS_PER_SECOND
            reads.maxByOrNull { (_, read) -> read.lag }?.let { (round, read) ->
                put("maxLag", mapOf("entries" to read.lag, "partition" to read.partition, "t" to t(round)))
            }
            reads.maxByOrNull { (_, read) -> read.lagSeconds }?.let { (round, read) ->
                put(
                    "maxLagSeconds",
                    mapOf(
                        "seconds" to read.lagSeconds,
                        "partition" to read.partition,
                        "aggregate" to read.oldestAggregate,
                        "t" to t(round),
                    ),
                )
            }
        }

        /** Each partition's appends and publications a second over [steps], and its lags at each step's end. */
        private fun series(steps: List<Pair<Round, Round>>, partitions: List<Int>, startedAt: Long): Map<String, Any?> =
            mapOf(
                "t" to steps.map { (_, later) -> (later.at - startedAt) / MILLIS_PER_SECOND },
                "partitions" to partitions.map { partition ->
                    fun perSecond(of: Round.(Int) -> Long) = steps.map { (earlier, later) ->
                        (later.of(partition) - earlier.of(partition)) / ((later.at - earlier.at) / MILLIS_PER_SECOND)
                    }
                    mapOf(
                        "partition" to partition,
                        "appended" to perSecond { head(it) },
                        "published" to perSecond { checkpoint(it) },
                        "lag" to steps.map { (_, later) -> later.partitions[partition]?.lag },
                        "lagSeconds" to steps.map { (_, later) -> later.partitions[partition]?.lagSeconds },
                    )
                },
            )
    }
}
