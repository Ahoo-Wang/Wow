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

import type { Preview } from '@storybook/react-vite';
import './preview.css';
import { DocsPage } from './DocsPage.js';

const preview: Preview = {
  parameters: {
    a11y: {
      context: {
        exclude: [['.ant-select-dropdown'], ['.ant-table-measure-row']],
      },
      test: 'error',
    },
    controls: {
      matchers: {
        color: /(background|color)$/i,
        date: /Date$/i,
      },
    },
    docs: {
      page: DocsPage,
      codePanel: true,
      toc: true,
    },
    options: {
      storySort: {
        order: [
          '开始使用',
          'HTTP',
          '事件与存储',
          'React Hooks',
          'View Engine',
          [
            '入门与业务流程',
            ['最小接入', '全链路体验', '订单流程'],
            '真实 API 接入',
            ['补偿数据', '补偿分析'],
            '引擎与宿主',
            [
              '我的工作视图',
              '视图管理',
              '嵌入视图',
              '运行时工具',
              '配置与恢复',
              '本地操作宿主',
            ],
            '数据视图',
            ['表格与汇总', '查询与分页', '卡片模式', '布局与主题'],
            '分析视图',
            ['配置与执行', '图表与结果', '性能验收'],
            '仪表盘视图',
            ['业务仪表盘'],
            '查询与筛选',
            ['组合筛选', '内置筛选器'],
            '扩展与组件',
            ['业务扩展', '内置单元格', 'Select', '日期时间', '可导入主题'],
          ],
          'Viewer',
          '开发验证',
        ],
      },
    },
  },
  tags: ['autodocs', 'test'],
};

export default preview;
