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

export const sidebarEn: DefaultTheme.Sidebar = {
    '/guide/': [
        {
            base: '/guide/',
            text: 'Guide',
            collapsed: false,
            items: [
                {text: 'Introduction', link: 'introduction'},
                {text: 'Getting Started', link: 'getting-started'},
                {text: 'Aggregate Modeling', link: 'modeling'},
                // {text: 'Event Store', link: 'eventstore'},
                // {text: 'Snapshot', link: 'snapshot'},
                {text: 'Command Gateway', link: 'command-gateway'},
                {text: 'Distributed Transactions (Saga)', link: 'saga'},
                {text: 'Projection Processor', link: 'projection'},
                {text: 'Query Service', link: 'query'},
                {text: 'Open API', link: 'open-api'},
                {text: 'Test Suite', link: 'test-suite'},
                {text: 'Business Intelligence', link: 'bi'},
                {text: 'Event Compensation', link: 'event-compensation'},
                // {text: 'Best Practices', link: 'best-practices'},
                {text: 'Performance Testing', link: 'perf-test'},
            ],
        }, {
            base: '/guide/extensions/',
            text: 'Extensions',
            collapsed: false,
            items: [
                {text: 'Kafka', link: 'kafka'},
                {text: 'Mongo', link: 'mongo'},
                {text: 'Redis', link: 'redis'},
                {text: 'R2dbc', link: 'r2bdc'},
                {text: 'Elasticsearch', link: 'elasticsearch'},
                {text: 'OpenTelemetry', link: 'opentelemetry'},
                {text: 'WebFlux', link: 'webflux'},
                {text: 'Spring-Boot-Starter', link: 'spring-boot-starter'},
                {text: 'Compatibility Test Suite', link: 'tck'},
            ],
        }, {
            base: '/guide/advanced/',
            text: 'Advanced',
            collapsed: false,
            items: [
                // {text: 'Architecture', link: 'architecture'},
                {text: 'ID Generator', link: 'id-generator'},
                {text: 'Compiler', link: 'compiler'},
                {text: 'Pre-allocated Key', link: 'prepare-key'},
                // {text: 'Metrics', link: 'metrics'},
                // {text: 'Observability', link: 'observability'},
                // {text: 'Aggregate Scheduler', link: 'aggregate-scheduler'},
            ],
        }, {
            text: 'Reference',
            base: '/reference/',
            collapsed: false,
            items: [
                {text: 'Configuration', link: 'config/basic'},
                {text: 'Examples', link: 'example/transfer'},
                {text: 'Awesome', link: 'awesome/cqrs'},
            ]
        }
    ],
    '/reference/': [
        {
            text: 'Reference',
            items: [
                {
                    text: 'Configuration',
                    base: '/reference/config/',
                    collapsed: false,
                    items: [
                        {text: 'Basic Configuration', link: 'basic'},
                        {text: 'Command Bus', link: 'command'},
                        {text: 'Event Bus', link: 'event'},
                        {text: 'Event Sourcing', link: 'eventsourcing'},
                    ],
                },
                {
                    text: 'Examples',
                    base: '/reference/example/',
                    collapsed: false,
                    items: [
                        {text: 'Bank Transfer (JAVA)', link: 'transfer'},
                        {text: 'Order System', link: 'order'},
                    ],
                },
                {
                    text: 'Awesome',
                    base: '/reference/awesome/',
                    collapsed: false,
                    items: [
                        {text: 'CQRS', link: 'cqrs'},
                        {text: 'Microservices', link: 'microservices'},
                        {text: 'Reactive', link: 'reactive'},
                    ]
                }
            ]
        },

    ]
}