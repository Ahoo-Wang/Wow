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

import type { Decorator } from '@storybook/react-vite';
import { AppShell, type ScenePage } from '../../shared/AppShell.js';

/** What every retail scene answers from, said in the host's service line. */
export const RETAIL_FIXTURE =
  '栖木生活 · 零售示例数据（钉在 2026-09-22 10:00）';

/**
 * The host application around a retail scene: its bar and navigation, and
 * the fixture it talks to. A workbench fills the page area, as it would a
 * screen.
 */
export function retailShell(current: ScenePage): Decorator {
  return Story => (
    <AppShell current={current} service={{ fixture: RETAIL_FIXTURE }}>
      <Story />
    </AppShell>
  );
}

/** The docs page's opening lines, shared by every retail scene. */
export const RETAIL_DATA_NOTE =
  '**数据**：栖木生活（虚构的家居生活品牌）2024-09-01 到 2026-09-22 的零售交易，约 2 万张子订单、1700 张售后单、6700 个会员、1.9 万个包裹，由种子生成、在浏览器里当场算（`retail/generate.ts`）；「现在」钉在 2026-09-22 10:00（Asia/Shanghai），「本月」「昨日」每次都一样。数据源把引擎发出的 Wow 查询翻译给 mingo 作答，不预聚合。';
