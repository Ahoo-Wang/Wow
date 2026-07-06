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
    '/zh/onboarding/': [
        {
            text: '入门指南',
            collapsed: false,
            items: [
                {text: '贡献者指南', link: '/zh/onboarding/contributor-guide'},
                {text: '架构师指南', link: '/zh/onboarding/staff-engineer-guide'},
                {text: '管理层指南', link: '/zh/onboarding/executive-guide'},
                {text: '产品经理指南', link: '/zh/onboarding/product-manager-guide'},
            ],
        },
    ],
    '/zh/guide/': [
        {
            text: '入门指南',
            collapsed: false,
            items: [
                {text: '贡献者指南', link: '/zh/onboarding/contributor-guide'},
                {text: '架构师指南', link: '/zh/onboarding/staff-engineer-guide'},
                {text: '管理层指南', link: '/zh/onboarding/executive-guide'},
                {text: '产品经理指南', link: '/zh/onboarding/product-manager-guide'},
            ],
        },
        {
            base: '/zh/guide/',
            text: '基础',
            collapsed: false,
            items: [
                {text: '简介', link: 'introduction'},
                {text: '快速上手', link: 'getting-started'},
                {text: '核心概念', link: 'core-concepts'},
                {text: '聚合建模', link: 'modeling'},
                {text: '配置', link: 'configuration'},
            ],
        },
        {
            base: '/zh/guide/',
            text: '核心',
            collapsed: false,
            items: [
                {text: '事件存储', link: 'eventstore'},
                {text: '快照', link: 'snapshot'},
                {text: '命令网关', link: 'command-gateway'},
                {text: '分布式事务(Saga)', link: 'saga'},
                {text: '投影', link: 'projection'},
                {text: '查询服务', link: 'query'},
                {text: '数据权限', link: 'data-access'},
                {text: '事件处理器', link: 'event-processor'},
            ],
        },
        {
            base: '/zh/guide/',
            text: '工具',
            collapsed: false,
            items: [
                {text: 'Open API', link: 'open-api'},
                {text: '测试套件', link: 'test-suite'},
                {text: '测试运行体系', link: 'test-runtime'},
                {text: '商业智能', link: 'bi'},
                {text: '事件补偿', link: 'event-compensation'},
            ],
        },
        {
            base: '/zh/guide/',
            text: '最佳实践',
            collapsed: false,
            items: [
                {text: '最佳实践', link: 'best-practices'},
                {text: '性能评测', link: 'perf-test'},
                {text: '故障排查', link: 'troubleshooting'},
                {text: '迁移指南', link: 'migration'},
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
            text: '深入',
            collapsed: true,
            items: [
                {text: '架构', link: 'architecture'},
                {text: '聚合生命周期', link: 'aggregate-lifecycle'},
                {text: '事件总线', link: 'event-bus'},
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
            collapsed: false,
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
            collapsed: false,
            items: [
                {text: '银行转账（JAVA）', link: 'transfer'},
                {text: '订单系统', link: 'order'},
                {text: '事件补偿', link: 'compensation'},
            ],
        },
        {
            text: '生态',
            collapsed: false,
            items: [
                {text: '生态资源', link: '/zh/reference/ecosystem'},
            ],
        },
    ],
}
