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
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.Ticked
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.compiler.model.source.ModelProviderSource
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.contextOf
import me.ahoo.wow.kernel.schedule.JobState
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.manifest.JobManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionSource
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URLClassLoader

/**
 * A saga's `@OnSchedule` handlers (decision D198): the generated binding runs each job's ticks through the handler,
 * the Manifest's subscription records the job, and a handler or job that cannot run fails the compilation.
 */
@OptIn(ExperimentalCompilerApi::class)
class ScheduleJobBindingTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "billing",
        ModelSymbolProcessorProvider.MODULE_OPTION to "billing-domain",
    )

    private val billing = SourceFile.kotlin(
        "Billing.kt",
        """
        package sample.billing

        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.OnSchedule
        import me.ahoo.wow.api.annotation.Retry
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.aggregate.AggregateType
        import me.ahoo.wow.api.client.CommandRequest
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.api.schedule.JobScope
        import me.ahoo.wow.api.schedule.Misfire
        import me.ahoo.wow.api.schedule.ScheduleTick
        import me.ahoo.wow.api.subscribe.SagaContext
        import me.ahoo.wow.api.subscribe.StateEvent

        data class IssueInvoices(val tenantId: String, val slot: Long)

        @Saga
        class BillingSaga {
            @OnSchedule(name = "invoice", cron = "0 0 0 1 * *", zone = "Asia/Shanghai", scope = JobScope.TENANT)
            @Retry(maxAttempts = 2)
            fun invoice(tick: ScheduleTick, context: SagaContext): CommandRequest<IssueInvoices> =
                CommandRequest.to(AggregateType("billing", "account").ref(tick.tenantId, "all"), IssueInvoices(tick.tenantId, tick.slot))

            @OnSchedule(name = "sweep", cron = "0 */5 * * * *", sharded = true, misfire = Misfire.CATCH_UP)
            suspend fun sweep(tick: ScheduleTick): List<CommandRequest<IssueInvoices>> = emptyList()

            @OnEvent
            fun onEvent(event: StateEvent<*>): List<CommandRequest<IssueInvoices>> = emptyList()
        }

        @Saga
        object Nightly {
            @OnSchedule(name = "nightly", cron = "@daily")
            fun nightly(tick: ScheduleTick): List<CommandRequest<IssueInvoices>> = emptyList()
        }
        """.trimIndent(),
    )

    private fun tick(ref: AggregateRef, job: String, slot: Long): StateEvent<JobState> = StateEvent(
        ref = ref,
        version = 1,
        commandId = "c-1",
        requestId = "r-1",
        commandType = ScheduleJobs.TICK,
        commitTime = 0,
        events = listOf(Ticked("$ref#tick@1", slot, 0, 0)),
        state = JobState().apply { this.job = job },
    )

    @Test
    fun `the binding should run the job's ticks through the handler, and the Manifest record the job`(): Unit =
        runBlocking {
            val (compilation, result) = compileModel("generate", options, billing)
            result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
            val services = compilation.workingDir.walkTopDown()
                .first { it.isFile && it.name == ModelProviderSource.MODEL_PROVIDER }
            val loader = URLClassLoader(
                arrayOf(services.parentFile.parentFile.parentFile.toURI().toURL()),
                result.classLoader,
            )
            val bindings = ModelProvider.load(loader).single().processors(Dependencies.NONE).associateBy { it.name }
            val invoice = bindings.getValue("BillingSaga.invoice")
            invoice.job.assert().isEqualTo(
                ScheduleJob("invoice", ScheduleRule.Cron("0 0 0 1 * *", "Asia/Shanghai"), JobScope.TENANT),
            )
            invoice.kind.assert().isEqualTo(FunctionKind.SAGA)
            invoice.retry.assert().isEqualTo(HandlerRetry(maxAttempts = 2))
            // The context the job runs in, as the compiler knew it, for the test DSL's schedule specification.
            invoice.context.assert().isEqualTo("billing")
            bindings.getValue("BillingSaga.onEvent").context.assert().isNull()
            bindings.getValue("BillingSaga.sweep").job.assert().isEqualTo(
                ScheduleJob("sweep", ScheduleRule.Cron("0 */5 * * * *", misfire = Misfire.CATCH_UP), sharded = true),
            )
            bindings.getValue("Nightly.nightly").job?.rule?.expression.assert().isEqualTo("@daily")
            bindings.getValue("BillingSaga.onEvent").job.assert().isNull()

            val ref = ScheduleJobs.typeOf("billing").ref("acme", "invoice")
            val event = tick(ref, "invoice", 3)
            val derived = invoice.invoke(event, contextOf(FunctionKind.SAGA, invoice.name, event))
            derived.single().body.toString().assert().isEqualTo("IssueInvoices(tenantId=acme, slot=3)")
            val sweep = bindings.getValue("BillingSaga.sweep")
            val shard = tick(ref, "sweep", 1)
            sweep.invoke(shard, contextOf(FunctionKind.SAGA, sweep.name, shard)).assert().isEmpty()

            val fragment = compilation.workingDir.walkTopDown()
                .first { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
            val subscriptions = ManifestTree.read(jacksonObjectMapper().readValue(fragment, Map::class.java))
                .contexts.single().subscriptions.associateBy { it.name }
            val manifest = subscriptions.getValue("BillingSaga.invoice")
            manifest.kind.assert().isEqualTo(SubscriptionKind.SAGA)
            manifest.source.assert().isEqualTo(SubscriptionSource("billing", listOf(ScheduleJobs.AGGREGATE)))
            manifest.job.assert().isEqualTo(JobManifest("invoice", "0 0 0 1 * *", "Asia/Shanghai", "tenant"))
            subscriptions.getValue("BillingSaga.sweep").job.assert()
                .isEqualTo(JobManifest("sweep", "0 */5 * * * *", sharded = true, misfire = "catch-up"))
            subscriptions.getValue("BillingSaga.onEvent").job.assert().isNull()
        }

    private val broken = SourceFile.kotlin(
        "Broken.kt",
        """
        package sample.billing

        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.OnSchedule
        import me.ahoo.wow.api.annotation.Reactor
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.client.CommandRequest
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.api.schedule.JobScope
        import me.ahoo.wow.api.schedule.ScheduleTick
        import me.ahoo.wow.api.subscribe.SubscriptionContext
        import me.ahoo.wow.api.subscribe.StateEvent

        @Reactor
        object Watcher {
            @OnSchedule(name = "watch", cron = "0 0 * * * *")
            fun watch(tick: ScheduleTick) = Unit
        }

        @Saga
        object Wrong {
            @OnSchedule(name = "bad name", cron = "0 0 *", zone = "Mars/Olympus", scope = JobScope.TENANT, sharded = true)
            fun misnamed(tick: ScheduleTick) = Unit

            @OnSchedule(name = "twice", cron = "0 0 3 * * *")
            @OnEvent
            fun both(tick: ScheduleTick) = Unit

            @OnSchedule(name = "params", cron = "0 0 3 * * *")
            fun params(event: StateEvent<*>, context: SubscriptionContext, extra: String) = Unit

            @OnSchedule(name = "returns", cron = "0 0 3 * * *")
            fun returns(tick: ScheduleTick): String = ""

            @OnSchedule(name = "same", cron = "0 0 3 * * *")
            fun first(tick: ScheduleTick) = Unit

            @OnSchedule(name = "same", cron = "0 0 4 * * *")
            fun second(tick: ScheduleTick) = Unit

            @OnSchedule(name = "hidden", cron = "0 0 3 * * *")
            private fun hidden(tick: ScheduleTick) = Unit
        }
        """.trimIndent(),
    )

    @Test
    fun `a job handler or a job that cannot run should fail the compilation`() {
        val (_, result) = compileModel("generate", options, broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("handler Watcher.watch is an @OnSchedule job of a @Reactor: only a @Saga runs jobs")
            .contains("handler Wrong.misnamed names its job \"bad name\"")
            .contains("handler Wrong.misnamed runs by \"0 0 *\", of 3 fields")
            .contains("handler Wrong.misnamed runs in the time zone \"Mars/Olympus\", which is not one")
            .contains("handler Wrong.misnamed is a sharded TENANT job: only a SYSTEM job is sharded")
            .contains("handler Wrong.both is @OnSchedule and @OnEvent or @OnRejected")
            .contains("handler Wrong.params must take a me.ahoo.wow.api.schedule.ScheduleTick first")
            .contains("handler Wrong.params takes me.ahoo.wow.api.subscribe.SubscriptionContext")
            .contains("handler Wrong.params takes 3 parameters")
            .contains("handler Wrong.returns returns kotlin.String")
            .contains("processor Wrong declares the job same twice, differently")
            .contains("handler Wrong.hidden is private")
    }

    @Test
    fun `a saga of jobs only needs no @OnEvent handler`() {
        val jobsOnly = SourceFile.kotlin(
            "Jobs.kt",
            """
            package sample.billing

            import me.ahoo.wow.api.annotation.OnSchedule
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.annotation.Sends
            import me.ahoo.wow.api.client.CommandRequest
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.api.schedule.ScheduleTick
            import reactor.core.publisher.Flux

            data class Sweep(val slot: Long)

            @Saga
            object Jobs {
                @OnSchedule(name = "reactive", cron = "0 0 3 * * *")
                @Sends(derives = [Sweep::class])
                fun reactive(tick: ScheduleTick): Flux<CommandRequest<*>> = Flux.empty()
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, jobsOnly)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
    }
}
