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

// 视图引擎在 TypeScript 客户端区域里自成四组：入门、概念、指南、参考。
// 阶段 7 的 B2～B5 各自只往自己的那一组里加页面（概念归 B2，宿主、操作与 CSP 归 B3，
// 存储归 B4，API 参考归 B5），互不改动别的组。
const viewEngineStart: DefaultTheme.SidebarItem = {
    base: '/zh/guide/typescript/',
    text: '视图引擎',
    items: [
        {text: '概览', link: 'view-engine'},
    ],
}

const viewEngineConcepts: DefaultTheme.SidebarItem = {
    base: '/zh/guide/typescript/',
    text: '视图引擎：概念',
    items: [
        {text: '核心概念', link: 'view-engine-concepts'},
    ],
}

const viewEngineGuides: DefaultTheme.SidebarItem = {
    base: '/zh/guide/typescript/',
    text: '视图引擎：指南',
    items: [
        {text: '写好一份定义', link: 'view-engine-definitions'},
        {text: '主题', link: 'view-engine-theming'},
        {text: '可访问性', link: 'view-engine-accessibility'},
        {text: '视图存在哪里', link: 'view-engine-storage'},
        {text: '接进宿主', link: 'view-engine-host'},
        {text: '声明式操作', link: 'view-engine-actions'},
        {text: '内容安全策略', link: 'view-engine-csp'},
    ],
}

const viewEngineReference: DefaultTheme.SidebarItem[] = [
    {
        base: '/zh/reference/typescript/wow-view-engine/',
        text: 'wow-view-engine',
        collapsed: true,
        items: [
            {text: '入口与契约', link: 'index.html'},
            {text: '引擎与资源', link: 'engine'},
            {text: '定义与字段类型', link: 'definitions'},
            {text: '宿主接线', link: 'host'},
            {text: '工作台与嵌入', link: 'components'},
            {text: 'React Hooks', link: 'react'},
            {text: '持久化端口', link: 'store'},
            {text: '测试工具', link: 'testing'},
            {text: 'Issue code', link: 'issues'},
            {
                // 生成的符号索引只有英文版。
                base: '/reference/typescript/wow-view-engine/',
                text: '符号索引（英文）',
                collapsed: true,
                items: [
                    {text: '根入口', link: 'symbols'},
                    {text: '/react', link: 'symbols-react'},
                    {text: '/ui', link: 'symbols-ui'},
                    {text: '/testing', link: 'symbols-testing'},
                    {text: '/react-router', link: 'symbols-react-router'},
                ],
            },
        ],
    },
    {
        base: '/zh/reference/typescript/wow-view-store/',
        text: 'wow-view-store',
        collapsed: true,
        items: [
            {text: 'Store 与合同', link: 'index.html'},
        ],
    },
]

// 还没有页面的组不出现在侧边栏里。
const viewEngine: DefaultTheme.SidebarItem[] = [
    ...[viewEngineStart, viewEngineConcepts, viewEngineGuides].filter((group) => group.items?.length),
    ...viewEngineReference,
]

// TypeScript 客户端区域共用一个侧边栏：指南加上各个包的参考。
const typescriptZh: DefaultTheme.SidebarItem[] = [
    {
        base: '/zh/guide/typescript/',
        text: 'TypeScript 客户端',
        items: [
            {text: '概览', link: 'index.html'},
            {text: '快速开始', link: 'quick-start'},
            {text: '认证与拦截器', link: 'authentication'},
            {text: '从任意 OpenAPI 文档生成', link: 'generated-client'},
            {text: '在 CI 中重新生成', link: 'regenerate-in-ci'},
            {text: '兼容性与版本', link: 'compatibility'},
            {text: '排障', link: 'troubleshooting'},
        ],
    },
    {
        base: '/zh/reference/typescript/wow-generator/',
        text: 'wow-generator',
        collapsed: true,
        items: [
            {text: '概览', link: 'index.html'},
            {text: 'CLI', link: 'cli'},
            {text: '配置', link: 'configuration'},
            {text: '程序化 API', link: 'programmatic-api'},
            {text: '生成产物与重新生成', link: 'generated-output'},
            {text: 'Wow 聚合识别', link: 'wow-discovery'},
            {text: '符号索引', link: 'symbols'},
        ],
    },
    ...viewEngine,
]

export const sidebarZh: DefaultTheme.Sidebar = {
    '/zh/guide/typescript/': typescriptZh,
    '/zh/reference/typescript/': typescriptZh,
    '/zh/workbench/': [
        {
            text: '使用工作台',
            base: '/zh/workbench/',
            items: [
                {text: '概览', link: 'index.html'},
                {text: '记录视图', link: 'records'},
                {text: '分析视图', link: 'analysis'},
                {text: '仪表盘', link: 'dashboards'},
                {text: '保存与管理视图', link: 'views'},
            ],
        },
    ],
    '/zh/guide/v10/': [
        {
            base: '/zh/guide/v10/',
            text: '指南',
            items: [
                {text: '快速开始', link: 'quickstart'},
                {text: '编写服务', link: 'index.html'},
                {text: '编程模型', link: 'programming-model'},
                {text: '编译器生成什么', link: 'generated-code'},
                {text: '测试', base: '/zh/guide/', link: 'testing'},
                {text: 'HTTP API', link: 'http-api'},
                {text: '查询语言', link: 'query'},
                {text: '配置项', link: 'configuration'},
                {text: '部署', link: 'deployment'},
            ],
        },
    ],
    '/zh/guide/': [
        {
            base: '/zh/guide/',
            text: '开发指南',
            items: [
                {text: '测试领域代码', link: 'testing'},
            ],
        },
    ],
    '/zh/concepts/': [
        {
            base: '/zh/concepts/',
            text: '概念',
            items: [
                {text: '架构总览', link: 'index.html'},
                {text: '决定与演化', link: 'programming-model'},
                {text: '日志就是总线', link: 'event-log'},
                {text: '写：从准入到追加', link: 'write-path'},
                {text: '等待结果', link: 'waits'},
                {text: '订阅、失败与重放', link: 'subscriptions'},
                {text: '读模型', link: 'read-models'},
                {text: '一份契约：Manifest', link: 'contract'},
                {text: '身份、租户与访问', link: 'identity'},
                {text: '上下文之间', link: 'cross-context'},
            ],
        },
    ],
    '/zh/reference/': [
        {
            base: '/zh/reference/',
            text: '参考',
            items: [
                {text: '术语表', link: 'glossary'},
            ],
        },
    ],
    '/zh/contribute/': [
        {
            base: '/zh/contribute/',
            text: '参与贡献',
            items: [
                {text: '文档治理', link: 'documentation'},
            ],
        },
    ],
}
