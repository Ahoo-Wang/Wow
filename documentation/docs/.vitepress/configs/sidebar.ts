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

import {DefaultTheme} from "vitepress/types/default-theme";

export const sidebar: DefaultTheme.Sidebar = {
    '/guide/': [
        {
            base: '/guide/',
            text: '指南',
            collapsed: false,
            items: [
                {text: '介绍', link: 'introduction',},
                {text: '快速上手', link: 'getting-started',},
                {text: '聚合建模', link: 'modeling',},
                {text: '命令网关', link: 'command-gateway',},
                {text: '事件存储', link: 'eventstore',},
                {text: '快照', link: 'snapshot',},
                {text: '投影', link: 'projection',},
                {text: '分布式事务(Saga)', link: 'saga',},
                {text: 'Open API', link: 'open-api',},
                {text: '测试套件', link: 'test-suite',},
                {text: '商业智能', link: 'bi',},
                {text: '事件补偿', link: 'event-compensation',},
                {text: '最佳实践', link: 'best-practices',},
                {text: '性能评测', link: 'perf-test',},
            ],
        }, {
            base: '/guide/extensions/',
            text: '扩展',
            collapsed: false,
            items: [
                {text: 'Kafka', link: 'kafka',},
                {text: 'Mongo', link: 'mongo',},
                {text: 'Redis', link: 'redis',},
                {text: 'R2bdc', link: 'r2bdc',},
                {text: 'Elasticsearch', link: 'elasticsearch',},
                {text: 'Opentelemetry', link: 'opentelemetry',},
                {text: 'Webflux', link: 'webflux',},
                {text: 'Spring-Boot-Starter', link: 'spring-boot-starter',},
            ],
        }, {
            base: '/guide/advanced/',
            text: '深入',
            collapsed: false,
            items: [
                {text: '架构', link: 'architecture',},
                {text: 'Id 生成器', link: 'id-generator',},
                {text: '编译器', link: 'compiler',},
                {text: '唯一性 Key', link: 'prepare-key',},
                {text: '指标', link: 'metrics',},
                {text: '可观测性', link: 'observability',},
                {text: '聚合调度器', link: 'aggregate-scheduler',},
            ],
        },
    ],
    '/reference/': [
        {
            text: '配置',
            base: '/reference/',
            items: [
                {text: '配置', link: 'configuration',},
            ],
        },
        {
            text: '示例',
            base: '/reference/example/',
            items: [
                {text: '订单系统', link: 'order',},
                {text: '银行转账（JAVA）', link: 'transfer',},
            ],
        }
    ]
}