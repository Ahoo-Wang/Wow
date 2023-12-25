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

import type {NavbarConfig} from '@vuepress/theme-default'

export const navbarZh: NavbarConfig = [
    {
        text: '指南',
        link: '/guide/getting-started.md'
    },
    {
        text: '参考',
        children: [
            {
                text: '配置',
                link: '/reference/configuration.md'
            },
            {
                text: '示例',
                children: [
                    '/reference/example/order.md',
                    '/reference/example/transfer.md',
                ],
            }
        ],
    },
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
    },
    {
        text: '了解更多',
        children: [
            '/advanced/architecture.md',
            '/advanced/perf-test.md',
            '/advanced/id-generator.md',
            '/advanced/compiler.md',
            '/advanced/prepare-key.md',
            '/advanced/metrics.md',
            '/advanced/observability.md',
            '/advanced/aggregate-scheduler.md',
            {
                text: "博客资源",
                children: [
                    {
                        text: 'Event Sourcing - Specifications',
                        link: 'https://abdullin.com/post/event-sourcing-specifications/'
                    },
                    {
                        text: 'Testing Event Sourcing',
                        link: 'https://event-driven.io/en/testing_event_sourcing/'
                    }
                ]
            }
        ],
    }, {
        text: 'JavaDoc',
        link: '/dokka/index.html',
        target: '_blank'
    },
    {
        text: `更新日志`,
        link: `https://github.com/Ahoo-Wang/Wow/releases`
    },
    {
        text: `Gitee`,
        link: `https://gitee.com/AhooWang/Wow`
    }
]