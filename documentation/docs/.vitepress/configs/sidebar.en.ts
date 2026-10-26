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

// The view engine's guides: one group of its own, beside the TypeScript client and the packages' reference.
const viewEngineGuides: DefaultTheme.SidebarItem = {
    base: '/typescript/view-engine/',
    text: 'View Engine',
    items: [
        {text: 'Concepts', link: 'index.html'},
        {text: 'Define Views', link: 'definitions'},
        {text: 'Host the Engine', link: 'host'},
        {text: 'Actions', link: 'actions'},
        {text: 'Saved Views', link: 'storage'},
        {text: 'Theming', link: 'theming'},
        {text: 'Accessibility', link: 'accessibility'},
        {text: 'Content Security Policy', link: 'csp'},
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
            {text: 'Symbol Index', link: 'symbols'},
        ],
    },
]

const viewEngine: DefaultTheme.SidebarItem[] = [viewEngineGuides, ...viewEngineReference]

// One sidebar for the TypeScript client area: its guides and the reference of each package.
const typescriptEn: DefaultTheme.SidebarItem[] = [
    {
        base: '/typescript/',
        text: 'TypeScript',
        items: [
            {text: 'Overview', link: 'index.html'},
            {text: 'Generate a Client', link: 'generate'},
            {text: 'Send Commands', link: 'commands'},
            {text: 'Read and Query', link: 'queries'},
            {text: 'React Hooks', link: 'react'},
            {text: 'Generate from Any OpenAPI Document', link: 'openapi-client'},
            {text: 'Authentication and Interceptors', link: 'authentication'},
            {text: 'Compatibility and Versions', link: 'compatibility'},
            {text: 'Troubleshooting', link: 'troubleshooting'},
        ],
    },
    {
        base: '/reference/typescript/wow-client/',
        text: 'wow-client',
        collapsed: true,
        items: [
            {text: 'Overview', link: 'index.html'},
            {text: 'Root entry', link: 'symbols'},
            {text: '/dsl', link: 'symbols-dsl'},
        ],
    },
    {
        base: '/reference/typescript/wow-react/',
        text: 'wow-react',
        collapsed: true,
        items: [
            {text: 'Overview', link: 'index.html'},
            {text: 'Symbol Index', link: 'symbols'},
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
    '/typescript/': typescriptEn,
    '/reference/typescript/': typescriptEn,
    '/data/': [
        {
            text: 'Data',
            base: '/data/',
            items: [
                {text: 'Data in a Wow System', link: 'index.html'},
                {text: 'Query for Analysis', link: 'querying'},
                {text: 'Datasets and the Analytics Catalog', link: 'datasets'},
                {text: 'Ask with an Agent', link: 'agents'},
                {text: 'The Workbench', link: 'workbench'},
            ],
        },
    ],
    '/operate/': [
        {
            base: '/operate/',
            text: 'Operate',
            items: [
                {text: 'Running Wow', link: 'index.html'},
                {text: 'Deployment Model', link: 'topology'},
                {text: 'Configure a Service', link: 'configuration'},
                {text: 'PostgreSQL', link: 'postgresql'},
                {text: 'MongoDB', link: 'mongodb'},
                {text: 'Kafka', link: 'kafka'},
                {text: 'ClickHouse', link: 'clickhouse'},
                {text: 'Kubernetes', link: 'kubernetes'},
                {text: 'Health, Metrics and Tracing', link: 'observability'},
                {text: 'Scaling and Capacity', link: 'scaling'},
                {text: 'Elasticsearch', link: 'elasticsearch'},
                {text: 'Backup and Recovery', link: 'backup'},
                {text: 'Rebuild and Replay', link: 'replay'},
                {text: 'Upgrade a Deployment', link: 'upgrades'},
                {text: 'Troubleshooting', link: 'troubleshooting'},
                {text: 'Secure a Deployment', link: 'security'},
                {text: 'The Ops Service and Console', link: 'ops-console'},
            ],
        },
    ],
    '/migrate/': [
        {
            base: '/migrate/',
            text: 'Migrate from 9.x',
            items: [
                {text: 'Migrating from 9.x', link: 'index.html'},
                {text: '9.x Capabilities in V10', link: 'capabilities'},
                {text: 'Migrate the Source', link: 'source'},
                {text: 'Configuration Keys', link: 'configuration'},
                {text: 'HTTP and TypeScript Clients', link: 'http-and-clients'},
                {text: 'Migrate the Data', link: 'data'},
                {text: 'Cut Over', link: 'cutover'},
                {text: 'Platform Services', link: 'platform'},
                {text: 'Metrics and Spans', link: 'metrics'},
                {text: 'migrate-data CLI', base: '/reference/', link: 'migrate-data'},
            ],
        },
    ],
    '/guide/': [
        {
            base: '/guide/',
            text: 'Model',
            items: [
                {text: 'Developer Guide', link: 'index.html'},
                {text: 'Write an Aggregate', link: 'aggregates'},
                {text: 'Rejections and Error Codes', link: 'rejections'},
                {text: 'Evolve Events', link: 'event-evolution'},
                {text: 'Owners, Spaces and Tags', link: 'ownership'},
                {text: 'Unique Keys', link: 'unique-keys'},
            ],
        },
        {
            base: '/guide/',
            text: 'Processes',
            items: [
                {text: 'Write a Saga', link: 'sagas'},
                {text: 'Projections and Reactors', link: 'projections'},
                {text: 'Scheduled Commands', link: 'scheduling'},
                {text: 'Failures and Retries', link: 'failures'},
            ],
        },
        {
            base: '/guide/',
            text: 'The Read Side',
            items: [
                {text: 'Read State', link: 'reading-state'},
                {text: 'Query Snapshots', link: 'queries'},
                {text: 'Query Event Streams', link: 'event-queries'},
                {text: 'Publish Datasets', link: 'datasets'},
            ],
        },
        {
            base: '/guide/',
            text: 'Across Contexts',
            items: [
                {text: 'Publish and Consume Streams', link: 'published-streams'},
                {text: 'Call Other Services', link: 'calling-services'},
            ],
        },
        {
            base: '/guide/',
            text: 'The Contract',
            items: [
                {text: 'Serve and Call the HTTP API', link: 'http'},
                {text: 'The Manifest and Compatibility', link: 'manifest'},
                {text: 'OpenAPI and springdoc', link: 'openapi'},
                {text: 'What the Compiler Generates', link: 'generated-code'},
            ],
        },
        {
            base: '/guide/',
            text: 'Test',
            items: [
                {text: 'Test Domain Code', link: 'testing'},
                {text: 'Test a Service', link: 'testing-services'},
            ],
        },
        {
            base: '/guide/',
            text: 'Host',
            items: [
                {text: 'Host on Spring Boot', link: 'spring-boot'},
                {text: 'Write the Domain in Java', link: 'java'},
                {text: 'Reactor, Ids and Models', link: 'extensions'},
                {text: 'Host without Spring', link: 'without-spring'},
            ],
        },
    ],
    '/start/': [
        {
            base: '/start/',
            text: 'Start',
            items: [
                {text: 'Start Here', link: 'index.html'},
                {text: 'Why Wow', link: 'why-wow'},
                {text: 'Quickstart', link: 'quickstart'},
                {text: 'Add Wow to an Existing Service', link: 'existing-project'},
                {text: 'Tutorial: Two Contexts End to End', link: 'tutorial'},
                {text: 'Agent Skills', link: 'skills'},
            ],
        },
    ],
    '/concepts/': [
        {
            base: '/concepts/',
            text: 'Concepts',
            items: [
                {text: 'Architecture Overview', link: 'index.html'},
                {text: 'Deciding and Evolving', link: 'programming-model'},
                {text: 'The Log Is the Bus', link: 'event-log'},
                {text: 'Writing: Admission to Append', link: 'write-path'},
                {text: 'Waiting for Outcomes', link: 'waits'},
                {text: 'Subscriptions, Failures and Replay', link: 'subscriptions'},
                {text: 'Read Models', link: 'read-models'},
                {text: 'One Contract: the Manifest', link: 'contract'},
                {text: 'Identity, Tenancy and Access', link: 'identity'},
                {text: 'Between Contexts', link: 'cross-context'},
            ],
        },
    ],
    '/reference/': [
        {
            base: '/reference/',
            text: 'Reference',
            items: [
                {text: 'Glossary', link: 'glossary'},
                {text: 'HTTP API', link: 'http'},
                {text: 'Query Language', link: 'query-language'},
                {text: 'Metrics, Spans and Health', link: 'observability'},
                {text: 'Wire Protocols', link: 'protocols'},
                {text: 'migrate-data CLI', link: 'migrate-data'},
                {text: 'Configuration Keys', link: 'configuration'},
                {text: 'Error Codes', link: 'error-codes'},
                {text: 'Annotations', link: 'annotations'},
                {text: 'Manifest Format', link: 'manifest'},
                {text: 'Modules and Artifacts', link: 'modules'},
                {text: 'API Levels', link: 'api-levels'},
            ],
        },
    ],
    '/contribute/': [
        {
            base: '/contribute/',
            text: 'Contribute',
            items: [
                {text: 'Codebase Tour', link: 'index.html'},
                {text: 'Test Layers', link: 'testing'},
                {text: 'Write a Backend', link: 'backends'},
                {text: 'Design Records', link: 'design-records'},
                {text: 'Documentation Governance', link: 'documentation'},
            ],
        },
    ],
}
