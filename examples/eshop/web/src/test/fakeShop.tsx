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

import { render } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import type { CommandResult } from "@ahoo-wang/wow-client";
import { commandResultOf } from "@ahoo-wang/wow-client/testing";
import { routes } from "@/App";
import { SessionProvider, type User } from "@/shop/session";

/** A request the fake services received. */
export interface Received {
  readonly method: string;
  readonly path: string;
  readonly headers: Record<string, string>;
  readonly body: unknown;
}

/** An answer: a JSON body with its status, or a server-sent event stream. */
export type Answer =
  | { readonly status?: number; readonly json: unknown }
  | { readonly events: readonly CommandResult[] };

type Route = [method: string, path: RegExp, answer: (request: Received) => Answer];

/**
 * The five services as one `fetch`: each request is matched against the
 * routes in order, and recorded in `received`; an unmatched one is a 404
 * problem, as the services answer it.
 */
export function fakeServices(routes: Route[]) {
  const received: Received[] = [];
  const transport = async (
    input: RequestInfo | URL,
    init: RequestInit = {},
  ): Promise<Response> => {
    const url = new URL(String(input instanceof Request ? input.url : input), "http://shop");
    const headers = Object.fromEntries(
      new Headers(init.headers).entries(),
    );
    const request: Received = {
      method: init.method ?? "GET",
      path: url.pathname + url.search,
      headers,
      body: typeof init.body === "string" ? JSON.parse(init.body) : init.body,
    };
    received.push(request);
    const route = routes.find(
      ([method, path]) => method === request.method && path.test(request.path),
    );
    if (!route) return problem(404, "Not Found", "wow.route.not-found");
    const answer = route[2](request);
    if ("events" in answer) {
      const text = answer.events
        .map(
          (event, index) =>
            `id:${index}\nevent:${event.hop.stage.toLowerCase()}\ndata:${JSON.stringify(event)}\n\n`,
        )
        .join("");
      return new Response(text, {
        status: 200,
        headers: { "Content-Type": "text/event-stream" },
      });
    }
    return new Response(JSON.stringify(answer.json), {
      status: answer.status ?? 200,
      headers: {
        "Content-Type":
          (answer.status ?? 200) >= 400
            ? "application/problem+json"
            : "application/json",
      },
    });
  };
  return { transport: transport as typeof fetch, received };
}

/** A problem+json answer. */
export function problem(
  status: number,
  title: string,
  code: string,
  extra: Record<string, unknown> = {},
): Response {
  return new Response(
    JSON.stringify({
      type: `urn:wow:error:${code}`,
      title,
      status,
      code,
      ...extra,
    }),
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
}

/** A problem answer for a route. */
export function problemAnswer(
  status: number,
  title: string,
  code: string,
  extra: Record<string, unknown> = {},
): Answer {
  return {
    status,
    json: { type: `urn:wow:error:${code}`, title, status, code, ...extra },
  };
}

/** A command's result. */
export function result(
  overrides: Partial<CommandResult> & {
    context?: string;
    aggregate?: string;
    id?: string;
  } = {},
): CommandResult {
  const { context = "eshop-catalog", aggregate = "product", id = "mug", ...rest } =
    overrides;
  return commandResultOf({ type: { context, name: aggregate }, tenantId: "(0)", id }, rest);
}

/** A snapshot record as the query routes answer it. */
export function snapshot<S>(aggregateId: string, state: S, extra: object = {}) {
  return {
    aggregateId,
    tenantId: "(0)",
    ownerId: null,
    spaceId: null,
    version: 1,
    deleted: false,
    tags: {},
    firstEventTime: 0,
    lastEventTime: 0,
    operator: null,
    firstOperator: null,
    state,
    ...extra,
  };
}

/** A state view as `GET …/{id}/state` answers it. */
export function stateView<S>(
  context: string,
  aggregate: string,
  id: string,
  state: S,
  ownerId: string | null = null,
) {
  return {
    ref: { type: { context, name: aggregate }, tenantId: "(0)", id },
    version: 1,
    scope: { ownerId, spaceId: null },
    deleted: false,
    tags: {},
    state,
  };
}

/** Renders the app at `path`, acting as `user`, on `transport`. */
export function renderShop(path: string, user: User, transport: typeof fetch) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  return {
    router,
    ...render(
      <SessionProvider initialUser={user} transport={transport}>
        <RouterProvider router={router} />
      </SessionProvider>,
    ),
  };
}

export const ALICE: User = { id: "alice", role: "buyer" };
export const ERIN: User = { id: "erin", role: "employee" };
