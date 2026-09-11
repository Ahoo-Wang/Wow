/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { useId } from 'react';
import {
  ChartBarIcon,
  ChartLineIcon,
  ChartAreaIcon,
  CheckIcon,
  ChartPieIcon,
  PanelsTopLeftIcon,
} from 'lucide-react';
import { ANALYSIS_VISUALIZATIONS } from './analysisVisualizations.js';
import type {
  inferCapabilities,
  VisualizationType,
} from './analysisDisplaySelection.js';
import { cn } from '../lib/utils.js';

const icons = {
  metric: PanelsTopLeftIcon,
  bar: ChartBarIcon,
  line: ChartLineIcon,
  area: ChartAreaIcon,
  pie: ChartPieIcon,
};

/** Cards render capability facts; selection is the only emitted operation. */
export function AnalysisVisualizationPicker({
  value,
  capabilities,
  disabled,
  onChange,
}: {
  value: string;
  capabilities?: ReturnType<typeof inferCapabilities>;
  disabled?: boolean;
  onChange(type: VisualizationType): void;
}) {
  const id = useId();
  return (
    <fieldset
      disabled={disabled}
      className="fve:m-0 fve:min-w-0 fve:border-0 fve:p-0"
    >
      <legend className="fve:mb-3 fve:text-sm fve:font-medium">
        选择展示方式
      </legend>
      <div className="fve:grid fve:grid-cols-2 fve:gap-2">
        {ANALYSIS_VISUALIZATIONS.filter(item => item.value !== 'table').map(
          chart => {
            const capability = capabilities?.find(
              item => item.type === chart.value,
            );
            const unavailable =
              !capability || capability.status === 'unavailable';
            const Icon = icons[chart.value];
            const reason =
              capability?.summaries.join('；') ||
              (!capability ? '请先运行查询' : '');
            return (
              <label
                key={chart.value}
                className={cn(
                  'fve:flex fve:min-w-0 fve:flex-col fve:gap-1.5 fve:rounded-lg fve:border fve:p-3 fve:text-sm fve:focus-within:ring-2 fve:focus-within:ring-ring',
                  value === chart.value && 'fve:border-primary fve:bg-accent',
                  unavailable
                    ? 'fve:cursor-not-allowed fve:bg-muted/30'
                    : 'fve:cursor-pointer fve:hover:bg-accent',
                )}
              >
                <input
                  type="radio"
                  name={id}
                  value={chart.value}
                  checked={value === chart.value}
                  disabled={unavailable}
                  aria-label={chart.label}
                  aria-describedby={`${id}-${chart.value}`}
                  onChange={() => onChange(chart.value)}
                  className="fve:sr-only"
                />
                <span className="fve:flex fve:items-center fve:gap-1.5">
                  <Icon
                    aria-hidden="true"
                    className="fve:size-4 fve:shrink-0"
                  />
                  <span className="fve:flex-1 fve:font-medium">
                    {chart.label}
                  </span>
                  {value === chart.value && (
                    <CheckIcon aria-hidden="true" className="fve:size-4" />
                  )}
                </span>
                <span
                  id={`${id}-${chart.value}`}
                  className="fve:text-xs fve:text-foreground"
                >
                  {unavailable
                    ? `不可用：${reason}`
                    : capability.status === 'recommended'
                      ? '推荐'
                      : '可用'}
                </span>
              </label>
            );
          },
        )}
      </div>
      {capabilities?.some(item => item.status === 'unavailable') && (
        <details className="fve:mt-3 fve:text-xs">
          <summary className="fve:cursor-pointer">不可用原因</summary>
          <dl className="fve:mt-2 fve:space-y-2">
            {capabilities
              .filter(item => item.status === 'unavailable')
              .map(item => (
                <div key={item.type}>
                  <dt className="fve:font-medium">{item.label}</dt>
                  <dd className="fve:m-0">{item.reasons.join('；')}</dd>
                </div>
              ))}
          </dl>
        </details>
      )}
    </fieldset>
  );
}
