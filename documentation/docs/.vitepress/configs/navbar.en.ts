import {DefaultTheme} from "vitepress/types/default-theme";

export const navbarEn: DefaultTheme.NavItem[] = [
    {
        text: 'Start',
        items: [
            {text: 'Why Wow', link: '/guide/introduction'},
            {text: '30-Minute Quickstart', link: '/guide/getting-started'},
            {text: 'Adopt in an Existing Project', link: '/guide/existing-project'},
            {text: 'Evaluate or Contribute by Role', link: '/onboarding/'},
        ],
    },
    {text: 'Development Guide', link: '/guide/'},
    {
        text: 'Production Operations',
        items: [
            {text: 'Production Best Practices', link: '/guide/best-practices'},
            {text: 'Backup, Restore, and Replay', link: '/guide/recovery'},
            {text: 'Observability', link: '/guide/advanced/observability'},
            {text: 'Troubleshooting', link: '/guide/troubleshooting'},
            {text: 'Migration Guide', link: '/guide/migration'},
        ],
    },
    {
        text: 'Reference',
        items: [
            {
                text: 'Configuration',
                items: [
                    {text: 'Core Configuration', link: '/reference/config/core'},
                    {text: 'Infrastructure', link: '/reference/config/infrastructure'},
                    {text: 'Observability', link: '/reference/config/observability'},
                    {text: 'Compensation', link: '/reference/config/compensation'},
                ],
            },
            {
                text: 'Examples',
                items: [
                    {text: 'Order and Cart (Kotlin)', link: '/reference/example/order'},
                    {text: 'Bank Transfer (Java)', link: '/reference/example/transfer'},
                    {text: 'Event Compensation', link: '/reference/example/compensation'},
                ],
            },
        ],
    },
    {text: 'API', link: '/dokka/index.html', target: '_blank'},
    {
        text: 'Resources',
        items: [
            {text: 'Articles', link: '/articles/'},
            {text: 'Agent Skills', link: '/guide/skills'},
            {text: 'Project Template', link: 'https://github.com/Ahoo-Wang/wow-project-template'},
            {text: 'Ecosystem', link: '/reference/ecosystem'},
            {text: 'Changelog', link: 'https://github.com/Ahoo-Wang/Wow/releases'},
        ],
    },
]
