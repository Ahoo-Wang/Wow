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

import type {SidebarConfig} from '@vuepress/theme-default'

export const sidebarZh: SidebarConfig = {
    '/guide/': [
        {
            text: '指南',
            children: [
                '/guide/introduction.md',
                '/guide/getting-started.md',
                // '/guide/configuration.md',
                '/guide/modeling.md',
                '/guide/command-gateway.md',
                '/guide/eventstore.md',
                '/guide/snapshot.md',
                '/guide/projection.md',
                '/guide/saga.md',
                '/guide/event-processor.md',
                '/guide/open-api.md',
                '/guide/test-suite.md',
                '/guide/bi.md',
                '/guide/event-compensation.md',
                '/guide/best-practices.md',
            ],
        },
    ],
    '/reference/': [
        {
            text: '配置',
            link: '/reference/configuration.md'
        }
    ],
    '/reference/example/': [
        {
            text: '示例',
            children: [
                '/reference/example/order.md',
                '/reference/example/transfer.md',
            ],
        }
    ],
    '/extensions/': [
        {
            text: '扩展',
            children: [
                '/extensions/kafka.md',
                '/extensions/mongo.md',
                '/extensions/redis.md',
                '/extensions/r2bdc.md',
                '/extensions/elasticsearch.md',
                '/extensions/opentelemetry.md',
                '/extensions/webflux.md',
                '/extensions/spring-boot-starter.md',
            ],
        }
    ],
    '/advanced/': [
        {
            text: '深入',
            children: [
                '/advanced/architecture.md',
                '/advanced/perf-test.md',
                '/advanced/id-generator.md',
                '/advanced/compiler.md',
                '/advanced/prepare-key.md',
                '/advanced/metrics.md',
                '/advanced/observability.md',
                '/advanced/aggregate-scheduler.md',
            ],
        }
    ],
}