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

import type { datasetMessages } from '../datasets.js';

/**
 * `datasetMessages` 的简体中文，逐键对应（`../datasets.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const datasetsZhCN = {
  // 独立的数据集视图（D93）：选数据集，以及还没有数据集时说什么。
  'label.dataset.picker': '数据集',
  'label.dataset.none': '还没有数据集',
  'label.dataset.none-hint': '目录提供数据集之后，它就出现在这里。',
  'label.dataset.loading': '正在读取数据集…',
  'label.dataset.refresh': '重新读取数据集',
  'dataset.read.failed': '读不到可用的数据集：{reason}',
} satisfies Record<keyof typeof datasetMessages, string>;
