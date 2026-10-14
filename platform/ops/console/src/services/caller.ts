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

import type { FetchExchange, RequestInterceptor } from "@ahoo-wang/fetcher";

/**
 * Who calls the ops service. The service reads it from request headers
 * (`OpsCallers`), standing in for CoSec, whose resolver gives the same
 * callers from a token: `X-User` names the operator — without it the
 * service answers 401 — `X-Tenant` its tenant (`(0)`, the tenant the
 * services' ledgers publish to, when left out), `X-Role` its roles. A
 * deployment behind CoSec puts its token interceptor in place of
 * {@link CallerHeaders}.
 */
export interface OpsCaller {
  user: string;
  tenant: string;
  roles: readonly string[];
}

export const USER_HEADER = "X-User";
export const TENANT_HEADER = "X-Tenant";
export const ROLES_HEADER = "X-Role";

/** The role that acts on the entries (`OpsCallers.OPERATOR`). */
export const OPERATOR_ROLE = "ops.operator";

/** Wow's default tenant: the one the services' ledgers publish to. */
export const DEFAULT_TENANT = "(0)";

/** Roles as a header lists them: comma-separated, blanks dropped. */
export function parseRoles(value: string): string[] {
  return value
    .split(",")
    .map((role) => role.trim())
    .filter((role) => role !== "");
}

/** Whether `caller` may act on the entries, as the service decides it. */
export function isOperator(caller: OpsCaller | null): boolean {
  return caller?.roles.includes(OPERATOR_ROLE) ?? false;
}

/** A caller read back from storage, or `null` for anything else. */
export function readCaller(value: string | null): OpsCaller | null {
  if (!value) return null;
  try {
    const parsed = JSON.parse(value) as Partial<OpsCaller>;
    if (typeof parsed.user !== "string" || parsed.user.trim() === "")
      return null;
    return {
      user: parsed.user.trim(),
      tenant:
        typeof parsed.tenant === "string" && parsed.tenant.trim() !== ""
          ? parsed.tenant.trim()
          : DEFAULT_TENANT,
      roles: Array.isArray(parsed.roles)
        ? parsed.roles.filter(
            (role): role is string => typeof role === "string",
          )
        : [],
    };
  } catch {
    return null;
  }
}

const STORAGE_KEY = "wow-ops-console.caller";

/** The caller in force, kept on this machine, and who listens for a change. */
export class CallerStore {
  private caller: OpsCaller | null;
  private readonly listeners = new Set<() => void>();

  constructor(private readonly storage: Storage | null = safeStorage()) {
    this.caller = readCaller(this.read());
  }

  get = (): OpsCaller | null => this.caller;

  set(caller: OpsCaller | null): void {
    this.caller = caller;
    try {
      if (caller) this.storage?.setItem(STORAGE_KEY, JSON.stringify(caller));
      else this.storage?.removeItem(STORAGE_KEY);
    } catch {
      // Kept for this visit only.
    }
    for (const listener of this.listeners) listener();
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  private read(): string | null {
    try {
      return this.storage?.getItem(STORAGE_KEY) ?? null;
    } catch {
      return null;
    }
  }
}

function safeStorage(): Storage | null {
  try {
    return localStorage;
  } catch {
    return null;
  }
}

/** Sends the caller in force as the headers the ops service reads. */
export class CallerHeaders implements RequestInterceptor {
  readonly name = "OpsCallerHeaders";
  readonly order = 0;

  constructor(private readonly caller: () => OpsCaller | null) {}

  intercept(exchange: FetchExchange): void {
    const caller = this.caller();
    if (!caller) return;
    const headers = exchange.ensureRequestHeaders();
    headers[USER_HEADER] = caller.user;
    headers[TENANT_HEADER] = caller.tenant;
    if (caller.roles.length) headers[ROLES_HEADER] = caller.roles.join(",");
  }
}
