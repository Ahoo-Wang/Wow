import {DefaultTheme} from "vitepress/types/default-theme";

export const navbarZh: DefaultTheme.NavItem[] = [
    {
        text: '开始使用',
        items: [
            {text: '认识 Wow', link: '/zh/guide/introduction'},
            {text: '30 分钟快速上手', link: '/zh/guide/getting-started'},
            {text: '接入现有项目', link: '/zh/guide/existing-project'},
            {text: '按角色评估与参与', link: '/zh/onboarding/'},
        ],
    },
    {text: '开发指南', link: '/zh/guide/'},
    {
        text: '生产运维',
        items: [
            {text: '生产最佳实践', link: '/zh/guide/best-practices'},
            {text: '备份、恢复与重放', link: '/zh/guide/recovery'},
            {text: '可观测性', link: '/zh/guide/advanced/observability'},
            {text: '故障排查', link: '/zh/guide/troubleshooting'},
            {text: '迁移指南', link: '/zh/guide/migration'},
        ],
    },
    {
        text: '参考',
        items: [
            {
                text: '配置',
                items: [
                    {text: '核心配置', link: '/zh/reference/config/core'},
                    {text: '基础设施', link: '/zh/reference/config/infrastructure'},
                    {text: '可观测性', link: '/zh/reference/config/observability'},
                    {text: '事件补偿', link: '/zh/reference/config/compensation'},
                ],
            },
            {
                text: '示例',
                items: [
                    {text: '订单与购物车（Kotlin）', link: '/zh/reference/example/order'},
                    {text: '银行转账（Java）', link: '/zh/reference/example/transfer'},
                    {text: '事件补偿', link: '/zh/reference/example/compensation'},
                ],
            },
        ],
    },
    {text: 'API', link: '/dokka/index.html', target: '_blank'},
    {
        text: '资源',
        items: [
            {text: '文章', link: '/zh/articles/'},
            {text: 'Agent Skills', link: '/zh/guide/skills'},
            {text: '项目模板', link: 'https://github.com/Ahoo-Wang/wow-project-template'},
            {text: '生态资源', link: '/zh/reference/ecosystem'},
            {text: '更新日志', link: 'https://github.com/Ahoo-Wang/Wow/releases'},
        ],
    },
]
