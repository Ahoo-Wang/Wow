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

package me.ahoo.wow.api.annotation

import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.api.schedule.ScheduleRule
import java.lang.annotation.Inherited

/**
 * A job of a [Saga] run by a cron rule (decision D198): the handler takes a [me.ahoo.wow.api.schedule.ScheduleTick]
 * and, optionally, the `SagaContext`, and returns the commands it derives, as an `@OnEvent` handler of a saga does.
 * The compiler writes the job into the Manifest. Each job is kept by the built-in aggregate `<context>.schedule`, whose
 * id is the job's [name] in its tenant: each time the rule is due it commits `Ticked(slot)`, and its subscription,
 * `processor.function`, hands the tick to the handler, retried and parked as any subscription's.
 *
 * @param name the job's name, unique in its context: letters, digits, `_` and `-`. The host overrides its rule with
 *   `wow.schedules.<name>.*`.
 * @param cron six fields, as Spring's `CronExpression` reads them (`0 0 3 * * *`: at 03:00 every day).
 * @param zone the time zone [cron] is read in, UTC by default.
 * @param scope [JobScope.SYSTEM]: one instance, enabled by the host at startup; [JobScope.TENANT]: one per tenant,
 *   created by the system command `wow.schedule.Enable`, each tenant free to override the rule and its time zone.
 * @param sharded a [JobScope.SYSTEM] job run by every partition's owner for its own partition's aggregates: one
 *   instance per log partition, each ticked exactly once per slot (`ScheduleTick.partition`).
 * @param misfire what a slot missed while no node ran the job does: by default only the latest runs, and the tick
 *   counts the others as skipped.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class OnSchedule(
    val name: String,
    val cron: String,
    val zone: String = ScheduleRule.UTC,
    val scope: JobScope = JobScope.SYSTEM,
    val sharded: Boolean = false,
    val misfire: Misfire = Misfire.COALESCE,
)
