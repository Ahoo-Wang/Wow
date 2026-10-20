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

// The view engine has four groups of its own in the TypeScript client area:
// start, concepts, guides and reference. Batches B2–B5 of phase 7 each add
// pages to their own group only (concepts in B2; host, actions and CSP in B3;
// storage in B4; the API reference in B5) and leave the others alone.
const viewEngineStart: DefaultTheme.SidebarItem = {
    base: '/guide/typescript/',
    text: 'View Engine',
    items: [
        {text: 'Overview', link: 'view-engine'},
    ],
}

const viewEngineConcepts: DefaultTheme.SidebarItem = {
    base: '/guide/typescript/',
    text: 'View Engine Concepts',
    items: [
        {text: 'Core Concepts', link: 'view-engine-concepts'},
    ],
}

const viewEngineGuides: DefaultTheme.SidebarItem = {
    base: '/guide/typescript/',
    text: 'View Engine Guides',
    items: [
        {text: 'Writing a Definition', link: 'view-engine-definitions'},
        {text: 'Theming', link: 'view-engine-theming'},
        {text: 'Accessibility', link: 'view-engine-accessibility'},
        {text: 'Where Views Live', link: 'view-engine-storage'},
        {text: 'Fitting It into a Host', link: 'view-engine-host'},
        {text: 'Declared Actions', link: 'view-engine-actions'},
        {text: 'Content Security Policy', link: 'view-engine-csp'},
    ],
}

const viewEngineReference: DefaultTheme.SidebarItem[] = [
    {
        base: '/reference/typescript/wow-view-engine/',
        text: 'wow-view-engine',
        collapsed: true,
        items: [
            {text: 'Entries and Contracts', link: 'index.html'},
            {text: 'Engine and Resources', link: 'engine'},
            {text: 'Definitions and Field Kinds', link: 'definitions'},
            {text: 'Host Wiring', link: 'host'},
            {text: 'Workbenches and Embeds', link: 'components'},
            {text: 'React Hooks', link: 'react'},
            {text: 'Persistence Port', link: 'store'},
            {text: 'Testing Helpers', link: 'testing'},
            {text: 'Issue Codes', link: 'issues'},
            {
                text: 'Symbol Index',
                collapsed: true,
                items: [
                    {text: 'Root entry', link: 'symbols'},
                    {text: '/react', link: 'symbols-react'},
                    {text: '/ui', link: 'symbols-ui'},
                    {text: '/testing', link: 'symbols-testing'},
                    {text: '/react-router', link: 'symbols-react-router'},
                ],
            },
        ],
    },
    {
        base: '/reference/typescript/wow-view-store/',
        text: 'wow-view-store',
        collapsed: true,
        items: [
            {text: 'Store and Contract', link: 'index.html'},
        ],
    },
]

// A group with no page yet stays out of the sidebar.
const viewEngine: DefaultTheme.SidebarItem[] = [
    ...[viewEngineStart, viewEngineConcepts, viewEngineGuides].filter((group) => group.items?.length),
    ...viewEngineReference,
]

// One sidebar for the TypeScript client area: its guides and the reference of each package.
const typescriptEn: DefaultTheme.SidebarItem[] = [
    {
        base: '/guide/typescript/',
        text: 'TypeScript Client',
        items: [
            {text: 'Overview', link: 'index.html'},
            {text: 'Quick Start', link: 'quick-start'},
            {text: 'Authentication and Interceptors', link: 'authentication'},
            {text: 'Generate from Any OpenAPI Document', link: 'generated-client'},
            {text: 'Regenerate in CI', link: 'regenerate-in-ci'},
            {text: 'Compatibility and Versions', link: 'compatibility'},
            {text: 'Troubleshooting', link: 'troubleshooting'},
        ],
    },
    {
        base: '/reference/typescript/wow-generator/',
        text: 'wow-generator',
        collapsed: true,
        items: [
            {text: 'Overview', link: 'index.html'},
            {text: 'CLI', link: 'cli'},
            {text: 'Configuration', link: 'configuration'},
            {text: 'Programmatic API', link: 'programmatic-api'},
            {text: 'Generated Output and Regeneration', link: 'generated-output'},
            {text: 'Wow Aggregate Discovery', link: 'wow-discovery'},
            {text: 'Symbol Index', link: 'symbols'},
        ],
    },
    ...viewEngine,
]

export const sidebarEn: DefaultTheme.Sidebar = {
    '/guide/typescript/': typescriptEn,
    '/reference/typescript/': typescriptEn,
    '/workbench/': [
        {
            text: 'Using the Workbench',
            base: '/workbench/',
            items: [
                {text: 'Overview', link: 'index.html'},
                {text: 'Record Views', link: 'records'},
                {text: 'Analysis Views', link: 'analysis'},
                {text: 'Dashboards', link: 'dashboards'},
                {text: 'Saving and Managing Views', link: 'views'},
            ],
        },
    ],
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
            // V10 is in pre-release (10.0.0-alpha.3, the v10 branch); its pages stay apart from the 9.x guide.
            base: '/guide/v10/',
            text: 'V10 (Preview)',
            collapsed: true,
            items: [
                {text: 'V10 Quick Start', link: 'quickstart'},
                {text: 'Write a V10 Service', link: 'index.html'},
                {text: 'Programming Model', link: 'programming-model'},
                {text: 'What the Compiler Generates', link: 'generated-code'},
                {text: 'Testing', link: 'testing'},
                {text: 'HTTP API', link: 'http-api'},
                {text: 'Query Language', link: 'query'},
                {text: 'Configuration Keys', link: 'configuration'},
                {text: 'Deployment', link: 'deployment'},
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
                {text: 'Prepare Key', link: 'advanced/prepare-key'},
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
                {text: 'Field Masking', link: 'query/masking'},
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
                {text: 'Dataset Query', link: 'query/dataset-query'},
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
                {text: 'TypeScript Client', link: 'typescript/index.html'},
            ],
        },
        {
            base: '/guide/',
            text: 'Testing and Delivery',
            collapsed: true,
            items: [
                {text: 'Test Suite', link: 'test-suite'},
                {text: 'Application Testing', link: 'application-testing'},
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
                {text: 'Observability', link: 'advanced/observability'},
                {text: 'Metrics', link: 'advanced/metrics'},
                {text: 'Troubleshooting', link: 'troubleshooting'},
                {
                    text: 'Migration Guide',
                    link: 'migration',
                    collapsed: true,
                    items: [
                        {text: 'Traditional Architecture', link: 'migration/traditional-architecture'},
                        {text: 'V9 Query Migration', link: 'query/v9-query-migration'},
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
                {text: 'View Store', link: 'view-store'},
                {text: 'Analytics Service', link: 'analytics'},
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
                {text: 'JSON Schema', link: 'schema'},
                {text: 'Keyed Executor', link: 'keyed-executor'},
            ],
        },
        {
            // Pages for people who change Wow itself, not for applications built on it.
            base: '/guide/',
            text: 'Contributing to Wow',
            collapsed: true,
            items: [
                {text: 'Contributor Guide', link: '/onboarding/contributor-guide'},
                {text: 'Framework Tests and Benchmarks', link: 'test-runtime'},
                {text: 'Documentation Governance', link: 'advanced/documentation-governance'},
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
            text: 'TypeScript Client',
            base: '/reference/typescript/',
            collapsed: true,
            items: [
                {text: 'wow-generator', link: 'wow-generator/index.html'},
                {text: 'wow-view-engine', link: 'wow-view-engine/index.html'},
                {text: 'wow-view-store', link: 'wow-view-store/index.html'},
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
