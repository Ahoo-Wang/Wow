import {DefaultTheme} from "vitepress/types/default-theme";

export const navbarEn: DefaultTheme.NavItem[] = [
    {
        text: 'Guide',
        link: '/en/guide/getting-started',
        activeMatch: '^/en/guide/'
    },
    {
        text: 'Reference',
        activeMatch: '^/en/reference/',
        items: [
            {
                text: 'Configuration',
                items: [
                    {text: 'Basic Configuration', link: '/en/reference/config/basic'},
                    {text: 'Command Bus', link: '/en/reference/config/command'},
                    {text: 'Event Bus', link: '/en/reference/config/event'},
                    {text: 'Event Sourcing', link: '/en/reference/config/eventsourcing'},
                ],
            },
            {
                text: 'Examples',
                items: [
                    {text: 'Bank Transfer (JAVA)', link: '/en/reference/example/transfer'},
                    {text: 'Order System', link: '/en/reference/example/order'},
                ],
            },
            {
                text: 'Awesome',
                items: [
                    {text: 'CQRS', link: '/en/reference/awesome/cqrs'},
                    {text: 'Microservices', link: '/en/reference/awesome/microservices'},
                    {text: 'Reactive', link: '/en/reference/awesome/reactive'},
                ],
            },
        ]
    }
    , {
        text: 'API',
        link: `/dokka/index.html`,
        target: '_blank'
    },
    {
        text: "Resources",
        items: [
            {
                text: 'Project template for quickly building DDD projects based on Wow framework',
                link: 'https://github.com/Ahoo-Wang/wow-project-template'
            },
            {
                text: 'Powerful TypeScript code generation tool',
                link: 'https://github.com/Ahoo-Wang/fetcher/blob/main/packages/generator/'
            },
            {
                text: 'Fluent Kotlin Assertion Library',
                link: 'https://github.com/Ahoo-Wang/FluentAssert'
            },
            {
                text: 'Open source projects - Microservice governance',
                items: [
                    {
                        text: 'CosId - Universal, flexible, high-performance distributed ID generator',
                        link: 'https://github.com/Ahoo-Wang/CosId'
                    },
                    {
                        text: 'CoSky - High-performance, low-cost microservice governance platform',
                        link: 'https://github.com/Ahoo-Wang/CoSky'
                    },
                    {
                        text: 'CoSec - Multi-tenant responsive security framework based on RBAC and policies',
                        link: 'https://github.com/Ahoo-Wang/CoSec'
                    },
                    {
                        text: 'CoCache - Distributed consistent secondary cache framework',
                        link: 'https://github.com/Ahoo-Wang/CoCache'
                    },
                    {
                        text: 'Simba - Easy-to-use, flexible distributed lock service',
                        link: 'https://github.com/Ahoo-Wang/Simba'
                    }
                ]
            }
        ]
    },
    {
        text: `Changelog`,
        link: `https://github.com/Ahoo-Wang/Wow/releases`
    },
    {
        text: `Gitee`,
        link: `https://gitee.com/AhooWang/Wow`
    }
]