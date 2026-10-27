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

package me.ahoo.wow.compiler.model.subscription

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnRejected
import me.ahoo.wow.api.annotation.OnSchedule
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.ScheduleTick
import me.ahoo.wow.api.subscribe.JobContext
import me.ahoo.wow.compiler.model.symbol.annotatedIn
import me.ahoo.wow.compiler.model.symbol.blocking
import me.ahoo.wow.compiler.model.symbol.handlerName
import me.ahoo.wow.manifest.JobManifest
import java.time.DateTimeException
import java.time.ZoneId

/**
 * A saga's `@OnSchedule` handlers: each a job, compiled into a binding of the context's built-in job
 * aggregate and into the Manifest's subscription `job`. The handler:
 *
 * - is a `@Saga`'s, not also `@OnEvent` or `@OnRejected`, and not private;
 * - takes a `ScheduleTick` and, optionally, the `JobContext`, and returns what a saga's handler returns;
 * - names its job with letters, digits, `_` and `-`, gives six cron fields (or a macro, `@daily`) and a time zone Java
 *   knows, and is sharded only as a SYSTEM job; a processor's jobs of one name are declared alike. The cron fields
 *   themselves are checked when the binding is built, by the kernel's parser.
 */
internal object ScheduleJobResolver {
    val ON_SCHEDULE: String = OnSchedule::class.java.name
    private val ON_EVENT = OnEvent::class.java.name
    private val ON_REJECTED = OnRejected::class.java.name
    private val TICK = ScheduleTick::class.java.name
    private val JOB_CONTEXT = JobContext::class.java.name
    private val BLOCKING = Blocking::class.java.name
    private val RETRY = Retry::class.java.name
    private const val CRON_FIELDS = 6
    private const val SAGA = "SAGA"
    private val LABELS = mapOf("PROJECT" to "@Projection", "REACT" to "@Reactor")

    /**
     * The bindings of the `@OnSchedule` handlers [functions] of the processor class [className], named [processor],
     * of [kind] (`SAGA`, or another kind they are a problem for). A handler with problems is left out, its problems
     * added to [problems], and so is a job declared twice differently. [returnProblem] checks what a saga returns.
     */
    @Suppress("LongParameterList")
    fun handlers(
        functions: List<KSFunctionDeclaration>,
        className: String,
        processor: String,
        kind: String?,
        reactorTypes: Set<String>,
        problems: MutableList<String>,
        returnProblem: (KSType) -> String?,
    ): List<HandlerBinding> {
        val handlers = functions.mapNotNull { handler(it, processor, kind, reactorTypes, problems, returnProblem) }
        handlers.mapNotNull { it.job }.groupBy { it.name }.filterValues { it.distinct().size > 1 }.keys.forEach {
            problems += "processor $className declares the job $it twice, differently: a job has one rule"
        }
        return handlers
    }

    @OptIn(KspExperimental::class)
    @Suppress("LongParameterList")
    private fun handler(
        function: KSFunctionDeclaration,
        processor: String,
        kind: String?,
        reactorTypes: Set<String>,
        problems: MutableList<String>,
        returnProblem: (KSType) -> String?,
    ): HandlerBinding? {
        val callee = function.simpleName.asString()
        val where = "handler $processor.$callee"
        val count = problems.size
        val name = function.handlerName(where, problems)
        problems += signatureProblems(where, function, kind)
        val returned = function.returnType?.resolve()
        val reactor = returned?.declaration?.qualifiedName?.asString() in reactorTypes
        if (returned != null && !reactor) {
            returnProblem(returned)?.let { problems += "$where $it" }
        }
        val annotation = function.annotatedIn(ON_SCHEDULE)!!.getAnnotationsByType(OnSchedule::class).first()
        val job = JobBinding(
            name = annotation.name,
            cron = annotation.cron,
            zone = annotation.zone,
            scope = annotation.scope.name,
            sharded = annotation.sharded,
            misfire = annotation.misfire.name,
        )
        problems += jobProblems(where, job)
        val retry = RetryResolver.read(function.annotatedIn(RETRY) ?: function, where, problems)
        if (problems.size > count) {
            return null
        }
        return HandlerBinding(
            function = name,
            subscription = "$processor.$name",
            callee = callee,
            state = null,
            nullableState = false,
            context = function.parameters.size > 1,
            suspending = Modifier.SUSPEND in function.modifiers,
            reactor = reactor,
            blocking = function.annotatedIn(BLOCKING) != null,
            retry = retry,
            job = job,
        )
    }

    /** What is wrong with [function]'s processor kind, annotations, visibility and parameters. */
    private fun signatureProblems(where: String, function: KSFunctionDeclaration, kind: String?): List<String> =
        buildList {
            if (kind != null && kind != SAGA) {
                add("$where is an @OnSchedule job of a ${LABELS[kind]}: only a @Saga runs jobs")
            }
            if (function.hasAnnotation(ON_EVENT) || function.hasAnnotation(ON_REJECTED)) {
                add("$where is @OnSchedule and @OnEvent or @OnRejected: a job's handler takes its ticks only")
            }
            if (function.isPrivate()) {
                add("$where is private: the generated subscription calls it")
            }
            val parameters = function.parameters.map { it.type.resolve().declaration.qualifiedName?.asString() }
            val first = parameters.firstOrNull()
            if (first != TICK) {
                add("$where must take a $TICK first, not ${first ?: "nothing"}: a job's handler receives its tick")
            }
            val context = parameters.getOrNull(1)
            if (context != null && context != JOB_CONTEXT) {
                add("$where takes $context: a job's handler takes a $JOB_CONTEXT after the tick, if anything")
            }
            if (parameters.size > 2) {
                add("$where takes ${parameters.size} parameters: a job's handler takes (ScheduleTick[, JobContext])")
            }
        }

    /** What is wrong with [job]: its name, its fields, its time zone, or a sharded job that is not SYSTEM. */
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

    private fun KSAnnotated.hasAnnotation(qualifiedName: String): Boolean =
        annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName }
}

/** The job of an `@OnSchedule` handler, as its annotation declares it. */
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
