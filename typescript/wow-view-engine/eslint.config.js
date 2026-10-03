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

import { fileURLToPath } from 'node:url';
import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';

/** Shared by package checks and root Storybook checks, using stable official React/Compiler rules. */
export const reactLintConfig = {
  plugins: { 'react-hooks': reactHooks },
  linterOptions: { reportUnusedDisableDirectives: 'error' },
  rules: {
    ...reactHooks.configs.recommended.rules,
    'react-hooks/exhaustive-deps': 'error',
    'react-hooks/incompatible-library': 'error',
    'react-hooks/unsupported-syntax': 'error',
  },
};

/** 只数代码行：跳过空行与注释，绊线量的是代码，不是本包偏长的 why-注释。 */
const countCode = { skipBlankLines: true, skipComments: true };

/**
 * 豁免上限 = 实测代码行 × 1.1，向上取到十位。
 *
 * 不钉死在实测值：改个 bug 多两行不该把 CI 打红。也不给更多：10% 只够维护，
 * 攒不回一个新主题，而且拆完一轮要重新实测、重新收紧。
 */
const withMargin = lines => Math.ceil((lines * 1.1) / 10) * 10;

/**
 * 绊线管辖内、当下就超阈值的文件，`lines` 是本次实测的代码行数。
 * 每条都在 `docs/design/todo.md` 的「`max-lines` 存量豁免」里有对应的拆分项——
 * 拆到阈值以内后，连同那一条一起删掉。R8 与 R9 拆完之后这里空着：
 * 管辖内的文件全部在 500 代码行以内。
 */
const maxLinesWaivers = [];

/**
 * 函数长度绊线（R2-94 b）：src 里一个函数（React 组件也是函数）至多 200 代码行，
 * 与 `max-lines` 同样只数代码行。
 *
 * 2026-10-03 实测 src（vendored 的 ui/components、ui/lib 除外）5,664 个函数：
 * p50 8、p90 36、p95 59、p99 155、p99.5 185、最长 371。阈值取 p99 之上的整数 200：
 * 取 150 会有 62 个函数进豁免名单，多是一屏 JSX 的组件，那是重写而不是绊线；
 * 200 只拦新长出来的离群者。棘轮：豁免拆短一个就删一条，阈值只降不升。
 */
const MAX_FUNCTION_LINES = 200;

/**
 * 当下超过 200 行、又拆不自然的函数，每文件一条，`lines` 是本次实测的代码行数，
 * 上限照 `withMargin` 给。能顺手拆的已经拆了（R4：提出纯函数、子组件、
 * 子 hook）；这里留下的，拆开只是把一处的状态当 props 传来传去。
 */
const functionLengthWaivers = [
  {
    file: 'src/ui/workbench/WorkbenchShell.tsx',
    lines: 371,
    reason: '外壳只排布各块，留下的是各块共享的状态与它们的次序',
  },
  {
    file: 'src/ui/analysis/AnalysisTable.tsx',
    lines: 369,
    reason:
      '虚拟行、留住的列宽、表头排序与汇总行共用一份测量，拆开要逐层传下去',
  },
  {
    file: 'src/react/useRecordTable.ts',
    lines: 325,
    reason: '控制器是一个对象字面量，成员都闭包着同一份运行时快照与选择锚点',
  },
  {
    file: 'src/ui/filter/ConditionPill.tsx',
    lines: 320,
    reason: '药丸、块头与只读三种画法共用字段、运算符与弹层的同一份状态',
  },
  {
    file: 'src/ui/charts/EChart.tsx',
    lines: 314,
    reason: '库实例的创建、尺寸、主题与事件绑定是同一个元素的一条生命周期',
  },
  {
    file: 'src/react/useWorkbench.ts',
    lines: 301,
    reason: '打开、持有、交接与离开守卫是一串有先后的 hook，次序就是语义',
  },
  {
    file: 'src/ui/record/RecordTable.tsx',
    lines: 291,
    reason: '固定列、展开行、汇总与选择共用同一张表的测量与 ref',
  },
  {
    file: 'src/ui/dashboard/DashboardGrid.tsx',
    lines: 289,
    reason: '网格放置、过滤器接线与面板整体共用同一份看板与搭建状态',
  },
  {
    file: 'src/ui/analysis/MetricCard.tsx',
    lines: 281,
    reason: '字段、汇总方式、百分位、公式与条件各控件改写同一个指标',
  },
  {
    file: 'src/react/useAnalysisEditor.ts',
    lines: 278,
    reason: '控制器是一个对象字面量，成员都读同一份草稿、作用域与 reshape',
  },
  {
    file: 'src/ui/dashboard/ContentEditor.tsx',
    lines: 274,
    reason: '一张表单：标题、正文、图片说明与链接共用同一份草稿与校验',
  },
  {
    file: 'src/ui/dashboard/Board.tsx',
    lines: 264,
    reason: '编辑栏、空板引导与两个对话框共用看板的搭建状态与焦点去向',
  },
  {
    file: 'src/ui/dashboard/NewAnalysisDialog.tsx',
    lines: 263,
    reason: '一张表单：数据集、标题与就地编辑的分析共用同一个持有的运行时',
  },
  {
    file: 'src/ui/workbench/RecordParts.tsx',
    lines: 246,
    reason: '把记录视图的各部件一次交给 children，部件共用同一个控制器',
  },
  {
    file: 'src/ui/manage/ViewManagerRow.tsx',
    lines: 242,
    reason: '一行的就地改名让其余按钮让位，所有按钮共用这份编辑状态',
  },
  {
    file: 'src/ui/charts/Cartesian.tsx',
    lines: 238,
    reason: '计划、尺寸拟合、动效与图例开关共用同一份计划与测量',
  },
  {
    file: 'src/react/analysisEditing.ts',
    lines: 231,
    reason:
      '返回类型是公开的 QuestionEditing，拆开会改它的成员次序（test/api）',
  },
  {
    file: 'src/ui/workbench/AnalysisParts.tsx',
    lines: 217,
    reason: '把分析视图的各部件一次交给 children，部件共用同一个控制器',
  },
  {
    file: 'src/ui/dashboard/PanelMenu.tsx',
    lines: 216,
    reason: '菜单项按同一组可做与否的判断逐项出现，判断与菜单在一处',
  },
];

export default tseslint.config(
  {
    // src/ui/components 与 src/ui/lib 是 shadcn 注册表源码，由 `shadcn add --diff`
    // 升级；本地改写会让此后每次升级都变成整文件冲突。
    ignores: [
      'dist/**',
      'node_modules/**',
      'coverage/**',
      'src/ui/components/**',
      'src/ui/lib/**',
    ],
  },
  {
    // test/、dev/、examples/、theme-check/ 不在 tsconfig 项目内，保持非类型检查规则。
    files: [
      'test/**/*.{ts,tsx}',
      'theme-check/**/*.ts',
      'dev/**/*.{ts,tsx}',
      'examples/**/*.{ts,tsx}',
    ],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    ...reactLintConfig,
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
      parserOptions: {
        tsconfigRootDir: fileURLToPath(new URL('.', import.meta.url)),
      },
    },
    rules: {
      ...reactLintConfig.rules,
      '@typescript-eslint/consistent-type-imports': 'error',
    },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      ...tseslint.configs.recommendedTypeChecked,
    ],
    ...reactLintConfig,
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
      parserOptions: {
        projectService: true,
        tsconfigRootDir: fileURLToPath(new URL('.', import.meta.url)),
      },
    },
    rules: {
      ...reactLintConfig.rules,
      '@typescript-eslint/consistent-type-imports': 'error',
      '@typescript-eslint/no-unnecessary-type-assertion': 'error',
      '@typescript-eslint/no-floating-promises': 'error',
      // 以下三条发现项均远超 20 处（108/55/41），且 src 大量按 React/store 惯用法把方法作为值传递，
      // 行为中性修复需 .bind/箭头包装（改变函数身份，影响 memo/依赖数组），按护栏任务纪律整体降级关闭。
      '@typescript-eslint/unbound-method': 'off',
      '@typescript-eslint/no-unsafe-member-access': 'off',
      '@typescript-eslint/no-unsafe-assignment': 'off',
      // A library does not write to its host's console; report through errors,
      // state or callbacks instead. Tests, dev/ and examples/ may log.
      'no-console': 'error',
    },
  },
  {
    // 绊线：拆开的文件不许长回去。vendored 的 src/ui/components、src/ui/lib 已由顶部
    // ignores 排除；src/ui/messages/ 是纯文案目录，一份译文天然就长，不该被行数管。
    // 三个入口是逐名写出的公开名单（D64），长度就是公开面，由 test/surface/ 逐名守着。
    files: ['src/**/*.{ts,tsx}'],
    ignores: [
      'src/ui/messages/**',
      'src/index.ts',
      'src/react/index.ts',
      'src/ui/index.ts',
    ],
    rules: {
      'max-lines': ['error', { max: 500, ...countCode }],
    },
  },
  {
    // 函数长度绊线只管 src；测试与 story 不在其列。vendored 目录已由顶部 ignores 排除。
    files: ['src/**/*.{ts,tsx}'],
    rules: {
      'max-lines-per-function': [
        'error',
        { max: MAX_FUNCTION_LINES, ...countCode },
      ],
    },
  },
  {
    // 测试按主题分文件，上限放宽到 1200：够写一个主题，不够再攒回四千行。
    files: ['test/**/*.{ts,tsx}'],
    rules: {
      'max-lines': ['error', { max: 1200, ...countCode }],
    },
  },
  ...maxLinesWaivers.map(({ file, lines }) => ({
    files: [file],
    rules: {
      'max-lines': ['error', { max: withMargin(lines), ...countCode }],
    },
  })),
  ...functionLengthWaivers.map(({ file, lines }) => ({
    files: [file],
    rules: {
      'max-lines-per-function': [
        'error',
        { max: withMargin(lines), ...countCode },
      ],
    },
  })),
);
