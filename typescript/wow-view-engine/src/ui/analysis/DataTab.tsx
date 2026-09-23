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

import { ArrowDownIcon, ArrowUpIcon, XIcon } from 'lucide-react';
import {
  withMoved,
  withSlot,
  stageValues,
  withStageOrder,
} from '../../analysis/index.js';
import {
  CHART_FAMILY,
  type FunnelSpec,
  type MetricCardSpec,
} from '../../model/index.js';
import { without } from '../../model/index.js';
import { IconButton } from '../IconButton.js';
import { useViewMessages } from '../MessagesProvider.js';
import { EditorCard } from '../variants.js';
import { useListFocus } from './listFocus.js';
import { SeriesList } from './SeriesList.js';
import {
  ChoiceField,
  NameField,
  OptionalSlotSelect,
  OptionsSection,
  SlotSelect,
  type Choice,
  type OptionsPageProps,
} from './optionControls.js';

/**
 * The data page (D20 屏 J): the chart's slots, each filled from the one
 * list that fits it — a position slot lists the dimensions, a measure slot
 * the metrics — so a slot cannot be filled with the wrong kind of column.
 * Entering, every slot is already filled by `fitChartSlots`; the analyst
 * changes the one that is not to their liking.
 */
export function DataTab(props: OptionsPageProps) {
  switch (CHART_FAMILY[props.chart.type]) {
    case 'cartesian':
      return <CartesianData {...props} />;
    case 'pie':
      return <PieData {...props} />;
    case 'heatmap':
      return <HeatmapData {...props} />;
    case 'scatter':
      return <ScatterData {...props} />;
    case 'funnel':
      return <FunnelData {...props} />;
    case 'metric':
      return <MetricData {...props} />;
    default:
      return null;
  }
}

function CartesianData({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.cartesian;
  if (!spec) return null;
  const update = (next: typeof spec) => onChange({ ...chart, cartesian: next });
  return (
    <>
      <SlotSelect
        label={messages.label('label.chart.slot.x')}
        items={shape.groups}
        value={spec.x}
        onChange={alias => update(withSlot(spec, 'x', 'splitBy', alias))}
      />
      {spec.splitBy !== undefined && (
        <SlotSelect
          label={messages.label('label.chart.slot.split')}
          items={shape.groups}
          value={spec.splitBy}
          onChange={alias => update(withSlot(spec, 'splitBy', 'x', alias))}
        />
      )}
      <SeriesList
        type={chart.type}
        spec={spec}
        metrics={shape.quantities}
        onChange={update}
      />
    </>
  );
}

function PieData({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.pie;
  if (!spec) return null;
  return (
    <>
      <SlotSelect
        label={messages.label('label.chart.slot.category')}
        items={shape.groups}
        value={spec.category}
        onChange={category =>
          onChange({ ...chart, pie: { ...spec, category } })
        }
      />
      <SlotSelect
        label={messages.label('label.chart.slot.value')}
        items={shape.quantities}
        value={spec.value}
        onChange={value => onChange({ ...chart, pie: { ...spec, value } })}
      />
    </>
  );
}

function HeatmapData({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.heatmap;
  if (!spec) return null;
  return (
    <>
      <SlotSelect
        label={messages.label('label.chart.slot.rows')}
        items={shape.groups}
        value={spec.y}
        onChange={alias =>
          onChange({ ...chart, heatmap: withSlot(spec, 'y', 'x', alias) })
        }
      />
      <SlotSelect
        label={messages.label('label.chart.slot.columns')}
        items={shape.groups}
        value={spec.x}
        onChange={alias =>
          onChange({ ...chart, heatmap: withSlot(spec, 'x', 'y', alias) })
        }
      />
      <SlotSelect
        label={messages.label('label.chart.slot.value')}
        items={shape.quantities}
        value={spec.value}
        onChange={value => onChange({ ...chart, heatmap: { ...spec, value } })}
      />
    </>
  );
}

function ScatterData({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.scatter;
  if (!spec) return null;
  return (
    <>
      <SlotSelect
        label={messages.label('label.chart.slot.point')}
        items={shape.groups}
        value={spec.category}
        onChange={category =>
          onChange({ ...chart, scatter: { ...spec, category } })
        }
      />
      <SlotSelect
        label={messages.label('label.chart.slot.x-metric')}
        items={shape.quantities}
        value={spec.x}
        onChange={alias =>
          onChange({ ...chart, scatter: withSlot(spec, 'x', 'y', alias) })
        }
      />
      <SlotSelect
        label={messages.label('label.chart.slot.y-metric')}
        items={shape.quantities}
        value={spec.y}
        onChange={alias =>
          onChange({ ...chart, scatter: withSlot(spec, 'y', 'x', alias) })
        }
      />
      <OptionalSlotSelect
        label={messages.label('label.chart.slot.size')}
        none={messages.label('label.chart.slot.none')}
        items={shape.quantities}
        value={spec.size}
        onChange={size =>
          onChange({
            ...chart,
            scatter:
              size === undefined ? without(spec, 'size') : { ...spec, size },
          })
        }
      />
    </>
  );
}

/**
 * A funnel's stages are either the metrics, one each, or the values of one
 * dimension; both are a list in business order, which nothing but the
 * analyst knows, so both lists can be reordered by hand.
 */
function FunnelData({ chart, shape, rows, label, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.funnel;
  if (!spec) return null;
  const update = (next: FunnelSpec) => onChange({ ...chart, funnel: next });
  if (spec.stages.from === 'metrics') {
    const items = spec.stages.items;
    return (
      <StageList
        title={messages.label('label.chart.slot.stages')}
        stages={items.map(item => ({
          key: item.metric,
          name:
            shape.metrics.find(metric => metric.value === item.metric)?.label ??
            item.metric,
          given: item.label,
        }))}
        onMove={(index, step) =>
          update({
            ...spec,
            stages: { from: 'metrics', items: withMoved(items, index, step) },
          })
        }
        /**
         * What the stage is called on the drawing. A metric's column title
         * names what was measured — 「金额 的 合计」 — and a funnel's stage
         * names a step of a business — 「下单」 — which is rarely the same
         * sentence and is knowledge only the analyst has. An emptied box
         * takes the name back rather than storing a blank, so the drawing
         * falls to the column title again.
         */
        onName={(index, given) =>
          update({
            ...spec,
            stages: {
              from: 'metrics',
              items: items.map((item, at) =>
                at !== index
                  ? item
                  : given === undefined
                    ? without(item, 'label')
                    : { ...item, label: given },
              ),
            },
          })
        }
      />
    );
  }
  const stages = spec.stages;
  // The order in force, followed by any value the result has that the
  // order does not name yet — a group the analyst has not placed.
  const order = [
    ...stages.order,
    ...stageValues(rows, stages.category).filter(
      value => !stages.order.includes(value),
    ),
  ];
  return (
    <>
      <SlotSelect
        label={messages.label('label.chart.slot.stage-of')}
        items={shape.groups}
        value={stages.category}
        onChange={category =>
          update(
            withStageOrder({ ...spec, stages: { ...stages, category } }, []),
          )
        }
      />
      {/* A stage counts what entered and remained: only a metric that adds
          up is one (`chart.funnel.not-additive`). */}
      <SlotSelect
        label={messages.label('label.chart.slot.value')}
        items={shape.quantities.filter(metric =>
          shape.additive.has(metric.value),
        )}
        value={stages.value}
        onChange={value => update({ ...spec, stages: { ...stages, value } })}
      />
      <StageList
        title={messages.label('label.chart.slot.stage-order')}
        stages={order.map(value => ({
          key: value,
          name: label(stages.category, value),
        }))}
        onMove={(index, step) =>
          update(withStageOrder(spec, withMoved(order, index, step)))
        }
        onRemove={index =>
          update(
            withStageOrder(
              spec,
              order.filter((_value, at) => at !== index),
            ),
          )
        }
      />
    </>
  );
}

/**
 * The stages in the order they are drawn in.
 *
 * A stage that comes from a metric also carries a name of its own
 * ({@link onName}), written in a box that is always there rather than
 * behind a menu the way a tray card's name is (`CardMenu.tsx` `CardName`):
 * this panel is a page of boxes and selects — an axis title, a reference
 * line's caption — and a stage's name is one more of them, while a tray
 * card's name is its *heading* and the card has no room left for a box. A
 * fourth icon per stage, opening a menu of one item, would cost a tab stop
 * per stage to say what the box says by standing there.
 */
function StageList({
  title,
  stages,
  onMove,
  onName,
  onRemove,
}: {
  title: string;
  /** `name` is what the stage is called by default; `given`, what was typed. */
  stages: { key: string; name: string; given?: string }[];
  onMove(index: number, step: -1 | 1): void;
  /** Names a stage, or takes the name back on an emptied box. */
  onName?(index: number, given: string | undefined): void;
  onRemove?(index: number): void;
}) {
  const messages = useViewMessages();
  // A stage that goes leaves the keyboard on the stage that took its place;
  // a stage that moves keeps it on the button that moved it, at the index
  // the stage landed on — including the boundary, where that button is now
  // disabled and the other way round takes the focus (`listFocus.ts`).
  const focus = useListFocus({
    list: '[data-slot="chart-options-stages"]',
    item: '[data-slot="stage-card"]',
  });
  return (
    <OptionsSection name="stages" title={title}>
      <ol className="flex flex-col gap-2">
        {stages.map((stage, index) => {
          // The buttons name the stage as it reads on screen: once it has
          // been given a name, that is the stage as far as the analyst is
          // concerned.
          const name = stage.given ?? stage.name;
          return (
            <li key={stage.key}>
              <EditorCard data-slot="stage-card" data-stage={stage.key}>
                {onName ? (
                  <NameField
                    label={messages.label('label.chart.stage-name', {
                      name: stage.name,
                    })}
                    placeholder={stage.name}
                    value={stage.given}
                    onChange={given => onName(index, given)}
                  />
                ) : (
                  // Cut off at the card's width; `title` is how a pointer
                  // reads the rest of it.
                  <span
                    data-slot="stage-name"
                    className="truncate font-medium"
                    title={name}
                  >
                    {name}
                  </span>
                )}
                <IconButton
                  label={messages.label('label.chart.move-up', { name })}
                  variant="ghost"
                  size="icon-xs"
                  className="ml-auto"
                  data-move="up"
                  disabled={index === 0}
                  onClick={event => {
                    focus.moved(event, index - 1, 'up');
                    onMove(index, -1);
                  }}
                >
                  <ArrowUpIcon />
                </IconButton>
                <IconButton
                  label={messages.label('label.chart.move-down', { name })}
                  variant="ghost"
                  size="icon-xs"
                  data-move="down"
                  disabled={index === stages.length - 1}
                  onClick={event => {
                    focus.moved(event, index + 1, 'down');
                    onMove(index, 1);
                  }}
                >
                  <ArrowDownIcon />
                </IconButton>
                {onRemove && (
                  <IconButton
                    label={messages.label('label.chart.remove-stage', { name })}
                    variant="ghost"
                    size="icon-xs"
                    disabled={stages.length <= 2}
                    onClick={event => {
                      focus.removing(event, index);
                      onRemove(index);
                    }}
                  >
                    <XIcon />
                  </IconButton>
                )}
              </EditorCard>
            </li>
          );
        })}
      </ol>
    </OptionsSection>
  );
}

function MetricData({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.metric;
  if (!spec) return null;
  const update = (next: MetricCardSpec) => onChange({ ...chart, metric: next });
  const others: Choice[] = shape.quantities.filter(
    metric => metric.value !== spec.metric,
  );
  // A headline that is a moment is written out as it is: nothing is
  // compared with it (`chart.metric.moment`), so the comparison is not asked.
  const moment = shape.moments.has(spec.metric);
  return (
    <>
      <SlotSelect
        label={messages.label('label.chart.slot.value')}
        items={shape.metrics}
        value={spec.metric}
        onChange={metric =>
          // The comparison names another metric; the headline moving onto
          // it leaves nothing to compare with. Moving onto a moment leaves
          // nothing to compare, aim at or format.
          update(
            shape.moments.has(metric)
              ? {
                  ...without(
                    without(without(spec, 'compare'), 'target'),
                    'format',
                  ),
                  metric,
                }
              : spec.compare?.metric === metric
                ? { ...without(spec, 'compare'), metric }
                : { ...spec, metric },
          )
        }
      />
      {!moment && (
        <OptionalSlotSelect
          label={messages.label('label.chart.compare-with')}
          none={messages.label('label.chart.slot.none')}
          items={others}
          value={spec.compare?.metric}
          onChange={metric =>
            update(
              metric === undefined
                ? without(spec, 'compare')
                : {
                    ...spec,
                    compare: { metric, mode: spec.compare?.mode ?? 'delta' },
                  },
            )
          }
        />
      )}
      {spec.compare && (
        <ChoiceField
          label={messages.label('label.chart.compare-mode')}
          items={(['delta', 'percent'] as const).map(mode => ({
            value: mode,
            label: messages.label(`label.chart.compare.${mode}`),
          }))}
          value={spec.compare.mode}
          onChange={mode =>
            update({ ...spec, compare: { ...spec.compare!, mode } })
          }
        />
      )}
    </>
  );
}
