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

import { ErrorCodes } from "@ahoo-wang/wow-client";
import type { Page, Route } from "@playwright/test";

/**
 * The compensation service's view store (`wow-view-store-starter`) as the
 * console reaches it, in memory: the routes `WowViewStore` sends under
 * `/view-store/tenant/{tenantId}/owner/{ownerId}/…`, with the server's
 * rules a test can see — the version a write expects, the request id a
 * replay answers, the tenant, owner and application of every path. A test
 * keeps one per page, so what it saves is still there after a reload.
 *
 * It answers only the console's own scope: the tenant `(0)`, the owner
 * `(shared)` and the application `compensation-dashboard`. Anything else
 * is refused as the server refuses a missing scope, so a console that
 * forgot its defaults fails here as it would against the service.
 */

export const CONSOLE_SCOPE = {
  tenantId: "(0)",
  ownerId: "(shared)",
  appId: "compensation-dashboard",
};

interface StoredView {
  aggregateId: string;
  version: number;
  deleted: boolean;
  state: {
    definitionId: string;
    title: string;
    audience: "shared";
    config: Record<string, unknown> & { kind: string };
  };
}

interface StoredPreferences {
  definitionId: string;
  order: string[];
  defaultInstanceId: string | null;
  autoRun: boolean | null;
  lastTabs: Record<string, string> | null;
  version: number;
}

/** A request the view store answered, as the page sent it. */
export interface ViewStoreRequest {
  method: string;
  /** The path under the scope, e.g. `view/snapshot/list`. */
  path: string;
  tenantId: string;
  ownerId: string;
  appId: string | undefined;
}

/** What a test can read of the store. */
export interface ViewStoreService {
  /** Every request, in the order it came. */
  readonly requests: ViewStoreRequest[];
  /** The views it keeps, deleted ones included. */
  readonly views: ReadonlyMap<string, StoredView>;
}

const SCOPE =
  /\/view-store\/tenant\/([^/]+)\/owner\/([^/]+)\/(.+?)(?:\?(.*))?$/;

async function answer(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  });
}

async function refuse(
  route: Route,
  status: number,
  errorCode: string,
  errorMsg: string,
) {
  await answer(route, { errorCode, errorMsg }, status);
}

/** The value the client's single-query filter names (`filter.id(id)`). */
function idOf(query: { filter?: { value?: unknown } } | null): string {
  return String(query?.filter?.value ?? "");
}

/** The value the client's list filter names (`state.definitionId` eq). */
function definitionOf(
  query: {
    filter?: { value?: unknown; children?: { value?: unknown }[] };
  } | null,
): string {
  const filter = query?.filter;
  return String(filter?.value ?? filter?.children?.[0]?.value ?? "");
}

/**
 * Routes the view store of the compensation service for `page`, empty, and
 * returns what it holds and was asked.
 */
export async function stubViewStore(page: Page): Promise<ViewStoreService> {
  const views = new Map<string, StoredView>();
  const preferences = new Map<string, StoredPreferences>();
  /** A write's request id, and the view as that write left it (null: deleted). */
  const written = new Map<string, StoredView | null>();
  const requests: ViewStoreRequest[] = [];
  let nextId = 1;

  await page.route(/\/view-store\/tenant\//, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const [, tenant, owner, path] = SCOPE.exec(url.pathname) ?? [];
    const tenantId = decodeURIComponent(tenant ?? "");
    const ownerId = decodeURIComponent(owner ?? "");
    const headers = request.headers();
    const appId = headers["cosec-app-id"];
    const method = request.method();
    requests.push({ method, path: path ?? "", tenantId, ownerId, appId });

    if (
      tenantId !== CONSOLE_SCOPE.tenantId ||
      ownerId !== CONSOLE_SCOPE.ownerId
    )
      return refuse(
        route,
        400,
        "ViewScopeRequired",
        `No view store at tenant [${tenantId}] owner [${ownerId}].`,
      );
    if (appId !== CONSOLE_SCOPE.appId)
      return refuse(route, 400, "ViewAppRequired", "CoSec-App-Id is required.");

    const requestId = headers["command-request-id"];
    const expected = headers["command-aggregate-version"];
    const body = request.postData() ? request.postDataJSON() : null;
    const live = (id: string) => {
      const view = views.get(id);
      return view && !view.deleted ? view : undefined;
    };
    const result = (view: StoredView) => ({
      id: `${view.aggregateId}-${view.version}`,
      aggregateId: view.aggregateId,
      aggregateVersion: view.version,
      errorCode: "Ok",
      errorMsg: "",
      stage: "SNAPSHOT",
    });
    const duplicate = () =>
      requestId !== undefined && written.has(requestId)
        ? refuse(
            route,
            409,
            ErrorCodes.DUPLICATE_REQUEST_ID,
            `Duplicate request id [${requestId}].`,
          )
        : undefined;

    // Reads.
    if (method === "POST" && path === "view/snapshot/list") {
      const definitionId = definitionOf(body);
      return answer(
        route,
        [...views.values()].filter(
          (view) => !view.deleted && view.state.definitionId === definitionId,
        ),
      );
    }
    if (method === "POST" && path === "view/snapshot/single") {
      const view = live(idOf(body));
      return view
        ? answer(route, view)
        : refuse(route, 404, ErrorCodes.NOT_FOUND, "Not found.");
    }
    if (method === "GET" && path === "system-views") return answer(route, []);
    if (method === "GET" && path.startsWith("system-views/"))
      return refuse(route, 404, ErrorCodes.NOT_FOUND, "Not found.");
    const replay = /^view\/requests\/(.+)$/.exec(path);
    if (method === "GET" && replay) {
      const id = decodeURIComponent(replay[1]);
      if (!written.has(id))
        return refuse(route, 404, ErrorCodes.NOT_FOUND, "Not found.");
      const view = written.get(id);
      return view
        ? answer(route, view)
        : route.fulfill({ status: 204, body: "" });
    }
    const preferencesPath = /^definitions\/(.+)\/preferences$/.exec(path);
    if (preferencesPath) {
      const definitionId = decodeURIComponent(preferencesPath[1]);
      const stored = preferences.get(definitionId) ?? {
        definitionId,
        order: [],
        defaultInstanceId: null,
        autoRun: null,
        lastTabs: null,
        version: 0,
      };
      if (method === "GET") return answer(route, stored);
      if (expected !== undefined && Number(expected) !== stored.version)
        return refuse(
          route,
          409,
          ErrorCodes.COMMAND_EXPECT_VERSION_CONFLICT,
          "Expected version conflict.",
        );
      const next = {
        ...stored,
        ...body,
        definitionId,
        version: stored.version + 1,
      };
      preferences.set(definitionId, next);
      return answer(route, {
        aggregateId: `preferences-${definitionId}`,
        aggregateVersion: next.version,
        errorCode: "Ok",
        errorMsg: "",
      });
    }

    // Writes.
    if (method === "POST" && path === "view") {
      const refused = duplicate();
      if (refused) return refused;
      const view: StoredView = {
        aggregateId: `view-${nextId++}`,
        version: 1,
        deleted: false,
        state: { ...body, audience: "shared" },
      };
      views.set(view.aggregateId, view);
      if (requestId) written.set(requestId, structuredClone(view));
      return answer(route, result(view));
    }
    const write = /^view\/([^/]+)(?:\/(save|rename|share))?$/.exec(path);
    if (write && (method === "PUT" || method === "DELETE")) {
      const refused = duplicate();
      if (refused) return refused;
      const view = live(decodeURIComponent(write[1]));
      if (!view) return refuse(route, 404, ErrorCodes.NOT_FOUND, "Not found.");
      if (expected !== undefined && Number(expected) !== view.version)
        return refuse(
          route,
          409,
          ErrorCodes.COMMAND_EXPECT_VERSION_CONFLICT,
          "Expected version conflict.",
        );
      const action = method === "DELETE" ? "delete" : write[2];
      if (action === "share") return answer(route, result(view));
      view.version += 1;
      if (action === "save") view.state.config = body.config;
      else if (action === "rename")
        view.state.title = String(body.title).trim();
      else view.deleted = true;
      if (requestId)
        written.set(requestId, view.deleted ? null : structuredClone(view));
      return answer(route, result(view));
    }
    return refuse(
      route,
      404,
      ErrorCodes.NOT_FOUND,
      `No route ${method} ${path}.`,
    );
  });

  return { requests, views };
}
