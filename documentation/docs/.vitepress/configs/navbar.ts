import {DefaultTheme} from "vitepress/types/default-theme";

export const navbar: DefaultTheme.NavItem[] = [
    {
        text: '指南',
        link: '/zh/guide/getting-started',
        activeMatch: '^/zh/guide/'
    },
    {
        text: '参考',
        activeMatch: '^/zh/reference/',
        items: [
            {
                text: '配置',
                items: [
                    {text: '基础配置', link: '/zh/reference/config/basic'},
                    {text: '命令总线', link: '/zh/reference/config/command'},
                    {text: '事件总线', link: '/zh/reference/config/event'},
                    {text: '事件溯源', link: '/zh/reference/config/eventsourcing'},
                ],
            },
            {
                text: '示例',
                items: [
                    {text: '银行转账(JAVA)', link: '/zh/reference/example/transfer'},
                    {text: '订单系统', link: '/zh/reference/example/order'},
                ],
            },
            {
                text: 'Awesome',
                items: [
                    {text: 'CQRS', link: '/zh/reference/awesome/cqrs'},
                    {text: 'Microservices', link: '/zh/reference/awesome/microservices'},
                    {text: 'Reactive', link: '/zh/reference/awesome/reactive'},
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
        text: "资源",
        items: [
            {
                text: '用于快速构建基于 Wow 框架的 DDD 项目模板',
                link: 'https://github.com/Ahoo-Wang/wow-project-template'
            },
            {
                text: '功能强大的 TypeScript 代码生成工具',
                link: 'https://github.com/Ahoo-Wang/fetcher/blob/main/packages/generator/'
            },
            {
                text: '流畅的 Kotlin 断言库',
                link: 'https://github.com/Ahoo-Wang/FluentAssert'
            },
            {
                text: '开源项目 - 微服务治理',
                items: [
                    {
                        text: 'CosId - 通用、灵活、高性能的分布式 ID 生成器',
                        link: 'https://github.com/Ahoo-Wang/CosId'
                    },
                    {
                        text: 'CoSky - 高性能、低成本微服务治理平台',
                        link: 'https://github.com/Ahoo-Wang/CoSky'
                    },
                    {
                        text: 'CoSec - 基于 RBAC 和策略的多租户响应式安全框架',
                        link: 'https://github.com/Ahoo-Wang/CoSec'
                    },
                    {
                        text: 'CoCache - 分布式一致性二级缓存框架',
                        link: 'https://github.com/Ahoo-Wang/CoCache'
                    },
                    {
                        text: 'Simba - 易用、灵活的分布式锁服务',
                        link: 'https://github.com/Ahoo-Wang/Simba'
                    }
                ]
            }
        ]
    },
    {
        text: `更新日志`,
        link: `https://github.com/Ahoo-Wang/Wow/releases`
    },
    {
        text: `Gitee`,
        link: `https://gitee.com/AhooWang/Wow`
    }
]