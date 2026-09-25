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

/* --------------------------------------------------------------------------
 * 业务场景的定义与视图，在浏览器之外先过一遍（docs/scenarios.md 6.2 第 3 批）：
 * 每个定义都被引擎接纳，每张视图都打得开、没有待修复的问题，它的查询由零售
 * 数据源答得出来。数字是否对，由各场景的孪生故事按黄金值断言；这里只守「视图
 * 与定义、数据源三者对得上」，一处字段名写错就在这里红，而不是等人打开目录。
 * ------------------------------------------------------------------------ */

import { describe, expect, it } from 'vitest';
import type {
  AnyViewRuntime,
  DataViewDefinition,
  ViewInstance,
} from '@ahoo-wang/wow-view-engine';
import { CHART_VIEWS } from './chartViews.js';
import { createRetailEngine } from './source.js';
import {
  AFTER_SALE_WORKBENCH_VIEWS,
  ANALYSIS_VIEWS,
  MEMBER_ANALYSIS_VIEWS,
  ORDER_WORKBENCH_VIEWS,
  retailAfterSalesDefinition,
  retailMembersDefinition,
  retailOrderAnalysisDefinition,
  retailOrderEventsDefinition,
  retailOrdersDefinition,
  retailWaybillsDefinition,
} from './views.js';

const SCENES: {
  name: string;
  definition: DataViewDefinition;
  views: ViewInstance[];
}[] = [
  {
    name: '订单工作台',
    definition: retailOrdersDefinition,
    views: ORDER_WORKBENCH_VIEWS,
  },
  {
    name: '分析工作台 · 订单',
    definition: retailOrderAnalysisDefinition,
    views: ANALYSIS_VIEWS,
  },
  {
    name: '分析工作台 · 会员',
    definition: retailMembersDefinition,
    views: MEMBER_ANALYSIS_VIEWS,
  },
  {
    name: '图型陈列',
    definition: retailOrderAnalysisDefinition,
    views: CHART_VIEWS,
  },
  {
    name: '售后工作台',
    definition: retailAfterSalesDefinition,
    views: AFTER_SALE_WORKBENCH_VIEWS,
  },
  { name: '运单宽表', definition: retailWaybillsDefinition, views: [] },
  { name: '订单事件流', definition: retailOrderEventsDefinition, views: [] },
];

/** 等一张视图跑完：成功或失败，不再是加载中。 */
async function settled(runtime: AnyViewRuntime, id: string) {
  const until = Date.now() + 5_000;
  while (Date.now() < until) {
    const state = runtime.getSnapshot();
    if (state.query.status === 'success' || state.query.status === 'error')
      return state;
    await new Promise(resolve => setTimeout(resolve, 5));
  }
  throw new Error(
    `${id} never settled: ${JSON.stringify(runtime.getSnapshot().query)}`,
  );
}

describe.each(SCENES)('$name', ({ definition, views }) => {
  it('is admitted by the engine', () => {
    const engine = createRetailEngine([definition], views);
    expect(engine.definitionIssues(definition.id)).toEqual([]);
    engine.dispose();
  });

  it('opens every view without an issue, and the retail source answers it', async () => {
    const engine = createRetailEngine([definition], views);
    const listing = await engine.list(definition.id);
    const ids = listing.items.map(item => item.id);
    expect(ids.length).toBe((definition.views ?? []).length + views.length);
    for (const id of ids) {
      const runtime = await engine.open(id);
      // An issue blocks the run, so it is read before waiting for one.
      expect({ id, issues: runtime.getSnapshot().issues }).toEqual({
        id,
        issues: [],
      });
      if (runtime.getSnapshot().query.status === 'idle') runtime.apply();
      const state = await settled(runtime, id);
      expect({ id, error: state.query.error }).toEqual({
        id,
        error: undefined,
      });
      engine.close(runtime);
    }
    engine.dispose();
  }, 60_000);
});
