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

import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  MAX_KANBAN_LANES,
  OTHER_LANE_ID,
  appendLane,
  compileKanbanCounts,
  compileRecord,
  defaultKanbanSpec,
  defaultRecordConfig,
  kanbanLanes,
  laneConfig,
  laneFields,
  projectKanban,
  recordProjection,
  validateDefinition,
  validateRecord,
  type RecordViewConfig,
} from '../src/index.js';
import { deprecatedUses } from '../src/runtime/deprecated.js';
import { NOW, ordersDefinition, recordConfig } from './fixtures.js';
import { dealsDefinition, kanbanConfig } from './fixtures/kanban.js';
import { AggregationFunction, FilterOperator } from '@ahoo-wang/wow-client/dsl';

const kinds = builtinFieldKinds;
const at = { now: NOW, timeZone: 'UTC' };

describe('kanbanLanes', () => {
  it("lays the lanes out in the options' order, the folded ones left out, then「其他」", () => {
    const lanes = kanbanLanes(
      dealsDefinition(),
      kanbanConfig({ kanban: { lane: 'stage', hidden: ['WON'] } }),
    );
    expect(lanes.map(lane => [lane.id, lane.value])).toEqual([
      ['option:"LEAD"', 'LEAD'],
      ['option:"LOST"', 'LOST'],
      [OTHER_LANE_ID, null],
    ]);
  });

  it('has no lanes for a config with no kanban, or a lane field that is gone', () => {
    expect(kanbanLanes(dealsDefinition(), recordConfig())).toEqual([]);
    expect(
      kanbanLanes(dealsDefinition(), kanbanConfig({ kanban: { lane: 'x' } })),
    ).toEqual([]);
  });

  it('tells a numeric option from its text', () => {
    const definition = dealsDefinition({
      fields: [
        { name: 'id', label: 'Deal', kind: 'string', sortable: true },
        {
          name: 'stage',
          label: 'Stage',
          kind: 'enum',
          options: [
            { value: 1, label: 'One' },
            { value: '1', label: 'Text one' },
          ],
        },
      ],
    });
    expect(
      kanbanLanes(definition, kanbanConfig()).map(lane => lane.id),
    ).toEqual(['option:1', 'option:"1"', OTHER_LANE_ID]);
  });
});

describe('laneConfig', () => {
  const definition = dealsDefinition();
  const config = kanbanConfig({
    filter: {
      op: 'and',
      children: [{ field: 'amount', operator: FilterOperator.GT, value: 10 }],
    },
  });

  it("ANDs an option's lane after the view's own conditions", () => {
    const [lead] = kanbanLanes(definition, config);
    expect(laneConfig(definition, config, lead!).filter).toEqual({
      op: 'and',
      children: [
        { field: 'amount', operator: FilterOperator.GT, value: 10 },
        {
          op: 'and',
          children: [
            { field: 'stage', operator: FilterOperator.IN, value: ['LEAD'] },
          ],
        },
      ],
    });
  });

  it('asks「其他」for every value none of the options is, folded ones included', () => {
    const folded = { ...config, kanban: { lane: 'stage', hidden: ['WON'] } };
    const other = kanbanLanes(definition, folded).find(
      lane => lane.id === OTHER_LANE_ID,
    )!;
    const query = compileRecord(
      definition,
      laneConfig(definition, folded, other),
      kinds,
      at,
      { cursor: null },
    );
    expect(JSON.stringify(query.filter)).toContain('"NOT_IN"');
    expect(JSON.stringify(query.filter)).toContain('["LEAD","WON","LOST"]');
  });

  it('leaves the config the user edits as it was', () => {
    const [lead] = kanbanLanes(definition, config);
    laneConfig(definition, config, lead!);
    expect(config.filter.children).toHaveLength(1);
  });
});

describe('compileKanbanCounts', () => {
  it('groups the shown option lanes by the lane field, counting them and summing what the view sums', () => {
    const query = compileKanbanCounts(
      dealsDefinition(),
      kanbanConfig({
        kanban: { lane: 'stage', hidden: ['LOST'] },
        summaries: [{ field: 'amount', fn: AggregationFunction.SUM }],
      }),
      kinds,
      at,
    );
    expect(query?.groupBy).toEqual([
      expect.objectContaining({ field: 'stage', alias: 'kanbanLane' }),
    ]);
    expect(query?.metrics.map(metric => metric.alias)).toEqual([
      'kanbanCount',
      'amount_sum',
    ]);
    expect(query?.limit).toBe(2);
    expect(JSON.stringify(query?.filter)).toContain('["LEAD","WON"]');
  });

  it('asks nothing when every option lane is folded', () => {
    expect(
      compileKanbanCounts(
        dealsDefinition(),
        kanbanConfig({
          kanban: { lane: 'stage', hidden: ['LEAD', 'WON', 'LOST'] },
        }),
        kinds,
        at,
      ),
    ).toBeNull();
  });
});

describe('projectKanban', () => {
  const definition = dealsDefinition();
  const config = kanbanConfig({
    summaries: [{ field: 'amount', fn: AggregationFunction.SUM }],
  });
  const lanes = kanbanLanes(definition, config);
  const pages = [
    {
      lane: lanes[0]!,
      page: { list: [{ id: 'd-1', stage: 'LEAD' }], nextCursor: 'c-1' },
      index: 1,
    },
    { lane: lanes[1]!, page: { list: [], nextCursor: null }, index: 1 },
    { lane: lanes[2]!, page: { list: [], nextCursor: null }, index: 1 },
    {
      lane: lanes[3]!,
      page: { list: [{ id: 'd-9', stage: 'OLD' }], nextCursor: 'c-9' },
      index: 1,
    },
  ];

  it('reads each option lane its count and summaries off the aggregation, an absent group as empty', () => {
    const view = projectKanban(definition, config, pages, [
      { kanbanLane: 'LEAD', kanbanCount: 7, amount_sum: 70 },
    ]);
    const [lead, won, , other] = view.lanes!;
    expect(lead).toMatchObject({
      label: 'Lead',
      total: 7,
      next: { cursor: 'c-1' },
    });
    expect(lead!.summaries?.cells[0]?.value).toBe(70);
    expect(won).toMatchObject({ tone: 'success', total: 0, next: null });
    // A cursor counts nothing: the「其他」lane says what it loaded.
    expect(other).toMatchObject({ value: null, label: '', total: null });
    expect(view.rows.map(row => row.key)).toEqual(['d-1', 'd-9']);
  });

  it('has no counts or summaries when the aggregation did not answer', () => {
    const [lead] = projectKanban(definition, config, pages, null).lanes!;
    expect(lead).toMatchObject({ total: null, summaries: null });
  });

  it("falls back on a paged lane's own total", () => {
    const [lead] = projectKanban(
      definition,
      config,
      [{ ...pages[0]!, page: { list: [{ id: 'd-1' }], total: 41, exact: true } }],
      null,
    ).lanes!;
    expect(lead).toMatchObject({ total: 41, next: { index: 2 } });
  });

  it('draws a record twice in no lane', () => {
    const view = projectKanban(
      definition,
      config,
      [
        pages[0]!,
        { ...pages[3]!, page: { list: [{ id: 'd-1' }], nextCursor: null } },
      ],
      null,
    );
    expect(view.lanes!.map(lane => lane.rows.length)).toEqual([1, 0]);
  });
});

describe('appendLane', () => {
  const definition = dealsDefinition();
  const config = kanbanConfig();
  const lanes = kanbanLanes(definition, config);
  const view = projectKanban(
    definition,
    config,
    [
      {
        lane: lanes[0]!,
        page: { list: [{ id: 'd-1' }], nextCursor: 'c-1' },
        index: 1,
      },
      {
        lane: lanes[1]!,
        page: { list: [{ id: 'd-2' }], nextCursor: null },
        index: 1,
      },
    ],
    null,
  );

  it("puts the lane's next page after it, and moves it on", () => {
    const next = appendLane(
      definition,
      config,
      view,
      lanes[0]!.id,
      { list: [{ id: 'd-3' }, { id: 'd-2' }], nextCursor: null },
      1,
    );
    expect(next.lanes![0]!.rows.map(row => row.key)).toEqual(['d-1', 'd-3']);
    expect(next.lanes![0]!.next).toBeNull();
    expect(next.lanes![1]).toBe(view.lanes![1]);
    expect(next.rows.map(row => row.key)).toEqual(['d-1', 'd-3', 'd-2']);
  });

  it('hands back a view with no such lane as it is', () => {
    expect(
      appendLane(
        definition,
        config,
        view,
        'nope',
        { list: [], nextCursor: null },
        1,
      ),
    ).toBe(view);
  });
});

describe('a kanban config admitted', () => {
  const definition = dealsDefinition();
  const codes = (config: RecordViewConfig) =>
    validateRecord(definition, config, kinds).map(found => found.code);

  it('admits a kanban laid out by an enum', () => {
    expect(codes(kanbanConfig())).toEqual([]);
  });

  it('asks a kanban for its half, and an enum with options to lay lanes out by', () => {
    const { kanban: _none, ...bare } = kanbanConfig();
    void _none;
    expect(codes(bare)).toEqual(['record.kanban.missing']);
    expect(codes(kanbanConfig({ kanban: { lane: 'amount' } }))).toEqual([
      'record.kanban.lane-not-enum',
    ]);
    expect(codes(kanbanConfig({ kanban: { lane: 'gone' } }))).toEqual([
      'record.field.unknown',
    ]);
  });

  it('refuses a half it cannot read, whatever the layout', () => {
    expect(
      codes(
        recordConfig({
          kanban: { lane: 3 } as unknown as RecordViewConfig['kanban'],
        }),
      ),
    ).toEqual(['record.kanban.invalid']);
  });

  it('leaves a stored half alone while the layout is another', () => {
    expect(codes(recordConfig({ kanban: { lane: 'amount' } }))).toEqual([]);
  });

  it(`shows at most ${MAX_KANBAN_LANES} lanes`, () => {
    const many = dealsDefinition({
      fields: [
        { name: 'id', label: 'Deal', kind: 'string', sortable: true },
        {
          name: 'stage',
          label: 'Stage',
          kind: 'enum',
          options: Array.from({ length: MAX_KANBAN_LANES + 2 }, (_, at) => ({
            value: `S${at}`,
            label: `Stage ${at}`,
          })),
        },
      ],
    });
    // Only the deal's id and stage here: the columns and card name no more.
    const bare = {
      table: { columns: [{ field: 'id' }] },
      card: { title: 'id', fields: [] },
    };
    const found = validateRecord(many, kanbanConfig(bare), kinds);
    expect(found).toEqual([
      expect.objectContaining({
        code: 'record.kanban.too-many-lanes',
        params: { count: MAX_KANBAN_LANES + 2, max: MAX_KANBAN_LANES },
      }),
    ]);
    // A new view starts with the first lanes shown and the rest folded.
    expect(defaultKanbanSpec(many)?.hidden).toEqual(['S12', 'S13']);
    expect(
      validateRecord(
        many,
        kanbanConfig({ ...bare, kanban: defaultKanbanSpec(many)! }),
        kinds,
      ),
    ).toEqual([]);
  });
});

describe('a definition that offers a kanban', () => {
  it('needs an enum with options to lay its lanes out by', () => {
    const none = dealsDefinition({
      fields: [{ name: 'id', label: 'Deal', kind: 'string', sortable: true }],
    });
    expect(laneFields(none)).toEqual([]);
    expect(validateDefinition(none, kinds).map(found => found.code)).toContain(
      'definition.record.kanban-no-lane',
    );
    expect(
      validateDefinition(dealsDefinition(), kinds).map(found => found.code),
    ).not.toContain('definition.record.kanban-no-lane');
  });

  it('writes the kanban half into a new view, and only then', () => {
    expect(defaultRecordConfig(dealsDefinition()).kanban).toEqual({
      lane: 'stage',
    });
    expect(defaultRecordConfig(ordersDefinition()).kanban).toBeUndefined();
  });

  it("asks for the cards' fields and their stage, whose lanes hold them", () => {
    const definition = dealsDefinition({
      record: { rowKey: 'id', paging: 'cursor', layouts: ['table', 'kanban'] },
    });
    const config = kanbanConfig({
      table: { columns: [{ field: 'id' }] },
      card: { title: 'id', fields: ['amount'] },
    });
    expect(recordProjection(definition, config).include).toContain('amount');
    // And the stage each card is in, which a move's rule reads off the row.
    expect(recordProjection(definition, config).include).toContain('stage');
  });
});

describe('a kanban with nothing to lay its lanes out by', () => {
  const none = dealsDefinition({
    fields: [{ name: 'id', label: 'Deal', kind: 'string', sortable: true }],
  });

  it('has no default half, no lane question and no counts', () => {
    expect(defaultKanbanSpec(none)).toBeUndefined();
    const config = kanbanConfig();
    const lane = { id: 'option:"LEAD"', value: 'LEAD' };
    expect(laneConfig(none, config, lane)).toBe(config);
    expect(compileKanbanCounts(none, config, kinds, at)).toBeNull();
    expect(
      compileKanbanCounts(dealsDefinition(), recordConfig(), kinds, at),
    ).toBeNull();
  });

  it('projects no lanes as an empty board', () => {
    const view = projectKanban(dealsDefinition(), kanbanConfig(), [], null);
    expect(view).toMatchObject({ rows: [], lanes: [] });
    expect(view.paging).toMatchObject({ mode: 'paged', hasNext: false });
  });
});

describe('a deprecated lane field', () => {
  it('is named among what the kanban asks, and only under the kanban', () => {
    const fields = dealsDefinition().fields.map(field =>
      field.name === 'stage'
        ? { ...field, deprecated: { message: 'Use pipelineStage' } }
        : field,
    );
    const codes = (config: RecordViewConfig) =>
      deprecatedUses(fields, config).map(found => found.params?.field);
    expect(codes(kanbanConfig())).toContain('Stage');
    expect(codes(kanbanConfig({ layout: 'table' }))).not.toContain('Stage');
  });
});
