import {DefaultTheme} from "vitepress/types/default-theme";

export const navbarEn: DefaultTheme.NavItem[] = [
    {text: 'Quick Start', link: '/guide/v10/quickstart'},
    {
        text: 'Guide',
        activeMatch: '/guide/v10/',
        items: [
            {text: 'Write a Service', link: '/guide/v10/'},
            {text: 'Programming Model', link: '/guide/v10/programming-model'},
            {text: 'Testing', link: '/guide/testing'},
            {text: 'HTTP API', link: '/guide/v10/http-api'},
            {text: 'Query Language', link: '/guide/v10/query'},
            {text: 'Deployment', link: '/operate/topology'},
        ],
    },
    {
        text: 'TypeScript',
        activeMatch: '/(guide|reference)/typescript/',
        items: [
            {
                text: 'Guide',
                items: [
                    {text: 'Overview', link: '/guide/typescript/'},
                    {text: 'Generate a Client', link: '/guide/typescript/generated-client'},
                    {text: 'View Engine', link: '/guide/typescript/view-engine'},
                    {text: 'Using the Workbench (for users)', link: '/workbench/'},
                ],
            },
            {
                text: 'Reference',
                items: [
                    {text: 'wow-generator', link: '/reference/typescript/wow-generator/'},
                    {text: 'wow-view-engine', link: '/reference/typescript/wow-view-engine/'},
                    {text: 'wow-view-store', link: '/reference/typescript/wow-view-store/'},
                ],
            },
        ],
    },
    {text: 'Concepts', link: '/concepts/', activeMatch: '/concepts/'},
    {
        text: 'Operate',
        activeMatch: '/operate/',
        items: [
            {text: 'Deployment Model', link: '/operate/topology'},
            {text: 'Secure a Deployment', link: '/operate/security'},
            {text: 'Kafka', link: '/operate/kafka'},
            {text: 'The Ops Service and Console', link: '/operate/ops-console'},
        ],
    },
    {
        text: 'Reference',
        items: [
            {text: 'Glossary', link: '/reference/glossary'},
            {text: 'Wire Protocols', link: '/reference/protocols'},
            {text: 'Configuration Keys', link: '/reference/configuration'},
            {text: 'Error Codes', link: '/reference/error-codes'},
            {text: 'Annotations', link: '/reference/annotations'},
            {text: 'Modules and Artifacts', link: '/reference/modules'},
            {text: 'API Levels', link: '/reference/api-levels'},
            {text: 'What the Compiler Generates', link: '/guide/v10/generated-code'},
            {text: 'Kotlin API', link: '/dokka/index.html', target: '_blank'},
            {text: 'Storybook', link: '/storybook/', target: '_blank'},
        ],
    },
    {
        text: 'Resources',
        items: [
            {text: 'Contribute', link: '/contribute/documentation'},
            {text: 'Changelog', link: 'https://github.com/Ahoo-Wang/Wow/releases'},
        ],
    },
]
