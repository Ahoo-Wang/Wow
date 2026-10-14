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

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnRejected
import me.ahoo.wow.api.annotation.OnSchedule
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.ScheduleTick
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.manifest.JobManifest
import java.time.DateTimeException
import java.time.ZoneId

/**
 * A saga's `@OnSchedule` handlers (decision D198): each a job, compiled into a binding of the context's built-in job
 * aggregate and into the Manifest's subscription `job`. The handler:
 *
 * - is a `@Saga`'s, not also `@OnEvent` or `@OnRejected`, and not private;
 * - takes a `ScheduleTick` and, optionally, the `SagaContext`, and returns what a saga's handler returns;
 * - names its job with letters, digits, `_` and `-`, gives six cron fields and a time zone Java knows, and is sharded
 *   only as a SYSTEM job; a processor's jobs of one name are declared alike. The cron fields are checked when the
 *   binding is built, by the kernel's parser.
 */
internal object ScheduleJobResolver {
    val ON_SCHEDULE: String = OnSchedule::class.java.name
    private val ON_EVENT = OnEvent::class.java.name
    private val ON_REJECTED = OnRejected::class.java.name
    private val TICK = ScheduleTick::class.java.name
    private val SAGA_CONTEXT = SagaContext::class.java.name
    private val BLOCKING = Blocking::class.java.name
    private const val CRON_FIELDS = 6

    /**
     * The binding of the `@OnSchedule` handler [function] of [processor], of [kind] (`SAGA`, or another kind it is a
     * problem for), or null with its problems added to [problems]. [returnProblem] checks what a saga returns.
     */
    @OptIn(KspExperimental::class)
    fun handler(
        function: KSFunctionDeclaration,
        processor: String,
        kind: String?,
        reactorTypes: Set<String>,
        problems: MutableList<String>,
        returnProblem: (KSType) -> String?,
    ): HandlerBinding? {
        val name = function.simpleName.asString()
        val where = "handler $processor.$name"
        val count = problems.size
        if (kind != null && kind != SAGA) {
            problems += "$where is an @OnSchedule job of a ${LABELS[kind]}: only a @Saga runs jobs"
        }
        if (function.hasAnnotation(ON_EVENT) || function.hasAnnotation(ON_REJECTED)) {
            problems += "$where is @OnSchedule and @OnEvent or @OnRejected: a job's handler takes its ticks only"
        }
        if (function.isPrivate()) {
            problems += "$where is private: the generated subscription calls it"
        }
        val parameters = function.parameters.map { it.type.resolve() }
        val first = parameters.firstOrNull()?.declaration?.qualifiedName?.asString()
        if (first != TICK) {
            problems += "$where must take a $TICK first, not ${first ?: "nothing"}: a job's handler receives its tick"
        }
        val context = parameters.getOrNull(1)?.declaration?.qualifiedName?.asString()
        if (context != null && context != SAGA_CONTEXT) {
            problems += "$where takes $context: a job's handler takes a $SAGA_CONTEXT after the tick, if anything"
        }
        if (parameters.size > 2) {
            problems += "$where takes ${parameters.size} parameters: a job's handler takes (ScheduleTick[, SagaContext])"
        }
        val returned = function.returnType?.resolve()
        val reactor = returned?.declaration?.qualifiedName?.asString() in reactorTypes
        if (returned != null && !reactor) {
            returnProblem(returned)?.let { problems += "$where $it" }
        }
        val annotation = function.getAnnotationsByType(OnSchedule::class).first()
        val job = JobBinding(
            name = annotation.name,
            cron = annotation.cron,
            zone = annotation.zone,
            scope = annotation.scope.name,
            sharded = annotation.sharded,
            misfire = annotation.misfire.name,
        )
        problems += jobProblems(where, job)
        val retry = RetryResolver.read(function, where, problems)
        if (problems.size > count) {
            return null
        }
        return HandlerBinding(
            function = name,
            subscription = "$processor.$name",
            state = null,
            nullableState = false,
            context = context != null,
            suspending = Modifier.SUSPEND in function.modifiers,
            reactor = reactor,
            blocking = function.hasAnnotation(BLOCKING),
            retry = retry,
            job = job,
        )
    }

    /** A processor's jobs of one name declared differently. */
    fun conflicts(processor: String, handlers: List<HandlerBinding>): List<String> =
        handlers.mapNotNull { it.job }.groupBy { it.name }.filterValues { it.distinct().size > 1 }.keys.map {
            "processor $processor declares the job $it twice, differently: a job has one rule"
        }

    private fun jobProblems(where: String, job: JobBinding): List<String> = buildList {
        if (!ScheduleJobs.isJobName(job.name)) {
            add("$where names its job \"${job.name}\": a job's name has letters, digits, '_' and '-' only")
        }
        val fields = job.cron.trim().split(Regex("\\s+"))
        if (!job.cron.trim().startsWith("@") && fields.size != CRON_FIELDS) {
            add(
                "$where runs by \"${job.cron}\", of ${fields.size} fields: a cron expression has six, second minute " +
                    "hour day-of-month month day-of-week",
            )
        }
        try {
            ZoneId.of(job.zone)
        } catch (_: DateTimeException) {
            add("$where runs in the time zone \"${job.zone}\", which is not one")
        }
        if (job.sharded && job.scope != JobScope.SYSTEM.name) {
            add("$where is a sharded ${job.scope} job: only a SYSTEM job is sharded")
        }
    }

    private const val SAGA = "SAGA"
    private val LABELS = mapOf("PROJECT" to "@Projection", "REACT" to "@Reactor")

    private fun com.google.devtools.ksp.symbol.KSAnnotated.hasAnnotation(qualifiedName: String): Boolean =
        annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName }
}

/** The job of an `@OnSchedule` handler, as its annotation declares it (decision D198). */
internal data class JobBinding(
    val name: String,
    val cron: String,
    val zone: String,
    val scope: String,
    val sharded: Boolean,
    val misfire: String,
) {
    /** The job as the Manifest's subscription records it. */
    fun manifest(): JobManifest = JobManifest(name, cron, zone, scope, sharded, misfire)
}
