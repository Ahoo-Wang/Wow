# Ports 62ea68f0f9 (D399's rehearsal instrumentation: live orders, TIMING lines) onto a tree, without the workflow
# file (the V10 side already runs on JDK 25 there). python3 ci-repro-port.py <tree> <max-catch-ups or "">
import sys
T, bound = sys.argv[1], sys.argv[2]
def patch(path, pairs):
    p = f'{T}/{path}'; s = open(p).read()
    for o, n in pairs:
        assert s.count(o) == 1, (path, o[:70]); s = s.replace(o, n)
    open(p, 'w').write(s)
patch('core/runtime/src/main/kotlin/me/ahoo/wow/runtime/node/NodeLifecycle.kt', [
("""    private suspend fun join(): Unit = coroutineScope {
        val reads = Job()""", """    private suspend fun join(): Unit = coroutineScope {
        val timingStart = System.nanoTime()
        val reads = Job()"""),
("""        topicSubscriptions.values.forEach { it.start() }
        ledgerPublisher?.start()
    }
""", """        topicSubscriptions.values.forEach { it.start() }
        ledgerPublisher?.start()
        logger.warn { "TIMING activate node=$node ms=${(System.nanoTime() - timingStart) / 1_000_000}" }
    }
"""),
("""    private fun work() {
        startJobs()""", """    private fun work() {
        timeCatchUp()
        startJobs()"""),
("""    /** Runs the node's background work: the startup of the SYSTEM jobs; cancelled when the node stops. */""",
"""    /** ci-repro: the subscriptions' lag behind the logs' heads after the join, every second until it is 0. */
    private fun timeCatchUp() {
        val started = System.nanoTime()
        background.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                var lag = 0L
                var positions = 0
                contexts.values.forEach { context ->
                    context.logGroups.forEach { group ->
                        val heads = HashMap<Int, Long>()
                        group.subscriptions.positions().forEach { p ->
                            positions++
                            val head = heads.getOrPut(p.partition) { group.log.head(p.partition) ?: 0L }
                            lag += (head - p.watermark).coerceAtLeast(0)
                        }
                    }
                }
                val ms = (System.nanoTime() - started) / 1_000_000
                logger.warn { "TIMING lag node=$node sinceJoinMs=$ms positions=$positions lag=$lag" }
                if (lag == 0L && positions > 0) {
                    logger.warn { "TIMING caughtUp node=$node ms=$ms" }
                    break
                }
            }
        }
    }

    /** Runs the node's background work: the startup of the SYSTEM jobs; cancelled when the node stops. */"""),
])
patch('test/migration-rehearsal/src/main/kotlin/me/ahoo/wow/rehearsal/driver/RehearsalDriver.kt', [
("import java.util.concurrent.ConcurrentHashMap\n", "import java.util.concurrent.ConcurrentHashMap\nimport java.util.concurrent.ConcurrentLinkedQueue\n"),
("""        "traffic" -> traffic()
""", """        "traffic" -> traffic()
        "live" -> live()
"""),
("""    private fun endpoints(): Endpoints = Endpoints.of(required("version"), required("base"))
""", """    private fun endpoints(): Endpoints = Endpoints.of(required("version"), required("base"))

    /**
     * ci-repro: creates new orders at --rate a second for --seconds, open loop, each on a virtual thread; writes one
     * JSON line a second to --out (the second, the commands completed in it, those failed, latency p50, p99, max) and
     * prints the totals.
     */
    private fun live(): Int {
        val endpoints = endpoints()
        val rate = required("rate").toLong()
        val seconds = required("seconds").toLong()
        val samples = ConcurrentLinkedQueue<LongArray>()
        val errors = ConcurrentHashMap<String, AtomicLong>()
        val started = System.nanoTime()
        val interval = NANOS_PER_SECOND / rate
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            var number = 0L
            while (number * interval < seconds * NANOS_PER_SECOND) {
                val wait = started + number * interval - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / NANOS_PER_MILLI, (wait % NANOS_PER_MILLI).toInt())
                val n = ++number
                executor.submit {
                    val sent = System.nanoTime()
                    val ok = try {
                        val ref = OrderRef("n-live-%07d".format(n), "t0", "c-000000")
                        val outcome = endpoints.createOrder(ref, createBody(Random(n), 1))
                        if (!outcome.ok) {
                            errors.computeIfAbsent(outcome.error.orEmpty().take(ERROR_KEY)) { AtomicLong() }
                                .incrementAndGet()
                        }
                        outcome.ok
                    } catch (failure: Exception) {
                        errors.computeIfAbsent(failure.javaClass.simpleName) { AtomicLong() }.incrementAndGet()
                        false
                    }
                    val latency = (System.nanoTime() - sent) / NANOS_PER_MILLI
                    samples += longArrayOf(System.currentTimeMillis(), latency, if (ok) 1 else 0)
                }
            }
        }
        val all = samples.toList()
        fun pct(sorted: List<Long>, p: Int) =
            if (sorted.isEmpty()) 0 else sorted[(sorted.size * p / 100).coerceAtMost(sorted.size - 1)]
        File(required("out")).writeText(
            all.groupBy { it[0] / 1000 }.toSortedMap().entries.joinToString("\\n", postfix = "\\n") { (second, s) ->
                val latencies = s.map { it[1] }.sorted()
                ""\"{"second":${second * 1000},"completed":${s.size},"failed":${s.count { it[2] == 0L }},""\" +
                    ""\""p50":${pct(latencies, 50)},"p99":${pct(latencies, 99)},"max":${latencies.last()}}""\"
            }
        )
        val latencies = all.map { it[1] }.sorted()
        val errorList = errors.entries.sortedByDescending { it.value.get() }.take(MAX_PRINTED)
            .joinToString(",") { (error, count) -> "\\"${error.replace("\\"", "'")}\\":${count.get()}" }
        println(
            ""\"{"command":"live","rate":$rate,"commands":${all.size},"failed":${all.count { it[2] == 0L }},""\" +
                ""\""p50":${pct(latencies, 50)},"p95":${pct(latencies, 95)},"p99":${pct(latencies, 99)},""\" +
                ""\""max":${latencies.lastOrNull() ?: 0},"errors":{$errorList}}""\"
        )
        return OK
    }
"""),
])
bound_arg = f'--wow.subscription.max-catch-ups={bound} ' if bound else ''
patch('test/migration-rehearsal/rehearsal/rehearse.sh', [
("""      --wow.node.standby="$([ "$standby" = standby ] && echo true || echo false)" \\
      >>"$WORK/v10.out" 2>&1 &""", f"""      {bound_arg}--wow.node.standby="$([ "$standby" = standby ] && echo true || echo false)" \\
      >>"$WORK/v10.out" 2>&1 &"""),
("""      --wow.node.standby="$([ "$standby" = standby ] && echo true || echo false)" \\
      --logging.level.root=warn \\""", f"""      {bound_arg}--wow.node.standby="$([ "$standby" = standby ] && echo true || echo false)" \\
      --logging.level.root=warn \\"""),
("""stop_hosts() { stop_pid v10; stop_pid views; }
""", """stop_hosts() { stop_pid v10; stop_pid views; }
# ci-repro: waits, at most 900 s, until both hosts have logged their first catch-up (TIMING caughtUp).
await_caught_up() {
  local deadline=$(($(date +%s) + 900))
  until [ "$(grep -c 'TIMING caughtUp' "$WORK/v10.out" || true)" -ge 1 ] &&
    [ "$(grep -c 'TIMING caughtUp' "$WORK/views.out" || true)" -ge 1 ]; do
    if [ "$(date +%s)" -ge "$deadline" ]; then echo '{"caughtUp":false}'; return 0; fi
    sleep 2
  done
  echo '{"caughtUp":true}'
}
"""),
("""step offline-start 0 start_hosts
record offline-write-stop "$offline_started" 0
""", """step offline-start 0 start_hosts
# ci-repro: live orders at a steady rate on the V10 host while its subscriptions catch up on the imported history.
driver live --version=v10 --base="$V10_URL" --seconds="${LIVE_SECONDS:-120}" --rate="${LIVE_RATE:-50}" \\
  --out="$WORK/offline-live.jsonl" >"$WORK/offline-live.log" 2>&1 &
live_pid=$!
live_started="$(now_ms)"
record offline-write-stop "$offline_started" 0
"""),
("""not_walked offline-not-walked "$offline_lines"
stop_hosts
TARGET_DB=example""", """not_walked offline-not-walked "$offline_lines"
wait "$live_pid" || true
record offline-live "$live_started" 0 "$(grep -E '^\\{.*\\}$' "$WORK/offline-live.log" | tail -1 || true)"
step offline-caught-up 0 await_caught_up
stop_hosts
TARGET_DB=example"""),
])
open(f'{T}/test/migration-rehearsal/rehearsal/run.json', 'w').write('{\n  "orders": 50000,\n  "migrate_in": "both"\n}\n')
print('ported', bound or 'default')
