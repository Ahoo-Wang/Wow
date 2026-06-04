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
    '/onboarding/': [
        {
            text: 'Onboarding',
            collapsed: false,
            items: [
                {text: 'Contributor Guide', link: '/onboarding/contributor-guide'},
                {text: 'Staff Engineer Guide', link: '/onboarding/staff-engineer-guide'},
                {text: 'Executive Guide', link: '/onboarding/executive-guide'},
                {text: 'Product Manager Guide', link: '/onboarding/product-manager-guide'},
            ],
        },
    ],
    '/guide/': [
        {
            text: 'Onboarding',
            collapsed: false,
            items: [
                {text: 'Contributor Guide', link: '/onboarding/contributor-guide'},
                {text: 'Staff Engineer Guide', link: '/onboarding/staff-engineer-guide'},
                {text: 'Executive Guide', link: '/onboarding/executive-guide'},
                {text: 'Product Manager Guide', link: '/onboarding/product-manager-guide'},
            ],
        },
        {
            base: '/guide/',
            text: 'Basics',
            collapsed: false,
            items: [
                {text: 'Introduction', link: 'introduction'},
                {text: 'Getting Started', link: 'getting-started'},
                {text: 'Core Concepts', link: 'core-concepts'},
                {text: 'Aggregate Modeling', link: 'modeling'},
                {text: 'Configuration', link: 'configuration'},
            ],
        },
        {
            base: '/guide/',
            text: 'Core',
            collapsed: false,
            items: [
                {text: 'Event Store', link: 'eventstore'},
                {text: 'Snapshot', link: 'snapshot'},
                {text: 'Command Gateway', link: 'command-gateway'},
                {text: 'Distributed Transactions (Saga)', link: 'saga'},
                {text: 'Projection', link: 'projection'},
                {text: 'Query Service', link: 'query'},
                {text: 'Data Access Control', link: 'data-access'},
                {text: 'Event Processor', link: 'event-processor'},
            ],
        },
        {
            base: '/guide/',
            text: 'Tooling',
            collapsed: false,
            items: [
                {text: 'Open API', link: 'open-api'},
                {text: 'Test Suite', link: 'test-suite'},
                {text: 'Test Runtime', link: 'test-runtime'},
                {text: 'Business Intelligence', link: 'bi'},
                {text: 'Event Compensation', link: 'event-compensation'},
            ],
        },
        {
            base: '/guide/',
            text: 'Best Practices',
            collapsed: false,
            items: [
                {text: 'Best Practices', link: 'best-practices'},
                {text: 'Performance Testing', link: 'perf-test'},
                {text: 'Troubleshooting', link: 'troubleshooting'},
                {text: 'Migration Guide', link: 'migration'},
            ],
        },
        {
            base: '/guide/extensions/',
            text: 'Extensions',
            collapsed: true,
            items: [
                {text: 'Kafka', link: 'kafka'},
                {text: 'Mongo', link: 'mongo'},
                {text: 'Redis', link: 'redis'},
                {text: 'R2DBC', link: 'r2dbc'},
                {text: 'Elasticsearch', link: 'elasticsearch'},
                {text: 'OpenTelemetry', link: 'opentelemetry'},
                {text: 'WebFlux', link: 'webflux'},
                {text: 'CoCache', link: 'cocache'},
                {text: 'CoSec', link: 'cosec'},
                {text: 'API Client', link: 'apiclient'},
                {text: 'Spring-Boot-Starter', link: 'spring-boot-starter'},
                {text: 'Compatibility Test Suite', link: 'tck'},
            ],
        },
        {
            base: '/guide/advanced/',
            text: 'Advanced',
            collapsed: true,
            items: [
                {text: 'Architecture', link: 'architecture'},
                {text: 'Aggregate Lifecycle', link: 'aggregate-lifecycle'},
                {text: 'Event Bus', link: 'event-bus'},
                {text: 'Data Flow', link: 'data-flow'},
                {text: 'Module Dependencies', link: 'module-dependencies'},
                {text: 'ID Generator', link: 'id-generator'},
                {text: 'Compiler', link: 'compiler'},
                {text: 'Prepare Key', link: 'prepare-key'},
                {text: 'JSON Schema', link: 'schema'},
                {text: 'Metrics', link: 'metrics'},
                {text: 'Observability', link: 'observability'},
                {text: 'Aggregate Scheduler', link: 'aggregate-scheduler'},
            ],
        },
    ],
    '/reference/': [
        {
            text: 'Configuration',
            base: '/reference/config/',
            collapsed: false,
            items: [
                {text: 'Core Configuration', link: 'core'},
                {text: 'Infrastructure', link: 'infrastructure'},
                {text: 'Observability', link: 'observability'},
                {text: 'Compensation', link: 'compensation'},
            ],
        },
        {
            text: 'Examples',
            base: '/reference/example/',
            collapsed: false,
            items: [
                {text: 'Bank Transfer (JAVA)', link: 'transfer'},
                {text: 'Order System', link: 'order'},
                {text: 'Event Compensation', link: 'compensation'},
            ],
        },
        {
            text: 'Ecosystem',
            collapsed: false,
            items: [
                {text: 'Ecosystem', link: '/reference/ecosystem'},
            ],
        },
    ],
}
