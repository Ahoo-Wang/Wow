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
 * 拆到阈值以内后，连同那一条一起删掉。
 */
const maxLinesWaivers = [
  // 骨架、预算、别名、元素域、指标、having、排序七套规则一个文件 —— todo.md「R8」
  { file: 'src/analysis/validate.ts', lines: 701 },
  // 抽走 writeLedger 之后仍是注册表 + 打开/创建 + 偏好缓存 —— todo.md「R9」
  { file: 'src/runtime/viewEngine.ts', lines: 635 },
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
    // test/、dev/、examples/ 不在 tsconfig 项目内，保持非类型检查规则。
    files: [
      'test/**/*.{ts,tsx}',
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
        // allowDefaultProject 供 reactLint.test.ts 以 lintText 虚拟检查 src/LintProbe.tsx。
        projectService: {
          allowDefaultProject: ['src/LintProbe.tsx'],
        },
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
    },
  },
  {
    // 绊线：拆开的文件不许长回去。vendored 的 src/ui/components、src/ui/lib 已由顶部
    // ignores 排除；src/ui/messages/ 是纯文案目录，一份译文天然就长，不该被行数管。
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/ui/messages/**'],
    rules: {
      'max-lines': ['error', { max: 500, ...countCode }],
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
);
