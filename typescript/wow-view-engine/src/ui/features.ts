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
 * Which of the workbench's own controls a host wants on screen (D18 XI).
 *
 * All on by default. A feature turned off is **absent**, never disabled
 * (D4): a sensitive view that must not be exported has no export button,
 * rather than a grey one that teaches the user what they may not do. These
 * are the host's presentation choices; whether a *user* may do something is
 * the store's `ViewPermissions`, which stays a separate question.
 */
export interface WorkbenchFeatures {
  /** The export button and window (D14). Record views only. */
  export?: boolean;
  /** The table/cards switch. Record views only. */
  layouts?: boolean;
  /** The column settings, or the card settings under the card layout. */
  columns?: boolean;
  /** The sort settings. Record views only. */
  sort?: boolean;
  /**
   * The visualization panel and the 「可视化」 button that opens it (D20 屏
   * I／J). Analysis views only: off, the result is read as the saved layout
   * says — a table, or the chart already in the config — and there is no way
   * into the panel, because a panel nobody can reach is a panel that does
   * not exist.
   */
  visualization?: boolean;
  /** The view manager — its gear in the sidebar and its item in the switcher. */
  manage?: boolean;
  /**
   * The search box at the end of the applied band (`SearchBox`). It is there
   * only where the definition declares a search field; off, the search is
   * still a condition the editor can add, just not one kept on hand.
   */
  search?: boolean;
}

/** Every feature, as the answer when a host says nothing. */
export const ALL_FEATURES: Readonly<Required<WorkbenchFeatures>> = {
  export: true,
  layouts: true,
  columns: true,
  sort: true,
  visualization: true,
  manage: true,
  search: true,
};

/** The host's answer over the default, with no member left undefined. */
export function featuresOf(
  given: WorkbenchFeatures | undefined,
): Required<WorkbenchFeatures> {
  return { ...ALL_FEATURES, ...given };
}
