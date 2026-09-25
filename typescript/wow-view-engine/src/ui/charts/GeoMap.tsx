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

import { useCallback, useEffect, useMemo, useState } from 'react';
import type { MapData } from '../../analysis/index.js';
import { pointAnchor } from '../analysis/DrillMenu.js';
import { useViewMessages } from '../MessagesProvider.js';
import { chartNotes } from './ChartNotes.js';
import { EChart, type ChartClick } from './EChart.js';
import type { FamilyProps } from './family.js';
import { drawnRegions, mapOption } from './mapOption.js';
import { loadChartMap, useChartMaps, type LoadedChartMap } from './maps.js';
import { useChartMotion } from './motion.js';
import type { ChartTheme } from './theme.js';

/** Where the map a chart names stands: on its way, drawn, or not to be had. */
type MapState =
  | { status: 'loading' }
  | { status: 'ready'; map: LoadedChartMap }
  | { status: 'missing' }
  | { status: 'failed' };

/**
 * The map a spec names — the first the host registered when it names none
 * — loaded the first time a chart draws it (`loadChartMap`).
 */
function useChartMap(named: string | undefined): {
  name: string | undefined;
  state: MapState;
} {
  const maps = useChartMaps();
  const name = named ?? maps[0]?.name;
  const known = name !== undefined && maps.some(map => map.name === name);
  // Keyed by the map it is of: a spec naming another map is loading that
  // one until its answer arrives, without an effect saying so first.
  const [settled, setSettled] = useState<{
    name: string;
    state: MapState;
  }>();
  useEffect(() => {
    if (!known || name === undefined) return;
    let live = true;
    loadChartMap(name).then(
      map => live && setSettled({ name, state: { status: 'ready', map } }),
      () => live && setSettled({ name, state: { status: 'failed' } }),
    );
    return () => {
      live = false;
    };
  }, [name, known]);
  const state: MapState =
    settled && settled.name === name ? settled.state : { status: 'loading' };
  return { name, state: known ? state : { status: 'missing' } };
}

/**
 * A map, drawn by ECharts from `mapOption` over the geography the host
 * registered (D41). Until it has arrived the frame stands empty; when the
 * host registered none, or not the one the spec names, or it failed to
 * load, that is said over the frame. A region the map has no area for is
 * counted and said, never guessed at. A pressed region is its group.
 */
export function GeoMap({
  data,
  spec,
  className,
  label,
  column,
  name,
  onPick,
  highlight,
}: FamilyProps<MapData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const map = useChartMap(spec?.map?.map);
  const ready = map.state.status === 'ready' ? map.state.map : undefined;
  const pickable = onPick !== undefined;
  const drawn = useMemo(
    () => drawnRegions(data, { spec, label }, ready?.regions),
    [data, spec, label, ready],
  );
  const option = useCallback(
    (theme: ChartTheme) =>
      ready
        ? mapOption(
            data,
            ready.name,
            { spec, label, column, animate, pickable, highlight },
            theme,
          )
        : {},
    [data, ready, spec, label, column, animate, pickable, highlight],
  );
  const onClick = useMemo(
    () =>
      onPick &&
      ((click: ChartClick & { name?: string }) => {
        if (click.componentType !== 'series') return;
        const region = drawn.find(entry => entry.name === click.name);
        if (!region) return;
        onPick(
          region.row,
          pointAnchor(click.event?.event ?? { clientX: 0, clientY: 0 }),
        );
      }),
    [onPick, drawn],
  );
  const unplaced = ready ? drawn.filter(region => !region.placed).length : 0;
  const notes = [
    ...(map.state.status === 'missing'
      ? [messages.label('label.chart.map.missing')]
      : map.state.status === 'failed'
        ? [messages.label('label.chart.map.failed')]
        : []),
    ...(unplaced > 0
      ? [messages.label('label.chart.map.unplaced', { count: unplaced })]
      : []),
    ...(data.omitted > 0
      ? [messages.label('label.chart.map.omitted', { count: data.omitted })]
      : []),
  ];
  return (
    <EChart
      name={name}
      className={className}
      option={option}
      onClick={onClick}
      chunk="geo"
      legend={chartNotes(notes, 'map-notes')}
      data={{
        'data-chart': 'map',
        'data-map': map.state.status,
        'data-marks': ready ? drawn.filter(region => region.placed).length : 0,
      }}
    />
  );
}
