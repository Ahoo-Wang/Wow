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

/**
 * What every chart-library family spreads onto its chart: a drawing with a
 * name, and nothing else.
 *
 * Recharts turns its `accessibilityLayer` on by default, and that layer puts
 * `role="application"` and `tabIndex={0}` on the root `<svg>`. Neither is
 * true of these charts. `application` tells a screen reader to leave browse
 * mode and hand every keystroke to an element with no key handling at all,
 * and the tab stop it pairs with led into a picture that answered nothing:
 * the `<title>` and `<desc>` recharts renders there are empty elements, and
 * the only text under the `<svg>` is the axis ticks.
 *
 * So the layer goes off and the drawing says what it is instead. The numbers
 * are not lost — `ChartReadingTable` carries them beside the chart, off the
 * same projection the marks come from.
 *
 * Recharts lets the two attributes through verbatim (`svgPropertiesNoEvents`
 * passes `role` and every `aria-*`), and its own defaults only apply where a
 * caller gave none, so these win. `test/analysisChartA11y.test.tsx` reads the
 * rendered `<svg>` rather than trusting that.
 */
export function asImage(name: string) {
  return {
    accessibilityLayer: false,
    role: 'img',
    'aria-label': name,
  } as const;
}
