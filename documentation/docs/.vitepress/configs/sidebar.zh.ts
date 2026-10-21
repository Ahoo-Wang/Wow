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

// 视图引擎的指南自成一组，与 TypeScript 客户端和各包的参考并列。
const viewEngineGuides: DefaultTheme.SidebarItem = {
    base: '/zh/typescript/view-engine/',
    text: '视图引擎',
    items: [
        {text: '核心概念', link: 'index.html'},
        {text: '定义视图', link: 'definitions'},
        {text: '接入宿主', link: 'host'},
        {text: '操作', link: 'actions'},
        {text: '视图存在哪里', link: 'storage'},
        {text: '主题', link: 'theming'},
        {text: '可访问性', link: 'accessibility'},
        {text: '内容安全策略', link: 'csp'},
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
const viewEngine: DefaultTheme.SidebarItem[] = [viewEngineGuides, ...viewEngineReference]

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
    '/zh/typescript/': typescriptZh,
    '/zh/reference/typescript/': typescriptZh,
    '/zh/data/': [
        {
            text: '数据',
            base: '/zh/data/',
            items: [
                {text: '工作台', link: 'workbench'},
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
                {text: '部署', base: '/zh/operate/', link: 'topology'},
            ],
        },
    ],
    '/zh/operate/': [
        {
            base: '/zh/operate/',
            text: '运维',
            items: [
                {text: '运行 Wow', link: 'index.html'},
                {text: '部署模型', link: 'topology'},
                {text: '配置服务', link: 'configuration'},
                {text: 'PostgreSQL', link: 'postgresql'},
                {text: 'Kafka', link: 'kafka'},
                {text: '保护部署', link: 'security'},
                {text: 'ops 服务与控制台', link: 'ops-console'},
            ],
        },
    ],
    '/zh/migrate/': [
        {
            base: '/zh/migrate/',
            text: '从 9.x 迁移',
            items: [
                {text: '从 9.x 迁移', link: 'index.html'},
                {text: 'V10 中的 9.x 能力', link: 'capabilities'},
                {text: '迁移数据', link: 'data'},
                {text: 'migrate-data 命令行', base: '/zh/reference/', link: 'migrate-data'},
            ],
        },
    ],
    '/zh/guide/': [
        {
            base: '/zh/guide/',
            text: '建模',
            items: [
                {text: '编写聚合', link: 'aggregates'},
                {text: '拒绝与错误码', link: 'rejections'},
                {text: '演进事件', link: 'event-evolution'},
                {text: '所有者、空间与标签', link: 'ownership'},
                {text: '唯一键', link: 'unique-keys'},
            ],
        },
        {
            base: '/zh/guide/',
            text: '流程',
            items: [
                {text: '编写 Saga', link: 'sagas'},
                {text: '投影与反应器', link: 'projections'},
                {text: '定时命令', link: 'scheduling'},
                {text: '失败与重试', link: 'failures'},
            ],
        },
        {
            base: '/zh/guide/',
            text: '跨上下文',
            items: [
                {text: '发布与消费事件流', link: 'published-streams'},
                {text: '调用其他服务', link: 'calling-services'},
            ],
        },
        {
            base: '/zh/guide/',
            text: '测试',
            items: [
                {text: '测试领域代码', link: 'testing'},
                {text: '测试服务', link: 'testing-services'},
            ],
        },
        {
            base: '/zh/guide/',
            text: '宿主',
            items: [
                {text: '在 Spring Boot 上运行', link: 'spring-boot'},
                {text: '用 Java 编写领域', link: 'java'},
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
                {text: '线上协议', link: 'protocols'},
                {text: 'migrate-data 命令行', link: 'migrate-data'},
                {text: '配置项', link: 'configuration'},
                {text: '错误码', link: 'error-codes'},
                {text: '注解', link: 'annotations'},
                {text: 'Manifest 格式', link: 'manifest'},
                {text: '模块与制品', link: 'modules'},
                {text: 'API 级别', link: 'api-levels'},
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
