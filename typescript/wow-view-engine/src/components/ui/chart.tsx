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

// Adapted from shadcn/ui base-nova Chart (MIT); see THIRD_PARTY_NOTICES.md.
import * as React from 'react';
import { cn } from '../../lib/utils.js';
import * as RechartsPrimitive from 'recharts';
import type { TooltipValueType, TooltipPayloadEntry } from 'recharts';

const INITIAL_DIMENSION = { width: 320, height: 200 } as const;
type TooltipNameType = number | string;

export type ChartConfig = Record<
  string,
  {
    label?: React.ReactNode;
    icon?: React.ComponentType;
    color?: string;
  }
>;

type ChartContextProps = {
  config: ChartConfig;
};

const ChartContext = React.createContext<ChartContextProps | null>(null);

function useChart() {
  const context = React.useContext(ChartContext);

  if (!context) {
    throw new Error('useChart must be used within a <ChartContainer />');
  }

  return context;
}

function ChartContainer({
  id,
  className,
  children,
  config,
  style,
  initialDimension = INITIAL_DIMENSION,
  ...props
}: React.ComponentProps<'div'> & {
  config: ChartConfig;
  children: React.ComponentProps<
    typeof RechartsPrimitive.ResponsiveContainer
  >['children'];
  initialDimension?: {
    width: number;
    height: number;
  };
}) {
  const uniqueId = React.useId();
  const chartId = `chart-${id ?? uniqueId.replace(/:/g, '')}`;

  return (
    <ChartContext.Provider value={{ config }}>
      <div
        data-slot="chart"
        data-chart={chartId}
        className={cn(
          "fve:flex fve:aspect-video fve:justify-center fve:text-xs fve:[&_.recharts-cartesian-axis-tick-value]:fill-muted-foreground fve:[&_.recharts-cartesian-grid_line[stroke='#ccc']]:stroke-border/50 fve:[&_.recharts-curve.recharts-tooltip-cursor]:stroke-border fve:[&_.recharts-dot[stroke='#fff']]:stroke-transparent fve:[&_.recharts-layer]:outline-hidden fve:[&_.recharts-polar-grid_[stroke='#ccc']]:stroke-border fve:[&_.recharts-radial-bar-background-sector]:fill-muted fve:[&_.recharts-rectangle.recharts-tooltip-cursor]:fill-muted fve:[&_.recharts-reference-line_[stroke='#ccc']]:stroke-border fve:[&_.recharts-sector]:outline-hidden fve:[&_.recharts-sector[stroke='#fff']]:stroke-transparent fve:[&_.recharts-surface:focus-visible]:outline-2 fve:[&_.recharts-surface:focus-visible]:outline-ring",
          className,
        )}
        {...props}
        style={{
          ...Object.fromEntries(
            Object.entries(config)
              .filter(([, item]) => item.color)
              .map(([key, item]) => [`--color-${key}`, item.color]),
          ),
          ...style,
        }}
      >
        <RechartsPrimitive.ResponsiveContainer
          initialDimension={initialDimension}
        >
          {children}
        </RechartsPrimitive.ResponsiveContainer>
      </div>
    </ChartContext.Provider>
  );
}

const ChartTooltip = RechartsPrimitive.Tooltip;

function ChartTooltipContent({
  active,
  payload,
  className,
  indicator = 'dot',
  hideLabel = false,
  hideIndicator = false,
  label,
  labelFormatter,
  labelClassName,
  formatter,
  color,
  nameKey,
  labelKey,
}: React.ComponentProps<typeof RechartsPrimitive.Tooltip> &
  React.ComponentProps<'div'> & {
    hideLabel?: boolean;
    hideIndicator?: boolean;
    indicator?: 'line' | 'dot' | 'dashed';
    nameKey?: string;
    labelKey?: string;
  } & Omit<
    RechartsPrimitive.DefaultTooltipContentProps<
      TooltipValueType,
      TooltipNameType
    >,
    'accessibilityLayer'
  >) {
  const { config } = useChart();

  const tooltipLabel = React.useMemo(() => {
    if (hideLabel || !payload?.length) {
      return null;
    }

    const [item] = payload;
    const key = String(labelKey ?? item?.dataKey ?? item?.name ?? 'value');
    const itemConfig = getPayloadConfigFromPayload(config, item, key);
    const value =
      !labelKey && typeof label === 'string'
        ? (config[label]?.label ?? label)
        : itemConfig?.label;

    if (labelFormatter) {
      return (
        <div className={cn('fve:font-medium', labelClassName)}>
          {labelFormatter(value, payload)}
        </div>
      );
    }

    if (!value) {
      return null;
    }

    return <div className={cn('fve:font-medium', labelClassName)}>{value}</div>;
  }, [
    label,
    labelFormatter,
    payload,
    hideLabel,
    labelClassName,
    config,
    labelKey,
  ]);

  if (!active || !payload?.length) {
    return null;
  }

  const nestLabel = payload.length === 1 && indicator !== 'dot';

  return (
    <div
      className={cn(
        'fve:grid fve:min-w-32 fve:items-start fve:gap-1.5 fve:rounded-lg fve:border fve:border-border/50 fve:bg-background fve:px-2.5 fve:py-1.5 fve:text-xs fve:shadow-xl',
        className,
      )}
    >
      {!nestLabel ? tooltipLabel : null}
      <div className="fve:grid fve:gap-1.5">
        {payload
          .filter(item => item.type !== 'none')
          .map((item, index) => {
            const key = String(nameKey ?? item.name ?? item.dataKey ?? 'value');
            const itemConfig = getPayloadConfigFromPayload(config, item, key);
            const indicatorColor = color ?? item.payload?.fill ?? item.color;

            return (
              <div
                key={index}
                className={cn(
                  'fve:flex fve:w-full fve:flex-wrap fve:items-stretch fve:gap-2 fve:[&>svg]:h-2.5 fve:[&>svg]:w-2.5 fve:[&>svg]:text-muted-foreground',
                  indicator === 'dot' && 'fve:items-center',
                )}
              >
                {formatter && item?.value !== undefined && item.name ? (
                  formatter(
                    item.value,
                    item.name,
                    item,
                    index,
                    item.payload as TooltipPayloadEntry[],
                  )
                ) : (
                  <>
                    {itemConfig?.icon ? (
                      <itemConfig.icon />
                    ) : (
                      !hideIndicator && (
                        <div
                          className={cn(
                            'fve:shrink-0 fve:rounded-[2px] fve:border-(--color-border) fve:bg-(--color-bg)',
                            {
                              'fve:h-2.5 fve:w-2.5': indicator === 'dot',
                              'fve:w-1': indicator === 'line',
                              'fve:w-0 fve:border-[1.5px] fve:border-dashed fve:bg-transparent':
                                indicator === 'dashed',
                              'fve:my-0.5': nestLabel && indicator === 'dashed',
                            },
                          )}
                          style={
                            {
                              '--color-bg': indicatorColor,
                              '--color-border': indicatorColor,
                            } as React.CSSProperties
                          }
                        />
                      )
                    )}
                    <div
                      className={cn(
                        'fve:flex fve:flex-1 fve:justify-between fve:leading-none',
                        nestLabel ? 'fve:items-end' : 'fve:items-center',
                      )}
                    >
                      <div className="fve:grid fve:gap-1.5">
                        {nestLabel ? tooltipLabel : null}
                        <span className="fve:text-muted-foreground">
                          {itemConfig?.label ?? item.name}
                        </span>
                      </div>
                      {item.value != null && (
                        <span className="fve:font-mono fve:font-medium fve:text-foreground fve:tabular-nums">
                          {typeof item.value === 'number'
                            ? item.value.toLocaleString()
                            : String(item.value)}
                        </span>
                      )}
                    </div>
                  </>
                )}
              </div>
            );
          })}
      </div>
    </div>
  );
}

const ChartLegend = RechartsPrimitive.Legend;

function ChartLegendContent({
  className,
  hideIcon = false,
  payload,
  verticalAlign = 'bottom',
  nameKey,
}: React.ComponentProps<'div'> & {
  hideIcon?: boolean;
  nameKey?: string;
} & RechartsPrimitive.DefaultLegendContentProps) {
  const { config } = useChart();

  if (!payload?.length) {
    return null;
  }

  return (
    <div
      className={cn(
        'fve:flex fve:items-center fve:justify-center fve:gap-4',
        verticalAlign === 'top' ? 'fve:pb-3' : 'fve:pt-3',
        className,
      )}
    >
      {payload
        .filter(item => item.type !== 'none')
        .map((item, index) => {
          const key = String(nameKey ?? item.dataKey ?? 'value');
          const itemConfig = getPayloadConfigFromPayload(config, item, key);

          return (
            <div
              key={index}
              className={cn(
                'fve:flex fve:items-center fve:gap-1.5 fve:[&>svg]:h-3 fve:[&>svg]:w-3 fve:[&>svg]:text-muted-foreground',
              )}
            >
              {itemConfig?.icon && !hideIcon ? (
                <itemConfig.icon />
              ) : (
                <div
                  className="fve:h-2 fve:w-2 fve:shrink-0 fve:rounded-[2px]"
                  style={{
                    backgroundColor: item.color,
                  }}
                />
              )}
              {itemConfig?.label}
            </div>
          );
        })}
    </div>
  );
}

function getPayloadConfigFromPayload(
  config: ChartConfig,
  payload: unknown,
  key: string,
) {
  if (typeof payload !== 'object' || payload === null) {
    return undefined;
  }

  const payloadPayload =
    'payload' in payload &&
    typeof payload.payload === 'object' &&
    payload.payload !== null
      ? payload.payload
      : undefined;

  let configLabelKey: string = key;

  if (
    key in payload &&
    typeof payload[key as keyof typeof payload] === 'string'
  ) {
    configLabelKey = payload[key as keyof typeof payload];
  } else if (
    payloadPayload &&
    key in payloadPayload &&
    typeof payloadPayload[key as keyof typeof payloadPayload] === 'string'
  ) {
    configLabelKey = payloadPayload[key as keyof typeof payloadPayload];
  }

  return configLabelKey in config ? config[configLabelKey] : config[key];
}

export {
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  ChartLegend,
  ChartLegendContent,
};
