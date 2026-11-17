# The measurement-only probe on the fix's PartitionRunner (never pushed): each partition's catch-up as it opens,
# under the cluster's logger name. python3 probe.py <tree>
import sys
p = f'{sys.argv[1]}/core/runtime/src/main/kotlin/me/ahoo/wow/runtime/subscription/PartitionRunner.kt'
s = open(p).read()
def sub(o, n):
    global s
    assert s.count(o) == 1, o[:70]
    s = s.replace(o, n)
cursors = '" + workers.joinToString { "${it.subscription.name}=${it.cursor}" }'
sub("""        if (permits == null) {
            permitted.complete(Unit)
            opened.complete(Unit)
            return
        }""", """        if (permits == null) {
            permitted.complete(Unit)
            opened.complete(Unit)
            readers.launch {
                val head = source.checkpointOf(SubscriptionStart.End, partition)
                val behindBy = head - (workers.minOfOrNull { it.cursor } ?: head)
                val since = TimeSource.Monotonic.markNow()
                awaitCaughtUp(head)
                catchUpProbe.info {
                    "[catchup] $partition no bound, behind by $behindBy, caught up in ${since.elapsedNow().inWholeMilliseconds}ms"
                }
            }
            return
        }""")
sub("""        if (workers.none { it.cursor < target }) {
            permitted.complete(Unit)
            opened.complete(Unit)
            return
        }
        opened.complete(Unit)
        if (caughtUpWithinPage(target)) {
            permitted.complete(Unit)
            return
        }
        permits.acquire()
        try {
            permitted.complete(Unit)
            awaitCaughtUp(target)
        } finally {
            permits.release()
        }""", """        if (workers.none { it.cursor < target }) {
            catchUpProbe.info { "[catchup] $partition not behind its head $target" }
            permitted.complete(Unit)
            opened.complete(Unit)
            return
        }
        val behindBy = target - workers.minOf { it.cursor }
        val since = TimeSource.Monotonic.markNow()
        opened.complete(Unit)
        if (caughtUpWithinPage(target)) {
            catchUpProbe.info {
                "[catchup] $partition within its page, behind by $behindBy, caught up in ${since.elapsedNow().inWholeMilliseconds}ms"
            }
            permitted.complete(Unit)
            return
        }
        val asked = TimeSource.Monotonic.markNow()
        permits.acquire()
        val waited = asked.elapsedNow().inWholeMilliseconds
        catchUpProbe.info { "[catchup] $partition permit after ${waited}ms, behind by $behindBy to $target: """ + cursors + """ }
        val held = TimeSource.Monotonic.markNow()
        try {
            permitted.complete(Unit)
            awaitCaughtUp(target)
        } finally {
            permits.release()
            catchUpProbe.info { "[catchup] $partition caught up in ${held.elapsedNow().inWholeMilliseconds}ms" }
        }""")
sub("""/** How often a partition holding a catch-up permit looks whether it has caught up. */""",
"""/** The measurement probe's catch-up lines, under the cluster's logger name so its INFO level shows them. */
private val catchUpProbe = io.github.oshai.kotlinlogging.KotlinLogging.logger("me.ahoo.wow.runtime.cluster.CatchUpProbe")

/** How often a partition holding a catch-up permit looks whether it has caught up. */""")
open(p, 'w').write(s)
