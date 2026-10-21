import {DefaultTheme} from "vitepress/types/default-theme";

export const navbarZh: DefaultTheme.NavItem[] = [
    {text: '快速开始', link: '/zh/guide/v10/quickstart'},
    {
        text: '指南',
        activeMatch: '/zh/guide/v10/',
        items: [
            {text: '编写服务', link: '/zh/guide/v10/'},
            {text: '编程模型', link: '/zh/guide/v10/programming-model'},
            {text: '测试', link: '/zh/guide/v10/testing'},
            {text: 'HTTP API', link: '/zh/guide/v10/http-api'},
            {text: '查询语言', link: '/zh/guide/v10/query'},
            {text: '部署', link: '/zh/guide/v10/deployment'},
        ],
    },
    {
        text: 'TypeScript',
        activeMatch: '/zh/(guide/typescript|typescript|reference/typescript)/',
        items: [
            {
                text: '指南',
                items: [
                    {text: '概览', link: '/zh/guide/typescript/'},
                    {text: '生成客户端', link: '/zh/guide/typescript/generated-client'},
                    {text: '视图引擎', link: '/zh/typescript/view-engine/'},
                    {text: '使用工作台（面向使用者）', link: '/zh/data/workbench'},
                ],
            },
            {
                text: '参考',
                items: [
                    {text: 'wow-generator', link: '/zh/reference/typescript/wow-generator/'},
                    {text: 'wow-view-engine', link: '/zh/reference/typescript/wow-view-engine/'},
                    {text: 'wow-view-store', link: '/zh/reference/typescript/wow-view-store/'},
                ],
            },
        ],
    },
    {
        text: '参考',
        items: [
            {text: '配置项', link: '/zh/guide/v10/configuration'},
            {text: '编译器生成什么', link: '/zh/guide/v10/generated-code'},
            {text: 'Kotlin API', link: '/dokka/index.html', target: '_blank'},
            {text: 'Storybook', link: '/storybook/', target: '_blank'},
        ],
    },
    {
        text: '资源',
        items: [
            {text: '参与贡献', link: '/zh/contribute/documentation'},
            {text: '更新日志', link: 'https://github.com/Ahoo-Wang/Wow/releases'},
        ],
    },
]
