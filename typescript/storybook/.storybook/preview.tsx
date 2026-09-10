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
            '开始体验',
            ['全链路体验'],
            '订单业务流程',
            [
              '接单与审核',
              '收款与放行',
              '备货与交付',
              '对账与结算',
              '售后与关闭',
            ],
            '开发接入',
            ['最小接入', '我的工作视图', '业务扩展'],
            '专项场景',
            [
              '数据展示',
              ['表格与汇总', '卡片模式', '布局与主题'],
              '查询与筛选',
              ['查询与分页', '组合筛选', '内置筛选器'],
              '视图与运行时',
              ['视图管理', '运行时工具', '配置与恢复', '本地操作宿主'],
              '组件与主题',
              ['内置单元格', 'Select', '日期时间', '可导入主题'],
            ],
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
