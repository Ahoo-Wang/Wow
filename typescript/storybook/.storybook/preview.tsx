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
import { withThemeByClassName } from '@storybook/addon-themes';
import './preview.css';
import { DocsPage } from './DocsPage.js';

const preview: Preview = {
  parameters: {
    a11y: {
      context: {
        exclude: [
          ['.ant-select-dropdown'],
          ['.ant-table-measure-row'],
          // Base UI puts a pair of focus sentinels around every open popup —
          // `aria-hidden` and `tabindex="0"`, which is exactly the shape
          // `aria-hidden-focus` is written to catch. They are how the popup
          // keeps the tab order inside itself, they are what the library
          // ships, and no story can render one differently.
          ['[data-base-ui-focus-guard]'],
        ],
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
          // The rewrite (packages/view-engine/docs/design/) delivers one
          // surface per step; this list grows with them rather than reserving
          // names for stories that do not exist yet.
          ['首页', '数据视图', '分析视图', '仪表盘视图', '真实后端'],
          'Viewer',
          '开发验证',
        ],
      },
    },
  },
  // View Engine's dark theme wakes up when `.dark` sits on an ancestor of
  // `.fve-root`; the addon puts it on `<html>`, so the toolbar switch reaches
  // every story the same way a host application would.
  decorators: [
    withThemeByClassName({
      themes: { light: '', dark: 'dark' },
      defaultTheme: 'light',
    }),
  ],
  tags: ['autodocs', 'test'],
};

export default preview;
