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
import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  actions,
  type DataViewDefinition,
  type RecordActions,
  type RecordData,
  type RecordRow,
  type ViewInstance,
} from '@ahoo-wang/wow-view-engine';
import { memorySource } from '@ahoo-wang/wow-view-engine/testing';
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import { HOST_LANGUAGE, recordConfig } from './fixtures.js';
import { rowSource } from './rowSource.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';
import { AggregationFunction, SortDirection } from '@ahoo-wang/wow-client/dsl';

/**
 * Opportunities moving through their stages, and requirements through
 * theirs: the two scenes a kanban is for (D83). Both definitions page by
 * cursor, as a kanban is best declared, and lay their lanes out by an enum
 * whose options are the stages.
 */
const opportunities: DataViewDefinition = {
  id: 'opportunities',
  title: '商机',
  recordNoun: '商机',
  kind: 'data',
  source: 'opportunities',
  fields: [
    { name: 'id', label: '商机编号', kind: 'string', sortable: true },
    { name: 'name', label: '商机名称', kind: 'string' },
    { name: 'customer', label: '客户', kind: 'string' },
    {
      name: 'stage',
      label: '阶段',
      kind: 'enum',
      options: [
        { value: 'LEAD', label: '线索' },
        { value: 'QUALIFIED', label: '已确认需求' },
        { value: 'PROPOSAL', label: '方案报价', tone: 'warning' },
        { value: 'NEGOTIATION', label: '商务谈判', tone: 'warning' },
        { value: 'WON', label: '赢单', tone: 'success' },
        { value: 'LOST', label: '输单', tone: 'danger' },
      ],
    },
    {
      name: 'amount',
      label: '预计金额',
      kind: 'number',
      summary: [AggregationFunction.SUM],
      sortable: true,
      numberFormat: { style: 'currency', currency: 'CNY' },
    },
    { name: 'owner', label: '负责人', kind: 'string' },
  ],
  record: {
    rowKey: 'id',
    paging: 'cursor',
    layouts: ['kanban', 'table', 'card'],
  },
  views: [],
};

const OPPORTUNITIES: RecordData[] = [
  ['OP-101', '华东区 ERP 升级', '江南制造', 'LEAD', 380000, '王敏'],
  ['OP-102', '门店收银替换', '鲜丰连锁', 'LEAD', 120000, '李想'],
  ['OP-103', '仓储系统扩容', '顺达物流', 'LEAD', 260000, '王敏'],
  ['OP-104', '会员中台', '鲜丰连锁', 'LEAD', 540000, '赵磊'],
  ['OP-105', '财务共享中心', '江南制造', 'LEAD', 900000, '李想'],
  ['OP-106', '质检数据平台', '恒信精密', 'QUALIFIED', 310000, '赵磊'],
  ['OP-107', '经销商门户', '海川饮品', 'QUALIFIED', 180000, '王敏'],
  ['OP-108', '供应链协同', '顺达物流', 'PROPOSAL', 720000, '李想'],
  ['OP-109', '智能排产', '恒信精密', 'NEGOTIATION', 1250000, '赵磊'],
  ['OP-110', '电子发票对接', '海川饮品', 'WON', 86000, '王敏'],
  ['OP-111', '移动巡店', '鲜丰连锁', 'WON', 150000, '李想'],
  ['OP-112', '旧系统运维外包', '江南制造', 'LOST', 200000, '赵磊'],
].map(([id, name, customer, stage, amount, owner]) => ({
  id,
  name,
  customer,
  stage,
  amount,
  owner,
}));

/** The stages an opportunity is advanced through, in order. */
const PIPELINE = ['LEAD', 'QUALIFIED', 'PROPOSAL', 'NEGOTIATION', 'WON'];

/**
 * One opportunity's commands, offered on its card and in its detail: move
 * it on a stage, or mark it lost. Each writes the story's documents, so the
 * refresh after it shows the card in its new lane.
 */
function opportunityActions(documents: RecordData[]): RecordActions {
  const stageOf = (row: RecordRow) => String(row.data.stage ?? '');
  const write = (row: RecordRow, stage: string) => {
    const found = documents.find(document => document.id === row.key);
    if (found) found.stage = stage;
  };
  const stages = opportunities.fields.find(
    field => field.name === 'stage',
  )!.options!;
  const closed = (stage: string) => stage === 'WON' || stage === 'LOST';
  return actions([
    {
      // Sets the stage: a card dragged to another lane runs it, and its
      // menu offers the same move as a choice of stage.
      id: 'move',
      label: '换阶段',
      moves: 'stage',
      on: ['row', 'detail'],
      form: { stage: { label: '阶段', options: stages } },
      available: (row, { input }) => {
        if (input?.stage === stageOf(row)) return '已经在这一阶段';
        if (closed(stageOf(row))) return '已结束的商机不再换阶段';
        return true;
      },
      run: async (row, input) => write(row, String(input.stage)),
    },
    {
      id: 'advance',
      label: '推进阶段',
      primary: true,
      on: ['row', 'detail'],
      available: row => {
        const at = PIPELINE.indexOf(stageOf(row));
        return at >= 0 && at < PIPELINE.length - 1 ? true : '这一阶段已经结束';
      },
      run: async row => {
        write(row, PIPELINE[PIPELINE.indexOf(stageOf(row)) + 1]!);
      },
    },
    {
      id: 'lose',
      label: '标记输单',
      tone: 'danger',
      on: ['row', 'detail'],
      available: row =>
        stageOf(row) === 'WON' || stageOf(row) === 'LOST'
          ? '这一阶段已经结束'
          : true,
      confirm: { title: '把这个商机标记为输单？' },
      run: async row => write(row, 'LOST'),
    },
  ]);
}

const pipeline: ViewInstance = {
  id: 'opportunity-pipeline',
  definitionId: opportunities.id,
  title: '销售漏斗',
  scope: 'personal',
  revision: '1',
  config: recordConfig({
    layout: 'kanban',
    pageSize: 3,
    sort: [{ field: 'amount', direction: SortDirection.DESC }],
    summaries: [{ field: 'amount', fn: AggregationFunction.SUM }],
    table: {
      columns: [
        { field: 'id', pinned: true },
        { field: 'name' },
        { field: 'customer' },
        { field: 'stage' },
        { field: 'amount' },
        { field: 'owner' },
      ],
    },
    card: { title: 'name', fields: ['customer', 'amount', 'owner'] },
    kanban: { lane: 'stage' },
  }),
};

const requirements: DataViewDefinition = {
  id: 'requirements',
  title: '需求',
  recordNoun: '需求',
  kind: 'data',
  source: 'requirements',
  fields: [
    { name: 'id', label: '需求编号', kind: 'string', sortable: true },
    { name: 'title', label: '标题', kind: 'string' },
    {
      name: 'state',
      label: '处理阶段',
      kind: 'enum',
      options: [
        { value: 'NEW', label: '待评估' },
        { value: 'ACCEPTED', label: '已排期' },
        { value: 'DEVELOPING', label: '开发中', tone: 'warning' },
        { value: 'TESTING', label: '测试中', tone: 'warning' },
        { value: 'RELEASED', label: '已上线', tone: 'success' },
        { value: 'REJECTED', label: '已拒绝', tone: 'danger' },
      ],
    },
    {
      name: 'priority',
      label: '优先级',
      kind: 'enum',
      options: [
        { value: 'P0', label: '紧急', tone: 'danger' },
        { value: 'P1', label: '高', tone: 'warning' },
        { value: 'P2', label: '普通' },
      ],
    },
    { name: 'assignee', label: '处理人', kind: 'string' },
  ],
  record: {
    rowKey: 'id',
    paging: 'cursor',
    layouts: ['kanban', 'table'],
  },
  views: [],
};

const REQUIREMENTS: RecordData[] = [
  ['RQ-1', '导出支持 Excel', 'NEW', 'P2', '周宁'],
  ['RQ-2', '订单列表按仓库筛选', 'NEW', 'P1', '周宁'],
  ['RQ-3', '审批流支持加签', 'ACCEPTED', 'P1', '陈晨'],
  ['RQ-4', '移动端扫码入库', 'DEVELOPING', 'P0', '陈晨'],
  ['RQ-5', '库存预警短信', 'DEVELOPING', 'P2', '孙悦'],
  ['RQ-6', '报表定时推送', 'TESTING', 'P1', '孙悦'],
  ['RQ-7', '单点登录接入', 'RELEASED', 'P0', '周宁'],
  ['RQ-8', '旧版界面皮肤', 'REJECTED', 'P2', '陈晨'],
  // Written before the stages were settled: in no lane the options list.
  ['RQ-9', '数据迁移脚本', 'ARCHIVED', 'P2', '孙悦'],
].map(([id, title, state, priority, assignee]) => ({
  id,
  title,
  state,
  priority,
  assignee,
}));

const board: ViewInstance = {
  id: 'requirement-board',
  definitionId: requirements.id,
  title: '需求处理',
  scope: 'personal',
  revision: '1',
  config: recordConfig({
    layout: 'kanban',
    pageSize: 5,
    summaries: [],
    table: {
      columns: [
        { field: 'id', pinned: true },
        { field: 'title' },
        { field: 'state' },
        { field: 'priority' },
        { field: 'assignee' },
      ],
    },
    card: { title: 'title', fields: ['priority', 'assignee'] },
    kanban: { lane: 'state' },
  }),
};

type Scene = 'opportunities' | 'requirements';

function KanbanDemo({ scene }: { scene: Scene }) {
  // The story's own copy, which its actions write: read on every query
  // (no remembered answers), so a card moves the moment its stage does.
  const [documents] = useState(() =>
    OPPORTUNITIES.map(document => ({ ...document })),
  );
  const [declared] = useState(() => opportunityActions(documents));
  return (
    <StoryEngine
      key={scene}
      create={() =>
        new ViewEngine({
          resources: [
            { definition: opportunities, source: memorySource(documents) },
            { definition: requirements, source: rowSource(REQUIREMENTS) },
          ],
          store: new MemoryViewStore({ instances: [pipeline, board] }),
          limits: DEFAULT_RUNTIME_LIMITS,
        })
      }
    >
      {engine =>
        scene === 'opportunities' ? (
          <DataWorkbench
            engine={engine}
            definitionId={opportunities.id}
            instanceId={pipeline.id}
            record={{ actions: declared }}
            {...HOST_LANGUAGE}
          />
        ) : (
          <DataWorkbench
            engine={engine}
            definitionId={requirements.id}
            instanceId={board.id}
            {...HOST_LANGUAGE}
          />
        )
      }
    </StoryEngine>
  );
}

const FIXTURE =
  '内存 ViewStore · 商机 12 条（六个阶段）· 需求 9 条（六个处理阶段，一条在已撤销的阶段）';

const description = `**布局 · 看板视图**（D83）

记录视图的第三种布局：按一个枚举字段的选项分栏，一栏一个阶段，栏里是卡片布局的那张卡。

- **一栏一条查询**：每栏是视图的条件加上这一栏的阶段，按游标取第一页；栏底「加载更多」只取这一栏的下一页。
- **栏头**：阶段的徽章（语气照定义）与这一阶段的条数，条数与汇总（这里是预计金额的合计）由一条按阶段分组的聚合取齐。
- **「其他」**：值不在选项里的记录（需求里那条已撤销阶段的），只在有卡片时才画，条数写已载入的张数。
- **拖动换栏**：把卡片拖到另一栏就换了阶段——宿主声明一个设置分栏字段的操作（\`moves: 'stage'\`），拖到哪一栏就以那一栏的值执行它；不能去的栏变淡并写出原因（已结束的商机不再换阶段）。卡片先落在新的一栏，执行后刷新；失败或取消就回到原处。键盘与读屏走卡片「⋯」里的「换阶段」，是同一个操作。
- **单条记录的操作**：宿主声明的操作照样在每张卡的卡脚（主操作是按钮，其余在「⋯」里）与详情里；商机这里是「推进阶段」与「标记输单」，执行后刷新，卡片到了新的一栏。看板视图首版不勾选，所以没有批量操作。
- **设置**：工具栏的「看板视图设置」选分栏字段、收起展开各栏（最多 12 栏），下面是卡片的标题、正文与图片。

- **数据源**：${FIXTURE}。`;

const meta = {
  title: 'View Engine/布局/看板视图',
  component: KanbanDemo,
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [
    Story => (
      <AppShell service={{ fixture: FIXTURE }}>
        <Story />
      </AppShell>
    ),
  ],
  args: { scene: 'opportunities' },
  argTypes: { scene: { table: { disable: true } } },
} satisfies Meta<typeof KanbanDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Opportunities by stage: counts and expected amounts per lane, three cards a time. */
export const KanbanByStage: Story = {
  name: '商机阶段',
  args: { scene: 'opportunities' },
};

/** Requirements by state, one in a state the options no longer list. */
export const RequirementStates: Story = {
  name: '需求处理阶段',
  args: { scene: 'requirements' },
};
