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

/** Where a chart failed: its library never arrived, or it threw drawing. */
export type ChartStage = 'load' | 'draw';

/**
 * A chart that could not be drawn, carrying what the library threw.
 *
 * It is thrown in render, so the render boundary around the chart stands in
 * for it and offers to draw it again, as for any other part; the boundary
 * tells it apart by this class and hands the host a `chart` failure rather
 * than a `render` one (D40), with the library's own error. Its message is
 * that error's, so the fallback says what it always said.
 */
export class ChartFailure extends Error {
  readonly stage: ChartStage;
  /** What the library, or its loader, threw. */
  readonly cause: unknown;

  constructor(stage: ChartStage, cause: unknown) {
    super(
      cause instanceof Error
        ? cause.message
        : typeof cause === 'string'
          ? cause
          : '',
    );
    this.name = 'ChartFailure';
    this.stage = stage;
    this.cause = cause;
  }
}

export function isChartFailure(error: unknown): error is ChartFailure {
  return error instanceof ChartFailure;
}
