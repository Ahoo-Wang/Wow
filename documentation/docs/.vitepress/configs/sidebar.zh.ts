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

export const sidebarZh: DefaultTheme.Sidebar = {
    '/zh/articles/': [
        {
            text: '文章',
            base: '/zh/articles/',
            collapsed: true,
            items: [
                {text: '文章首页', link: 'index.html'},
                {text: '接口返回 200，查询却查不到', link: 'command-success-is-not-complete'},
                {text: '传统架构 VS Wow：从写接口到交付领域模型', link: 'traditional-vs-wow-architecture'},
                {text: 'AI 越强，业务模型越值钱', link: 'why-ddd-fits-ai-era'},
            ],
        },
    ],
    '/zh/onboarding/': [
        {
            base: '/zh/onboarding/',
            text: '评估与参与',
            collapsed: true,
            items: [
                {text: '按角色选择', link: 'index.html'},
                {text: '贡献者指南', link: 'contributor-guide'},
                {text: '资深工程师指南', link: 'staff-engineer-guide'},
                {text: '管理者指南', link: 'executive-guide'},
                {text: '产品经理指南', link: 'product-manager-guide'},
            ],
        },
    ],
    '/zh/guide/': [
        {
            base: '/zh/guide/',
            text: '开始使用',
            collapsed: true,
            items: [
                {text: '指南导览', link: 'index.html'},
                {text: '简介', link: 'introduction'},
                {text: '快速上手', link: 'getting-started'},
                {text: '接入现有项目', link: 'existing-project'},
                {text: '核心概念', link: 'core-concepts'},
            ],
        },
        {
            base: '/zh/guide/',
            text: '领域模型',
            collapsed: true,
            items: [
                {text: '领域模型概览', link: 'domain/index.html'},
                {text: '聚合与不变量', link: 'domain/aggregate'},
                {text: '事件溯源', link: 'domain/event-sourcing'},
                {text: '事件演进', link: 'domain/event-evolution'},
                {text: '快照', link: 'domain/snapshot'},
                {text: '聚合生命周期', link: 'domain/lifecycle'},
            ],
        },
        {
            base: '/zh/guide/',
            text: '命令',
            collapsed: true,
            items: [
                {text: '命令概览', link: 'command/index.html'},
                {text: '定义命令', link: 'command/definition'},
                {text: '发送命令', link: 'command/sending'},
                {text: 'API 客户端', link: 'command/api-client'},
                {text: '完成语义', link: 'command/completion'},
                {text: '失败与幂等', link: 'command/reliability'},
                {
                    text: '工作原理',
                    collapsed: true,
                    items: [
                        {text: '命令处理管线', link: 'command/internals/pipeline'},
                        {text: '命令等待运行时', link: 'command/internals/wait-runtime'},
                        {text: '命令传输与路由', link: 'command/internals/transport'},
                    ],
                },
            ],
        },
        {
            base: '/zh/guide/',
            text: '事件与协作',
            collapsed: true,
            items: [
                {text: '事件与协作概览', link: 'event/index.html'},
                {text: '事件处理器', link: 'event/processor'},
                {text: 'Saga', link: 'event/saga'},
                {text: '事件补偿', link: 'event/compensation'},
                {text: '事件分发管线', link: 'event/dispatch'},
            ],
        },
        {
            base: '/zh/guide/',
            text: '读模型与查询',
            collapsed: true,
            items: [
                {text: '投影', link: 'projection'},
                {text: '查询服务', link: 'query'},
                {text: '数据权限', link: 'data-access'},
            ],
        },
        {
            base: '/zh/guide/',
            text: '接口与自动化',
            collapsed: true,
            items: [
                {text: 'Open API', link: 'open-api'},
                {text: 'Agent Skills', link: 'skills'},
                {text: '商业智能', link: 'bi'},
            ],
        },
        {
            base: '/zh/guide/',
            text: '测试与交付',
            collapsed: true,
            items: [
                {text: '测试套件', link: 'test-suite'},
                {text: '应用测试', link: 'application-testing'},
                {text: '框架测试与基准', link: 'test-runtime'},
            ],
        },
        {
            base: '/zh/guide/',
            text: '生产运维',
            collapsed: true,
            items: [
                {text: '配置', link: 'configuration'},
                {text: 'BI 部署与恢复', link: 'bi-operations'},
                {text: '生产最佳实践', link: 'best-practices'},
                {text: '备份、恢复与重放', link: 'recovery'},
                {text: '故障排查', link: 'troubleshooting'},
                {
                    text: '迁移指南',
                    link: 'migration',
                    collapsed: true,
                    items: [
                        {text: '传统架构迁移', link: 'migration/traditional-architecture'},
                        {text: 'Wow v6 迁移到 v8', link: 'migration/v6-to-v8'},
                        {text: '运行时编排迁移', link: 'migration/runtime-orchestration'},
                    ],
                },
            ],
        },
        {
            base: '/zh/guide/extensions/',
            text: '扩展',
            collapsed: true,
            items: [
                {text: 'Kafka', link: 'kafka'},
                {text: 'Mongo', link: 'mongo'},
                {text: 'Redis', link: 'redis'},
                {text: 'Elasticsearch', link: 'elasticsearch'},
                {text: 'OpenTelemetry', link: 'opentelemetry'},
                {text: 'WebFlux', link: 'webflux'},
                {text: 'CoCache', link: 'cocache'},
                {text: 'CoSec', link: 'cosec'},
                {text: 'API 客户端', link: 'apiclient'},
                {text: 'Spring-Boot-Starter', link: 'spring-boot-starter'},
                {text: '兼容性测试套件', link: 'tck'},
            ],
        },
        {
            base: '/zh/guide/advanced/',
            text: '深入原理',
            collapsed: true,
            items: [
                {text: '架构', link: 'architecture'},
                {text: '运行时生命周期', link: 'runtime-lifecycle'},
                {text: '序列化', link: 'serialization'},
                {text: '数据流', link: 'data-flow'},
                {text: '模块依赖', link: 'module-dependencies'},
                {text: 'ID 生成器', link: 'id-generator'},
                {text: '编译器', link: 'compiler'},
                {text: '预分配 Key', link: 'prepare-key'},
                {text: 'JSON Schema', link: 'schema'},
                {text: '指标', link: 'metrics'},
                {text: '可观测性', link: 'observability'},
                {text: '聚合调度器', link: 'aggregate-scheduler'},
            ],
        },
    ],
    '/zh/reference/': [
        {
            text: '配置',
            base: '/zh/reference/config/',
            collapsed: true,
            items: [
                {text: '核心配置', link: 'core'},
                {text: '基础设施', link: 'infrastructure'},
                {text: '可观测性', link: 'observability'},
                {text: '事件补偿', link: 'compensation'},
            ],
        },
        {
            text: '示例',
            base: '/zh/reference/example/',
            collapsed: true,
            items: [
                {text: '订单与购物车（Kotlin）', link: 'order'},
                {text: '银行转账（JAVA）', link: 'transfer'},
                {text: '事件补偿', link: 'compensation'},
            ],
        },
        {
            text: '生态',
            collapsed: true,
            items: [
                {text: '生态资源', link: '/zh/reference/ecosystem'},
            ],
        },
    ],
}
