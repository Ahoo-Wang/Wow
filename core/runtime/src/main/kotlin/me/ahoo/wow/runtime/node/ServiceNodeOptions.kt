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

package me.ahoo.wow.runtime.node

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.WritePolicies
import me.ahoo.wow.runtime.ExecutorOptions
import me.ahoo.wow.runtime.StateCacheOptions
import me.ahoo.wow.runtime.access.AccessStampOptions
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.runtime.admission.CommandValidator
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.ledger.LedgerPublishing
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.log.HandoffOptions
import me.ahoo.wow.runtime.schedule.JobOptions
import me.ahoo.wow.runtime.snapshot.SnapshotOptions
import me.ahoo.wow.runtime.state.SharedStateOptions
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.timer.TimerOptions
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.tenant.TenantPolicy
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.TopicAdmin
import me.ahoo.wow.spi.transport.TopicProducer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where a node runs a command. */
enum class Routing {
    /** On the owner of its log partition, forwarded over the cluster channel; the default. */
    PARTITIONED,

    /** On the node it arrived at, with optimistic concurrency: for a single node. */
    LOCAL,
}

/**
 * The tuning of a [ServiceNode] (names the host's keys for them).
 *
 * - [cluster]: partition ownership and the cluster channel; [routing]: where commands run.
 * - [wait]: the waits ([WaitOptions]); its default timeout is that of every command whose request names none,
 *   whichever edge it came through, capped by its maximum. [ServiceNode.stop] also waits at most that maximum for the
 *   waits registered on the node to end: none of them outlives it.
 * - [subscription]: the subscriptions of every context, the built-in ones included, with the starts the host
 *   configures for some of them ([SubscriptionOptions.starts]): a name no subscription of the node has
 *   fails its assembly.
 * - [appendQueue]: the partition append queue, on by default.
 * - [executor]: how the node's executors take commands ([ExecutorOptions]): each aggregate's lane, its batches, and
 *   the commands in flight on the node.
 * - [stateCache]: what each context's state cache keeps between commands ([StateCacheOptions]).
 * - [sharedStates]: the bounds of each context's shared states, shared by its state consumers.
 * - [timers]: the timer runner of each context that has a timer store.
 * - [schedules]: the host's settings of each job, by job name (`wow.schedules.<job>.*`): its rule, and the retries
 *   of the subscriptions that run it; a name no `@OnSchedule` handler of the node runs fails its assembly, and so
 *   does [subscription] configuring a job's subscription by its name.
 * - [snapshots]: the snapshot maintenance of each context: how often it claims the state revisions,
 *   rebuilds in place and checks the backfills, and the bounds of a rebuild.
 * - [topicRouted]: the aggregate types whose commands the topic router takes: an
 *   admitted command of one of them is written to its context's command topic, accepted once the broker acknowledged
 *   it, and executed by the owner of its log partition from there. Each must be a type the node serves, the node
 *   needs a producer ([SourceTopics.producer]), and each of their contexts takes the commands of its command topic
 *   whether or not [SourceTopics.intake] is set. None by default.
 * - [handoff]: the bounds of the hand-over of the node's own commits to its subscriptions' tails in memory
 * ([HandoffOptions]).
 * - [access]: how each context's built-in subscription `wow.access` stamps the access attributes over a history
 *   ([AccessStampOptions]).
 * - [ledger]: where the node publishes its failure ledger: the ops context whose `parked` aggregate
 *   records each entry, through its command topic, which needs a producer ([SourceTopics.producer]). Not published by
 *   default.
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class ServiceNodeOptions @InternalWowApi constructor(
    val cluster: ClusterOptions = ClusterOptions(),
    val routing: Routing = Routing.PARTITIONED,
    val wait: WaitOptions = WaitOptions(),
    val subscription: SubscriptionOptions = SubscriptionOptions(),
    val appendQueue: AppendQueueOptions = AppendQueueOptions(),
    val executor: ExecutorOptions = ExecutorOptions(),
    val stateCache: StateCacheOptions = StateCacheOptions(),
    val sharedStates: SharedStateOptions = SharedStateOptions(),
    val timers: TimerOptions = TimerOptions(),
    val schedules: Map<String, JobOptions> = emptyMap(),
    val topicRouted: Set<AggregateType> = emptySet(),
    val ledger: LedgerPublishing? = null,
    /** The snapshot maintenance of each context: claims, in-place rebuilds, backfills. */
    val snapshots: SnapshotOptions = SnapshotOptions(),
    val handoff: HandoffOptions = HandoffOptions(),
    val access: AccessStampOptions = AccessStampOptions(),
) {
    /**
     * The longest [ServiceNode.stop] takes with these options, computed from them; nothing enforces it, it is the time
     * a host leaves the stop (Spring Boot's shutdown phase, a pod's grace period). Its steps, in order:
     *
     * - **Hand-off** (HANDED_OFF), each context and topic at once, each partition at once: the command intake's
     *   partition stopped ([SubscriptionOptions.stopTimeout]), the commands in flight drained
     *   ([ClusterOptions.drainTimeout]), then every log group's subscriptions stopped at once (one more stop timeout):
     *   `2 × stopTimeout + drainTimeout`.
     * - **Waits** (WAITS_ENDED): until the stop's start plus [WaitOptions.maxTimeout], so nothing when the hand-off ran
     *   past it. Together with the hand-off: `max(maxTimeout, 2 × stopTimeout + drainTimeout)`.
     * - **Gateway** (GATEWAY_CLOSED): the admissions under way and the detached commands, at most one drain timeout.
     * - **Executions** (EXECUTIONS_ENDED): what is left of the stop's start plus the wait maximum, at least one drain
     *   timeout; past the first two terms, one drain timeout.
     * - **Executors** (EXECUTORS_CLOSED): at most one drain timeout.
     *
     * So `max(maxTimeout, 2 × stopTimeout + drainTimeout) + 3 × drainTimeout`: 90 seconds with the defaults. Not in
     * it: the store calls of the stop (the membership's leave, each lease's release, the checkpoints' flush, the logs'
     * close), each bounded by its store's own timeout.
     */
    val stopBound: Duration
        get() = maxOf(wait.maxTimeout, subscription.stopTimeout * 2 + cluster.drainTimeout) + cluster.drainTimeout * 3

    /** This ServiceNodeOptions with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        cluster: ClusterOptions = this.cluster,
        routing: Routing = this.routing,
        wait: WaitOptions = this.wait,
        subscription: SubscriptionOptions = this.subscription,
        appendQueue: AppendQueueOptions = this.appendQueue,
        executor: ExecutorOptions = this.executor,
        stateCache: StateCacheOptions = this.stateCache,
        sharedStates: SharedStateOptions = this.sharedStates,
        timers: TimerOptions = this.timers,
        schedules: Map<String, JobOptions> = this.schedules,
        topicRouted: Set<AggregateType> = this.topicRouted,
        ledger: LedgerPublishing? = this.ledger,
        snapshots: SnapshotOptions = this.snapshots,
        handoff: HandoffOptions = this.handoff,
        access: AccessStampOptions = this.access,
    ): ServiceNodeOptions = ServiceNodeOptions(
        cluster,
        routing,
        wait,
        subscription,
        appendQueue,
        executor,
        stateCache,
        sharedStates,
        timers,
        schedules,
        topicRouted,
        ledger,
        snapshots,
        handoff,
        access,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is ServiceNodeOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ServiceNodeOptions(cluster=$cluster, routing=$routing, wait=$wait, subscription=$subscription, " +
            "appendQueue=$appendQueue, executor=$executor, stateCache=$stateCache, sharedStates=$sharedStates, " +
            "timers=$timers, schedules=$schedules, topicRouted=$topicRouted, ledger=$ledger, snapshots=$snapshots, " +
            "handoff=$handoff, access=$access)"

    private fun valueFields(): List<Any?> = listOf(
        cluster, routing, wait, subscription, appendQueue, executor, stateCache, sharedStates, timers, schedules,
        topicRouted, ledger, snapshots, handoff, access,
    )
}

/**
 * The admission plug-ins of a node's [me.ahoo.wow.runtime.admission.CommandGateway], and the
 * [tenants] of its aggregate types. [ids] are the node's id generators: admission's
 * command and aggregate ids, the wait ids, and the ids its decide functions and sagas take. [authorizer] is null when
 * the application gives none ([me.ahoo.wow.runtime.admission.CommandGateway.authorizes]). [policies] are the access
 * policies the node's kernel holds each command to, before and after deciding.
 */
@InternalWowApi
data class AdmissionPlugins(
    val authorizer: CommandAuthorizer? = null,
    val transformers: List<CommandTransformer> = emptyList(),
    val validators: List<CommandValidator> = emptyList(),
    val ids: IdGenerators = IdGenerators.DEFAULT,
    val tenants: TenantPolicy = TenantPolicy.CALLERS,
    /** The access policy of each aggregate type its commands are held to; none, the default rule decides. */
    val policies: WritePolicies = WritePolicies.NONE,
)

/**
 * The topics of a node's broker, each named after [prefix], the prefix every service on the broker writes under:
 *
 * - **Published streams**: the node reads other contexts' streams from [source], each topic named as
 *   the publishing context's Manifest names it (`PublishedManifest.topic`, [topicOf]). Partitions added to a topic
 *   it reads are read too, within [partitionRefresh] of the source seeing them.
 * - **Commands across contexts**, with a [producer]: a saga's command to an aggregate of
 *   a context outside its own is sent to that context's command topic, `wow.<context>.commands` ([commandsOf]), and
 *   the receipts of those another context rejects are read from the saga's context's receipts topic,
 *   `wow.<context>.receipts` ([receiptsOf]).
 * - **Command intake**, when [intake] is set: each context the node hosts executes the commands of its
 *   command topic, whose partitions must be its log's, and sends the receipts of those it rejects through
 *   [producer].
 * - **Topic management**: at its start, the node validates or creates the topics its contexts own as
 *   [ddl] says, through [admin], which any [ddl] but [Ddl.NONE] needs.
 */
class SourceTopics @InternalWowApi constructor(
    val source: PartitionedSource,
    val prefix: String = "",
    val producer: TopicProducer? = null,
    val intake: Boolean = false,
    val admin: TopicAdmin? = null,
    val ddl: Ddl = Ddl.NONE,
    val partitionRefresh: Duration = DEFAULT_PARTITION_REFRESH,
) {
    init {
        require(!intake || producer != null) { "A command intake sends receipts: it needs a producer." }
        require(!ddl.checks || admin != null) { "Topics are validated or created through an admin: $ddl." }
        require(partitionRefresh.isPositive()) { "The partitions of a topic are refreshed at a positive interval." }
    }

    /** The topic of a stream the Manifest publishes on [topic]. */
    fun topicOf(topic: String): String = prefix + topic

    /** The command topic of [context]. */
    fun commandsOf(context: String): String = "${prefix}wow.$context.commands"

    /** The receipts topic of [context]. */
    fun receiptsOf(context: String): String = "${prefix}wow.$context.receipts"

    /** This SourceTopics with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        source: PartitionedSource = this.source,
        prefix: String = this.prefix,
        producer: TopicProducer? = this.producer,
        intake: Boolean = this.intake,
        admin: TopicAdmin? = this.admin,
        ddl: Ddl = this.ddl,
        partitionRefresh: Duration = this.partitionRefresh,
    ): SourceTopics = SourceTopics(source, prefix, producer, intake, admin, ddl, partitionRefresh)

    override fun equals(other: Any?): Boolean =
        this === other || other is SourceTopics && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "SourceTopics(" +
        "source=$source, prefix=$prefix, producer=$producer, intake=$intake, admin=$admin, ddl=$ddl, partitionRefresh=$partitionRefresh)"

    private fun valueFields(): List<Any?> = listOf(source, prefix, producer, intake, admin, ddl, partitionRefresh)

    companion object {
        /** A SourceTopics of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            source: PartitionedSource,
            prefix: String = "",
            producer: TopicProducer? = null,
            intake: Boolean = false,
            admin: TopicAdmin? = null,
            ddl: Ddl = Ddl.NONE,
            partitionRefresh: Duration = DEFAULT_PARTITION_REFRESH
        ): SourceTopics = SourceTopics(
            source,
            prefix,
            producer,
            intake,
            admin,
            ddl,
            partitionRefresh
        )

        val DEFAULT_PARTITION_REFRESH: Duration = 30.seconds
    }
}
