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
import { RecordWorkbench } from '@ahoo-wang/fetcher-view-engine/ui';
import { ScenarioFrame } from '../shared/ScenarioFrame.js';
import {
  createStoryEngine,
  recordConfig,
  savedViews,
  type SourceBehaviour,
} from './fixtures.js';
import { StoryEngine, viewEngineScene } from './StoryEngine.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

/**
 * The Record workbench in one state at a time. Every state below follows from
 * what the backend does or from what the saved config says, so a story sets
 * one of those two and changes nothing else.
 */
function RecordWorkbenchDemo({
  behaviour = 'data',
  instanceId,
  broken = false,
}: {
  behaviour?: SourceBehaviour;
  instanceId?: string;
  /** Saves a config the definition no longer accepts, to show "needs fixing". */
  broken?: boolean;
}) {
  return (
    <StoryEngine
      create={() =>
        createStoryEngine({
          behaviour,
          instances: broken
            ? [
                {
                  ...savedViews[0],
                  title: '待修复视图',
                  config: recordConfig({
                    table: { columns: [{ field: 'removedColumn' }] },
                  }),
                },
              ]
            : savedViews,
        })
      }
    >
      {engine => (
        <RecordWorkbench
          engine={engine}
          definitionId="orders"
          instanceId={instanceId ?? savedViews[0].id}
        />
      )}
    </StoryEngine>
  );
}

const scene = {
  ...viewEngineScene,
  domain: '数据视图',
  summary: '明细、汇总、筛选与保存，全部来自一份配置。',
  fixture: '内存 ViewStore · 三条订单 · 可切换的数据源行为',
  setup: '每次挂载都新建引擎与存储，场景之间不共享已保存的视图。',
  observe: '表格、汇总行与提示反映这一次执行的口径，而不是草稿。',
};

const meta = {
  decorators: [
    (Story, context) => (
      <ScenarioFrame title={context.name} {...scene}>
        <Story />
      </ScenarioFrame>
    ),
  ],
  title: 'View Engine/数据视图/Record 工作台',
  component: RecordWorkbenchDemo,
  args: { behaviour: 'data' },
  argTypes: {
    behaviour: {
      control: 'inline-radio',
      options: ['data', 'empty', 'slow', 'failing'],
    },
    broken: { table: { disable: true } },
    instanceId: { table: { disable: true } },
  },
} satisfies Meta<typeof RecordWorkbenchDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Rows, the summary row and the saved conditions of a shared view. */
export const WithData: Story = { args: { behaviour: 'data' } };

/** A query that succeeded and matched nothing, which is not an error. */
export const EmptyResult: Story = { args: { behaviour: 'empty' } };

/** What the first execution looks like before the answer arrives. */
export const Loading: Story = { args: { behaviour: 'slow' } };

/** A failed query keeps the view and its conditions; only the data is gone. */
export const QueryFailed: Story = { args: { behaviour: 'failing' } };

/** A saved config the definition outgrew: `apply` is refused until it is fixed. */
export const NeedsFixing: Story = { args: { broken: true } };

/** No saved view under this id, reported instead of an empty frame. */
export const CannotOpen: Story = { args: { instanceId: 'deleted' } };
