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

package me.ahoo.wow.apiclient.query

import me.ahoo.wow.api.query.analytics.AnalyticsPage
import me.ahoo.wow.api.query.analytics.AnalyticsQuery
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.PostExchange
import reactor.core.publisher.Mono

const val SNAPSHOT_ANALYTICS_RESOURCE_NAME = "$SNAPSHOT_RESOURCE_NAME/analyze"

/** Additive analytics client contract; existing SnapshotQueryApi implementations do not inherit this method. */
interface SnapshotAnalyticsQueryApi<R> : SnapshotQueryApi {
    @PostExchange(SNAPSHOT_ANALYTICS_RESOURCE_NAME)
    fun analyze(@RequestBody query: AnalyticsQuery): R
}

interface ReactiveSnapshotAnalyticsQueryApi : SnapshotAnalyticsQueryApi<Mono<AnalyticsPage>>

interface SynchronousSnapshotAnalyticsQueryApi : SnapshotAnalyticsQueryApi<AnalyticsPage>

fun AnalyticsQuery.analyze(api: ReactiveSnapshotAnalyticsQueryApi): Mono<AnalyticsPage> = api.analyze(this)

fun AnalyticsQuery.analyze(api: SynchronousSnapshotAnalyticsQueryApi): AnalyticsPage = api.analyze(this)
