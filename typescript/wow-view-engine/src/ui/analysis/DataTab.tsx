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

import { ArrowDownIcon, ArrowUpIcon, PlusIcon, XIcon } from 'lucide-react';
import {
  moved,
  placed,
  stageValues,
  withStageOrder,
  without,
} from '../../analysis/index.js';
import {
  CHART_FAMILY,
  type CartesianSeries,
  type CartesianSpec,
  type FunnelSpec,
  type MetricCardSpec,
} from '../../model/index.js';
import { Button } from '../components/button.js';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import { IconButton } from '../IconButton.js';
import { useViewMessages } from '../MessagesProvider.js';
import { DropdownMenuContent } from '../popups.js';
import { EditorCard } from '../variants.js';
import { CompactSelect } from './CompactSelect.js';
import {
  ChoiceField,
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
  const update = (next: CartesianSpec) =>
    onChange({ ...chart, cartesian: next });
  const drawn = new Set(spec.series.map(series => series.metric));
  const undrawn = shape.metrics.filter(metric => !drawn.has(metric.value));
  const nameOf = (alias: string) =>
    shape.metrics.find(metric => metric.value === alias)?.label ?? alias;
  const patch = (index: number, change: Partial<CartesianSeries>) =>
    update({
      ...spec,
      series: spec.series.map((series, at) =>
        at === index ? { ...series, ...change } : series,
      ),
    });
  return (
    <>
      <SlotSelect
        label={messages.label('label.chart.slot.x')}
        items={shape.groups}
        value={spec.x}
        onChange={alias => update(placed(spec, 'x', 'splitBy', alias))}
      />
      {spec.splitBy !== undefined && (
        <SlotSelect
          label={messages.label('label.chart.slot.split')}
          items={shape.groups}
          value={spec.splitBy}
          onChange={alias => update(placed(spec, 'splitBy', 'x', alias))}
        />
      )}
      <OptionsSection
        name="series"
        title={messages.label('label.chart.slot.series')}
      >
        {spec.series.map((series, index) => {
          const name = nameOf(series.metric);
          return (
            <EditorCard
              key={series.metric}
              data-slot="series-card"
              data-metric={series.metric}
            >
              <span className="truncate font-medium">{name}</span>
              {chart.type === 'combo' && (
                <CompactSelect
                  label={messages.label('label.chart.mark-of', { name })}
                  items={(['bar', 'line', 'area'] as const).map(mark => ({
                    value: mark,
                    label: messages.label(`label.chart.mark.${mark}`),
                  }))}
                  value={series.type ?? 'bar'}
                  onChange={type => patch(index, { type })}
                />
              )}
              <CompactSelect
                label={messages.label('label.chart.axis-of', { name })}
                items={(['left', 'right'] as const).map(axis => ({
                  value: axis,
                  label: messages.label(`label.chart.axis.${axis}`),
                }))}
                value={series.axis ?? 'left'}
                onChange={axis => patch(index, { axis })}
              />
              <IconButton
                label={messages.label('label.chart.remove-series', { name })}
                variant="ghost"
                size="icon-xs"
                className="ml-auto"
                disabled={spec.series.length <= 1}
                onClick={() =>
                  update({
                    ...spec,
                    series: spec.series.filter((_series, at) => at !== index),
                  })
                }
              >
                <XIcon />
              </IconButton>
            </EditorCard>
          );
        })}
        {/* A split chart draws one metric: the pivot is its series. */}
        {spec.splitBy === undefined && (
          <DropdownMenu>
            <DropdownMenuTrigger
              render={
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={undrawn.length === 0}
                  className="self-start"
                />
              }
            >
              <PlusIcon data-icon="inline-start" />
              {messages.label('label.chart.add-series')}
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start">
              <DropdownMenuGroup>
                {undrawn.map(metric => (
                  <DropdownMenuItem
                    key={metric.value}
                    onClick={() =>
                      update({
                        ...spec,
                        series: [
                          ...spec.series,
                          chart.type === 'combo'
                            ? { metric: metric.value, type: 'bar' }
                            : { metric: metric.value },
                        ],
                      })
                    }
                  >
                    {metric.label}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuGroup>
            </DropdownMenuContent>
          </DropdownMenu>
        )}
      </OptionsSection>
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
        items={shape.metrics}
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
          onChange({ ...chart, heatmap: placed(spec, 'y', 'x', alias) })
        }
      />
      <SlotSelect
        label={messages.label('label.chart.slot.columns')}
        items={shape.groups}
        value={spec.x}
        onChange={alias =>
          onChange({ ...chart, heatmap: placed(spec, 'x', 'y', alias) })
        }
      />
      <SlotSelect
        label={messages.label('label.chart.slot.value')}
        items={shape.metrics}
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
        items={shape.metrics}
        value={spec.x}
        onChange={alias =>
          onChange({ ...chart, scatter: placed(spec, 'x', 'y', alias) })
        }
      />
      <SlotSelect
        label={messages.label('label.chart.slot.y-metric')}
        items={shape.metrics}
        value={spec.y}
        onChange={alias =>
          onChange({ ...chart, scatter: placed(spec, 'y', 'x', alias) })
        }
      />
      <OptionalSlotSelect
        label={messages.label('label.chart.slot.size')}
        none={messages.label('label.chart.slot.none')}
        items={shape.metrics}
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
        }))}
        onMove={(index, step) =>
          update({
            ...spec,
            stages: { from: 'metrics', items: moved(items, index, step) },
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
      <SlotSelect
        label={messages.label('label.chart.slot.value')}
        items={shape.metrics}
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
          update(withStageOrder(spec, moved(order, index, step)))
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

function StageList({
  title,
  stages,
  onMove,
  onRemove,
}: {
  title: string;
  stages: { key: string; name: string }[];
  onMove(index: number, step: -1 | 1): void;
  onRemove?(index: number): void;
}) {
  const messages = useViewMessages();
  return (
    <OptionsSection name="stages" title={title}>
      <ol className="flex flex-col gap-2">
        {stages.map((stage, index) => (
          <li key={stage.key}>
            <EditorCard data-slot="stage-card" data-stage={stage.key}>
              <span className="truncate font-medium">{stage.name}</span>
              <IconButton
                label={messages.label('label.chart.move-up', {
                  name: stage.name,
                })}
                variant="ghost"
                size="icon-xs"
                className="ml-auto"
                disabled={index === 0}
                onClick={() => onMove(index, -1)}
              >
                <ArrowUpIcon />
              </IconButton>
              <IconButton
                label={messages.label('label.chart.move-down', {
                  name: stage.name,
                })}
                variant="ghost"
                size="icon-xs"
                disabled={index === stages.length - 1}
                onClick={() => onMove(index, 1)}
              >
                <ArrowDownIcon />
              </IconButton>
              {onRemove && (
                <IconButton
                  label={messages.label('label.chart.remove-stage', {
                    name: stage.name,
                  })}
                  variant="ghost"
                  size="icon-xs"
                  disabled={stages.length <= 2}
                  onClick={() => onRemove(index)}
                >
                  <XIcon />
                </IconButton>
              )}
            </EditorCard>
          </li>
        ))}
      </ol>
    </OptionsSection>
  );
}

function MetricData({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.metric;
  if (!spec) return null;
  const update = (next: MetricCardSpec) => onChange({ ...chart, metric: next });
  const others: Choice[] = shape.metrics.filter(
    metric => metric.value !== spec.metric,
  );
  return (
    <>
      <SlotSelect
        label={messages.label('label.chart.slot.value')}
        items={shape.metrics}
        value={spec.metric}
        onChange={metric =>
          // The comparison names another metric; the headline moving onto
          // it leaves nothing to compare with.
          update(
            spec.compare?.metric === metric
              ? { ...without(spec, 'compare'), metric }
              : { ...spec, metric },
          )
        }
      />
      <OptionalSlotSelect
        label={messages.label('label.chart.column.compare')}
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
