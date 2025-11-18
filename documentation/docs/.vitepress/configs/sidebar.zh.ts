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
    '/zh/guide/': [
        {
            base: '/zh/guide/',
            text: '指南',
            collapsed: false,
            items: [
                {text: '简介', link: 'introduction'},
                {text: '快速上手', link: 'getting-started'},
                {text: '聚合建模', link: 'modeling'},
                // {text: '事件存储', link: 'eventstore'},
                // {text: '快照', link: 'snapshot'},
                {text: '命令网关', link: 'command-gateway'},
                {text: '分布式事务(Saga)', link: 'saga'},
                {text: '投影处理器', link: 'projection'},
                {text: '查询服务', link: 'query'},
                {text: 'Open API', link: 'open-api'},
                {text: '测试套件', link: 'test-suite'},
                {text: '商业智能', link: 'bi'},
                {text: '事件补偿', link: 'event-compensation'},
                // {text: '最佳实践', link: 'best-practices'},
                {text: '性能评测', link: 'perf-test'},
            ],
        }, {
            base: '/zh/guide/extensions/',
            text: '扩展',
            collapsed: false,
            items: [
                {text: 'Kafka', link: 'kafka'},
                {text: 'Mongo', link: 'mongo'},
                {text: 'Redis', link: 'redis'},
                {text: 'R2bdc', link: 'r2bdc'},
                {text: 'Elasticsearch', link: 'elasticsearch'},
                {text: 'OpenTelemetry', link: 'opentelemetry'},
                {text: 'WebFlux', link: 'webflux'},
                {text: 'Spring-Boot-Starter', link: 'spring-boot-starter'},
                {text: '兼容性测试套件', link: 'tck'},
            ],
        }, {
            base: '/zh/guide/advanced/',
            text: '深入',
            collapsed: false,
            items: [
                // {text: '架构', link: 'architecture'},
                {text: 'Id 生成器', link: 'id-generator'},
                {text: '编译器', link: 'compiler'},
                {text: '预分配 Key', link: 'prepare-key'},
                // {text: '指标', link: 'metrics'},
                // {text: '可观测性', link: 'observability'},
                // {text: '聚合调度器', link: 'aggregate-scheduler'},
            ],
        }, {
            text: '参考',
            base: '/zh/reference/',
            collapsed: false,
            items: [
                {text: '配置', link: 'config/basic'},
                {text: '示例', link: 'example/transfer'},
                {text: 'Awesome', link: 'awesome/cqrs'},
            ]
        }
    ],
    '/zh/reference/': [
        {
            text: '参考',
            items: [
                {
                    text: '配置',
                    base: '/zh/reference/config/',
                    collapsed: false,
                    items: [
                        {text: '基础配置', link: 'basic'},
                        {text: '命令总线', link: 'command'},
                        {text: '事件总线', link: 'event'},
                        {text: '事件溯源', link: 'eventsourcing'},
                    ],
                },
                {
                    text: '示例',
                    base: '/zh/reference/example/',
                    collapsed: false,
                    items: [
                        {text: '银行转账（JAVA）', link: 'transfer'},
                        {text: '订单系统', link: 'order'},
                    ],
                },
                {
                    text: 'Awesome',
                    base: '/zh/reference/awesome/',
                    collapsed: false,
                    items: [
                        {text: 'CQRS', link: 'cqrs'},
                        {text: 'Microservices', link: 'microservices'},
                        {text: 'Reactive', link: 'reactive'},
                    ]
                }
            ]
        },

    ]
}