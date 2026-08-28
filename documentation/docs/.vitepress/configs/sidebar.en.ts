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
    '/articles/': [
        {
            text: 'Articles',
            base: '/articles/',
            collapsed: true,
            items: [
                {text: 'Articles Home', link: 'index.html'},
                {text: 'HTTP 200 but the Query Is Empty', link: 'command-success-is-not-complete'},
                {text: 'Traditional CRUD vs Wow', link: 'traditional-vs-wow-architecture'},
                {text: 'Why DDD Matters More in the AI Era', link: 'why-ddd-fits-ai-era'},
            ],
        },
    ],
    '/onboarding/': [
        {
            base: '/onboarding/',
            text: 'Evaluate and Contribute',
            collapsed: true,
            items: [
                {text: 'Choose by Role', link: 'index.html'},
                {text: 'Contributor Guide', link: 'contributor-guide'},
                {text: 'Staff Engineer Guide', link: 'staff-engineer-guide'},
                {text: 'Executive Guide', link: 'executive-guide'},
                {text: 'Product Manager Guide', link: 'product-manager-guide'},
            ],
        },
    ],
    '/guide/': [
        {
            base: '/guide/',
            text: 'Start',
            collapsed: true,
            items: [
                {text: 'Guide Overview', link: 'index.html'},
                {text: 'Introduction', link: 'introduction'},
                {text: 'Getting Started', link: 'getting-started'},
                {text: 'Existing Spring Boot Project', link: 'existing-project'},
                {text: 'Core Concepts', link: 'core-concepts'},
            ],
        },
        {
            base: '/guide/',
            text: 'Domain Model',
            collapsed: true,
            items: [
                {text: 'Domain Model Overview', link: 'domain/index.html'},
                {text: 'Aggregate and Invariants', link: 'domain/aggregate'},
                {text: 'Event Sourcing', link: 'domain/event-sourcing'},
                {text: 'Event Evolution', link: 'domain/event-evolution'},
                {text: 'Snapshots', link: 'domain/snapshot'},
                {text: 'Aggregate Lifecycle', link: 'domain/lifecycle'},
            ],
        },
        {
            base: '/guide/',
            text: 'Commands',
            collapsed: true,
            items: [
                {text: 'Commands Overview', link: 'command/index.html'},
                {text: 'Define Commands', link: 'command/definition'},
                {text: 'Send Commands', link: 'command/sending'},
                {text: 'API Client', link: 'command/api-client'},
                {text: 'Completion Semantics', link: 'command/completion'},
                {text: 'Failures and Idempotency', link: 'command/reliability'},
                {
                    text: 'How It Works',
                    collapsed: true,
                    items: [
                        {text: 'Command Processing Pipeline', link: 'command/internals/pipeline'},
                        {text: 'Command Wait Runtime', link: 'command/internals/wait-runtime'},
                        {text: 'Command Transport and Routing', link: 'command/internals/transport'},
                    ],
                },
            ],
        },
        {
            base: '/guide/',
            text: 'Events and Collaboration',
            collapsed: true,
            items: [
                {text: 'Events and Collaboration Overview', link: 'event/index.html'},
                {text: 'Event Processor', link: 'event/processor'},
                {text: 'Saga', link: 'event/saga'},
                {text: 'Event Compensation', link: 'event/compensation'},
                {text: 'Event Dispatch Pipeline', link: 'event/dispatch'},
            ],
        },
        {
            base: '/guide/',
            text: 'Query',
            link: 'query',
            collapsed: true,
            items: [
                {text: 'Query Gateway', link: 'query/query-gateway'},
                {text: 'Query Backend', link: 'query/query-backend'},
                {text: 'Query API Client', link: 'query/query-api-client'},
                {text: 'Filter Expressions', link: 'query/filter-expression'},
                {
                    text: 'Data Queries',
                    link: 'query/data-query',
                    collapsed: true,
                    items: [
                        {text: 'Snapshot Queries', link: 'query/snapshot-query'},
                        {text: 'Event Stream Queries', link: 'query/event-stream-query'},
                    ],
                },
                {
                    text: 'Aggregation Queries',
                    link: 'query/aggregation-query',
                    collapsed: true,
                    items: [
                        {text: 'Snapshot Aggregation', link: 'query/snapshot-aggregation'},
                        {text: 'Event Stream Aggregation', link: 'query/event-stream-aggregation'},
                    ],
                },
                {text: 'Query Model Schema', link: 'query/query-model-schema'},
                {text: 'Projection', link: 'projection'},
                {text: 'Data Access Control', link: 'data-access'},
            ],
        },
        {
            base: '/guide/',
            text: 'Interfaces and Automation',
            collapsed: true,
            items: [
                {text: 'Open API', link: 'open-api'},
                {text: 'Agent Skills', link: 'skills'},
                {text: 'Business Intelligence', link: 'bi'},
            ],
        },
        {
            base: '/guide/',
            text: 'Testing and Delivery',
            collapsed: true,
            items: [
                {text: 'Test Suite', link: 'test-suite'},
                {text: 'Application Testing', link: 'application-testing'},
                {text: 'Framework Tests and Benchmarks', link: 'test-runtime'},
            ],
        },
        {
            base: '/guide/',
            text: 'Production Operations',
            collapsed: true,
            items: [
                {text: 'Configuration', link: 'configuration'},
                {text: 'BI Deployment and Recovery', link: 'bi-operations'},
                {text: 'Production Best Practices', link: 'best-practices'},
                {text: 'Backup, Restore, and Replay', link: 'recovery'},
                {text: 'Troubleshooting', link: 'troubleshooting'},
                {
                    text: 'Migration Guide',
                    link: 'migration',
                    collapsed: true,
                    items: [
                        {text: 'Traditional Architecture', link: 'migration/traditional-architecture'},
                        {text: 'Migrate Wow v6 to v8', link: 'migration/v6-to-v8'},
                        {text: 'Runtime Orchestration', link: 'migration/runtime-orchestration'},
                    ],
                },
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
            text: 'How Wow Works',
            collapsed: true,
            items: [
                {text: 'Architecture', link: 'architecture'},
                {text: 'Runtime Lifecycle', link: 'runtime-lifecycle'},
                {text: 'Serialization', link: 'serialization'},
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
            collapsed: true,
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
            collapsed: true,
            items: [
                {text: 'Order and Cart (Kotlin)', link: 'order'},
                {text: 'Bank Transfer (JAVA)', link: 'transfer'},
                {text: 'Event Compensation', link: 'compensation'},
            ],
        },
        {
            text: 'Ecosystem',
            collapsed: true,
            items: [
                {text: 'Ecosystem', link: '/reference/ecosystem'},
            ],
        },
    ],
}
