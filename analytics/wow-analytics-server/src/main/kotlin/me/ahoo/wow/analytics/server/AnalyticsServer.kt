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

package me.ahoo.wow.analytics.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

/**
 * The analytics service (dataset platform R5): the view store, the dataset registry and the datasets it publishes,
 * served from ClickHouse, behind CoSec. An enterprise deploys it with its ClickHouse and CoSec and has the whole BI
 * stack — the dataset directory, analysis views and boards, and the review and publication of datasets.
 */
@SpringBootApplication
@EnableConfigurationProperties(AnalyticsProperties::class)
class AnalyticsServer

fun main(args: Array<String>) {
    runApplication<AnalyticsServer>(*args)
}
