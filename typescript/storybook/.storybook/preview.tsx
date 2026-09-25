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

import type { Decorator, Preview } from '@storybook/react-vite';
import { DecoratorHelpers } from '@storybook/addon-themes';
import { useEffect } from 'storybook/preview-api';
import '@ahoo-wang/wow-view-engine/themes.css';
import './preview.css';
import { DocsPage } from './DocsPage.js';
import { ThemedDocsContainer } from './ThemedDocsContainer.js';
import {
  DEFAULT_PRESET,
  MODES,
  PRESETS,
} from '../stories/view-engine/presets.js';

const { initializeThemeState, pluckThemeFromContext } = DecoratorHelpers;

/**
 * The mode, on the addon's own toolbar switch: light, dark, or the reader's
 * system (phase 5, 5D). The stories draw their surfaces the way a host's
 * page does — following a `.dark` class on `<html>` rather than pinning a
 * `theme` — so this is what the switch sets, and `system` sets it from
 * `prefers-color-scheme`, following a change while the story is open, the
 * way a host with no toggle of its own would. The global keeps the addon's
 * name, `theme`, so a story's `globals: { theme: 'dark' }` still pins it.
 */
initializeThemeState([...MODES], 'light');
const withMode: Decorator = (storyFn, context) => {
  const { themeOverride } = (context.parameters.themes ?? {}) as {
    themeOverride?: string;
  };
  const mode = themeOverride || pluckThemeFromContext(context) || 'light';
  useEffect(() => {
    const html = document.documentElement;
    if (mode !== 'system') {
      html.classList.toggle('dark', mode === 'dark');
      return;
    }
    const query = matchMedia('(prefers-color-scheme: dark)');
    const follow = () => html.classList.toggle('dark', query.matches);
    follow();
    query.addEventListener('change', follow);
    return () => query.removeEventListener('change', follow);
  }, [mode]);
  return storyFn();
};

/**
 * The preset, on a toolbar switch of its own (phase 5, 5D): the presets
 * `themes.css` declares, put on `<html>` as `data-fve-preset` — where a host
 * puts it, so every surface and every popup portalled to `<body>` takes it.
 * The default preset is the theme itself, so it is no attribute at all: a
 * story that sets one of its own finds the page as a host with no preset
 * leaves it.
 */
const withPreset: Decorator = (storyFn, context) => {
  const preset = String(context.globals.fvePreset ?? DEFAULT_PRESET);
  useEffect(() => {
    const html = document.documentElement;
    if (preset === DEFAULT_PRESET) html.removeAttribute('data-fve-preset');
    else html.setAttribute('data-fve-preset', preset);
  }, [preset]);
  return storyFn();
};

/**
 * The density, on a switch of its own (themes.md 2.4, batch T4): put on
 * `<html>` as `data-fve-density`, where a host puts it. The preset's own
 * recommendation is what a story shows until one is picked, so
 * `preset` — no attribute at all — is the first item.
 */
const withDensity: Decorator = (storyFn, context) => {
  const density = String(context.globals.fveDensity ?? 'preset');
  useEffect(() => {
    const html = document.documentElement;
    if (density === 'preset') html.removeAttribute('data-fve-density');
    else html.setAttribute('data-fve-density', density);
  }, [density]);
  return storyFn();
};

/**
 * Which way a change is coloured (themes.md 2.6, D35 Q61; batch T5): put on
 * `<html>` as `data-fve-change-colors`, where a host puts it. `semantic` —
 * by whether the change is good — is what no attribute at all means.
 */
const withChangeColors: Decorator = (storyFn, context) => {
  const convention = String(context.globals.fveChangeColors ?? 'semantic');
  // Only a picked convention is written, and taken back when it goes: with
  // the default the attribute is the story's own to set (a story that pins
  // `red-up` in its `beforeEach`).
  useEffect(() => {
    if (convention === 'semantic') return;
    const html = document.documentElement;
    html.setAttribute('data-fve-change-colors', convention);
    return () => html.removeAttribute('data-fve-change-colors');
  }, [convention]);
  return storyFn();
};

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
      container: ThemedDocsContainer,
      codePanel: true,
      // Sections and their subsections; a card's title in the guided intro
      // (`IntroBlocks.tsx`) is a heading of the card, not of the page.
      toc: {
        headingSelector: 'h2, h3',
        ignoreSelector: '.story-intro-card h3',
      },
    },
    options: {
      // The catalog of docs/scenarios.md 5.1: the guided intro first — it is
      // where Storybook opens — then the host's home page, the business
      // scenes, one page per capability, the components' states, and the real
      // services. A name listed here that no story carries is simply skipped,
      // so the list names only what exists.
      storySort: {
        order: [
          'View Engine',
          [
            '导览',
            '首页',
            '业务场景',
            [
              '运营日报',
              '销售复盘',
              '履约与售后',
              '订单工作台',
              '售后工作台',
              '分析工作台',
              '运单宽表',
              '订单事件流',
              '会员详情页',
              '订单详情页',
            ],
            '能力',
            [
              '显示收口',
              '参考与算出的系列',
              '长时间轴',
              '框选与追问',
              '板上的搜索',
              '主题与预设',
              ['主题一览', '逐套预设', '宿主自定义主题'],
            ],
            '组件状态',
            [
              '记录工作台',
              '分析工作台',
              '仪表盘',
              'EmbeddedView',
              'EmbeddedDashboard',
              '筛选编辑器',
            ],
            '真实后端',
            [
              '补偿控制台',
              ['运营概览', '快照控制台', '事件流分析台'],
              '客户',
              ['快照控制台', '事件流分析台'],
              '交易订单',
              ['快照控制台', '事件流分析台'],
              '商品定价',
              ['快照控制台', '事件流分析台'],
            ],
          ],
          'React Hooks',
        ],
      },
    },
  },
  globalTypes: {
    fvePreset: {
      description: 'View Engine preset (data-fve-preset on <html>)',
      toolbar: {
        title: 'Preset',
        icon: 'paintbrush',
        items: PRESETS.map(preset => ({ value: preset, title: preset })),
        dynamicTitle: true,
      },
    },
    fveDensity: {
      description: 'View Engine density (data-fve-density on <html>)',
      toolbar: {
        title: 'Density',
        icon: 'component',
        items: ['preset', 'compact', 'default', 'comfortable'].map(density => ({
          value: density,
          title: density,
        })),
        dynamicTitle: true,
      },
    },
    fveChangeColors: {
      description:
        'View Engine change convention (data-fve-change-colors on <html>)',
      toolbar: {
        title: 'Change colors',
        icon: 'transfer',
        items: ['semantic', 'green-up', 'red-up'].map(convention => ({
          value: convention,
          title: convention,
        })),
        dynamicTitle: true,
      },
    },
  },
  initialGlobals: {
    fvePreset: DEFAULT_PRESET,
    fveDensity: 'preset',
    fveChangeColors: 'semantic',
  },
  // View Engine's dark theme wakes up when `.dark` sits on an ancestor of
  // `.fve-root`, and a preset when `data-fve-preset` does; both go on
  // `<html>`, so the toolbar reaches every story the way a host would.
  decorators: [withMode, withPreset, withDensity, withChangeColors],
  tags: ['autodocs', 'test'],
};

export default preview;
