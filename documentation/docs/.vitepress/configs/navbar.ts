import {DefaultTheme} from "vitepress/types/default-theme";

export const navbar: DefaultTheme.NavItem[] = [
    {
        text: '指南',
        link: '/guide/getting-started'
    },
    {
        text: '配置',
        link: '/reference/config/basic',
    },
    {
        text: '示例',
        items: [
            {text: '银行转账(JAVA)', link: '/reference/example/transfer'},
        ],
    }, {
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
            },
            {
                text: 'CQRS',
                items: [
                    {
                        text: 'Event Sourcing - Specifications',
                        link: 'https://abdullin.com/post/event-sourcing-specifications/'
                    },
                    {
                        text: 'Testing Event Sourcing',
                        link: 'https://event-driven.io/en/testing_event_sourcing/'
                    }, {
                        text: 'CQRS Journey',
                        link: 'https://learn.microsoft.com/en-us/previous-versions/msp-n-p/jj554200(v=pandp.10)'
                    }, {
                        text: 'Saga distributed transactions pattern',
                        link: 'https://learn.microsoft.com/en-us/azure/architecture/reference-architectures/saga/saga'
                    }
                ]
            },
            {
                text: '响应式编程',
                items: [
                    {
                        text: '响应式宣言',
                        link: 'https://www.reactivemanifesto.org/zh-CN'
                    }, {
                        text: 'R2DBC',
                        link: 'https://r2dbc.io/'
                    }]
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