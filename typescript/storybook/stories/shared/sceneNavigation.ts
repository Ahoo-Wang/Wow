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
/* --------------------------------------------------------------------------
 * The host's navigation as the engine gives it (host-integration.md 4.3):
 * the retail workbenches are resources of 栖木生活's host, each bound to
 * the page — here, the story — it lives on, and the shell draws its links
 * from `useViewNavigation` rather than from a list of its own. The boards
 * and the detail pages are the catalogue's pages, not resources.
 * ------------------------------------------------------------------------ */

import {
  MemoryViewStore,
  ViewEngine,
  type ViewSource,
} from '@ahoo-wang/wow-view-engine';
import { bind, type ViewRouter } from '@ahoo-wang/wow-view-engine/ui';
import { RETAIL_BOARD_DEFINITIONS } from '../view-engine/retail/boards.js';
import {
  RETAIL_AFTER_SALES,
  RETAIL_ORDER_ANALYSIS,
  RETAIL_ORDER_EVENTS,
  RETAIL_ORDERS,
  RETAIL_WAYBILLS,
} from '../view-engine/retail/views.js';

/**
 * A scene's link: relative to the page, not the site root — the anchor
 * lives in `iframe.html`, whose directory is the Storybook root wherever it
 * is served (`/` locally, `/storybook/` on GitHub Pages).
 */
export function storyHref(story: string): string {
  return `./?path=/story/${story}`;
}

/** Each retail workbench, by its resource: the story its page opens on. */
const WORKBENCH_STORIES = [
  {
    resource: RETAIL_ORDERS,
    story: 'view-engine-业务场景-订单工作台--order-workbench-scene',
  },
  {
    resource: RETAIL_AFTER_SALES,
    story: 'view-engine-业务场景-售后工作台--after-sale-workbench',
  },
  {
    resource: RETAIL_ORDER_ANALYSIS,
    story: 'view-engine-业务场景-分析工作台--order-analysis',
  },
  {
    resource: RETAIL_WAYBILLS,
    story: 'view-engine-业务场景-运单宽表--waybill-wide-table',
  },
  {
    resource: RETAIL_ORDER_EVENTS,
    story: 'view-engine-业务场景-订单事件流--order-event-stream',
  },
];

export const RETAIL_WORKBENCHES: Readonly<Record<string, string>> =
  Object.fromEntries(
    WORKBENCH_STORIES.map(({ resource, story }) => [resource, story]),
  );

/** The route table: one line per resource, every view on its page. */
export const SCENE_ROUTES = WORKBENCH_STORIES.map(({ resource, story }) =>
  bind(resource, { route: () => storyHref(story) }),
);

/** The navigation reads the resources; it never asks them for a row. */
const UNASKED: ViewSource = {
  paged: () => Promise.reject(new Error('The navigation reads no rows.')),
  cursor: () => Promise.reject(new Error('The navigation reads no rows.')),
  aggregate: () => Promise.reject(new Error('The navigation reads no rows.')),
};

let engine: ViewEngine | undefined;

/** The retail host's resources, as the shell's navigation reads them. */
export function sceneEngine(): ViewEngine {
  engine ??= new ViewEngine({
    resources: RETAIL_BOARD_DEFINITIONS.map(definition =>
      definition.kind === 'data'
        ? { definition, source: UNASKED }
        : { definition },
    ),
    store: new MemoryViewStore(),
    onIssue: () => {},
  });
  return engine;
}

/**
 * The router port over Storybook's address: the shell is on the story
 * `story`, and a way elsewhere moves the whole Storybook, as a host's
 * navigation moves the whole page.
 */
export function sceneRouter(story: string | undefined): ViewRouter {
  return {
    location: {
      pathname: './',
      search: story ? `?path=/story/${story}` : '',
      state: null,
    },
    go: path => window.top?.location.assign(new URL(path, location.href)),
  };
}
