import {DefaultTheme} from "vitepress/types/default-theme";

export const navbarEn: DefaultTheme.NavItem[] = [
    {
        text: 'Guide',
        link: '/guide/getting-started',
        activeMatch: '^/guide/'
    },
    {
        text: 'Reference',
        activeMatch: '^/reference/',
        items: [
            {
                text: 'Configuration',
                items: [
                    {text: 'Basic Configuration', link: '/reference/config/basic'},
                    {text: 'Command Bus', link: '/reference/config/command'},
                    {text: 'Event Bus', link: '/reference/config/event'},
                    {text: 'Event Sourcing', link: '/reference/config/eventsourcing'},
                ],
            },
            {
                text: 'Examples',
                items: [
                    {text: 'Bank Transfer (JAVA)', link: '/reference/example/transfer'},
                    {text: 'Order System', link: '/reference/example/order'},
                    {text: 'Event Compensation', link: '/reference/example/compensation'},
                ],
            },
            {
                text: 'Awesome',
                items: [
                    {text: 'CQRS', link: '/reference/awesome/cqrs'},
                    {text: 'Microservices', link: '/reference/awesome/microservices'},
                    {text: 'Reactive', link: '/reference/awesome/reactive'},
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
            { text: '-' },
            {
                text: 'CosId - Distributed ID Generator',
                link: 'https://github.com/Ahoo-Wang/CosId'
            },
            {
                text: 'CoSky - Microservice Governance',
                link: 'https://github.com/Ahoo-Wang/CoSky'
            },
            {
                text: 'CoSec - Reactive Security Framework',
                link: 'https://github.com/Ahoo-Wang/CoSec'
            },
            {
                text: 'CoCache - Distributed Cache',
                link: 'https://github.com/Ahoo-Wang/CoCache'
            },
            {
                text: 'Simba - Distributed Lock',
                link: 'https://github.com/Ahoo-Wang/Simba'
            }
        ]
    },
    {
        text: `Changelog`,
        link: `https://github.com/Ahoo-Wang/Wow/releases`
    }
]