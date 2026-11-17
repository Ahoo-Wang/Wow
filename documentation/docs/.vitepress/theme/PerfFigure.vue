<!--
  - Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
  - Licensed under the Apache License, Version 2.0 (the "License");
  - you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
  -->

<!-- A performance figure, `<PerfFigure name="m5max-sweep" />`: perf-figures.mjs draws it from the reports' headline
     files when the site is built, in the page's language. -->
<script setup lang="ts">
import {computed} from 'vue'
import {useData} from 'vitepress'
import {figureHtml} from './perf-figures.mjs'

const props = defineProps<{ name: string }>()
const {lang} = useData()

const files = import.meta.glob('../../../../test/benchmarks/results/reports/*/headline.json', {
    eager: true,
    import: 'default',
})
const headlines = Object.fromEntries(
    Object.entries(files).map(([path, headline]) => [path.split('/').at(-2), headline]),
)

const html = computed(() => figureHtml(props.name, lang.value.startsWith('zh') ? 'zh' : 'en', headlines))
</script>

<template>
  <div class="perf-figure-host" v-html="html"/>
</template>

<style>
.perf-figure {
  --perf-series-1: #2a78d6;
  --perf-series-2: #eb6834;
  margin: 24px 0;
}

.dark .perf-figure {
  --perf-series-1: #3987e5;
  --perf-series-2: #d95926;
}

.perf-figure .perf-panels {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.perf-figure .perf-panel {
  flex: 1 1 300px;
  min-width: 0;
}

.perf-figure .perf-key-line {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--vp-c-text-2);
}

.perf-figure .perf-best-key {
  display: inline-block;
  width: 10px;
  height: 10px;
  margin-right: 6px;
  border: 2px solid var(--vp-c-text-2);
  border-radius: 50%;
  vertical-align: -1px;
}

.perf-figure .perf-dot.perf-best {
  stroke: var(--vp-c-text-1);
  stroke-width: 2;
}

.perf-figure .perf-chart {
  display: block;
  width: 100%;
  height: auto;
  font-family: var(--vp-font-family-base);
}

.perf-figure .perf-chart.perf-narrow {
  display: none;
  max-width: 480px;
}

/* A label above its row crosses the grid: a halo of the page's colour keeps it legible. */
.perf-figure .perf-case {
  paint-order: stroke;
  stroke: var(--vp-c-bg);
  stroke-width: 4px;
  stroke-linejoin: round;
}

@media (max-width: 639px) {
  .perf-figure .perf-chart.perf-wide {
    display: none;
  }

  .perf-figure .perf-chart.perf-narrow {
    display: block;
  }
}

.perf-figure .perf-title {
  font-size: 14px;
  font-weight: 600;
  fill: var(--vp-c-text-1);
}

.perf-figure .perf-tick,
.perf-figure .perf-label {
  font-size: 11px;
  fill: var(--vp-c-text-2);
  font-variant-numeric: tabular-nums;
}

.perf-figure .perf-label {
  fill: var(--vp-c-text-1);
}

.perf-figure .perf-note {
  font-size: 11px;
  fill: var(--vp-c-text-3);
}

.perf-figure .perf-grid {
  stroke: var(--vp-c-divider);
  stroke-width: 1;
}

.perf-figure .perf-axis {
  stroke: var(--vp-c-text-3);
  stroke-width: 1;
}

.perf-figure .perf-limit {
  stroke: var(--vp-c-text-2);
  stroke-width: 1;
}

.perf-figure .perf-line {
  fill: none;
  stroke-width: 2;
  stroke-linejoin: round;
  stroke-linecap: round;
}

.perf-figure .perf-range {
  stroke-width: 2;
  stroke-linecap: round;
}

.perf-figure .perf-line.perf-series-1,
.perf-figure .perf-range.perf-series-1 {
  stroke: var(--perf-series-1);
}

.perf-figure .perf-line.perf-series-2 {
  stroke: var(--perf-series-2);
}

.perf-figure .perf-band {
  fill: var(--perf-series-1);
  opacity: 0.1;
}

.perf-figure .perf-dot {
  stroke: var(--vp-c-bg);
  stroke-width: 2;
}

.perf-figure .perf-dot.perf-series-1 {
  fill: var(--perf-series-1);
}

.perf-figure .perf-dot.perf-series-2 {
  fill: var(--perf-series-2);
}

.perf-figure .perf-dot.perf-hollow {
  fill: var(--vp-c-bg);
  stroke-width: 2;
}

.perf-figure .perf-dot.perf-hollow.perf-series-1 {
  stroke: var(--perf-series-1);
}

.perf-figure .perf-dot.perf-hollow.perf-series-2 {
  stroke: var(--perf-series-2);
}

.perf-figure .perf-legend {
  display: flex;
  gap: 16px;
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--vp-c-text-2);
}

.perf-figure .perf-key {
  display: inline-block;
  width: 16px;
  height: 2px;
  margin: 0 6px 4px 0;
  vertical-align: middle;
}

.perf-figure .perf-key.perf-series-1 {
  background: var(--perf-series-1);
}

.perf-figure .perf-key.perf-series-2 {
  background: var(--perf-series-2);
}

.perf-figure figcaption {
  margin-top: 8px;
  font-size: 13px;
  color: var(--vp-c-text-2);
}

.perf-figure details {
  margin-top: 8px;
  font-size: 13px;
}
</style>
