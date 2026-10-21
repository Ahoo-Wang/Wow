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
import type { TokenStorage } from "@ahoo-wang/fetcher-cosec";

/**
 * Who calls the analytics catalog. A deployment (the server's `cosec`
 * profile) reads it from the CoSec access token the analyst signed in with
 * (`via: "cosec"`): its subject, its tenant and its roles, which the console
 * reads from the token too. A local run (`wow.platform.trust-identity-headers`)
 * reads it from request headers instead (`AnalyticsCatalogCallers`): `X-User`
 * names the caller — without it the service answers 401 — `X-Tenant` its
 * tenant (`(platform)`, the catalog's, when left out), `X-Role` its roles
 * ({@link CallerHeaders}).
 */
export interface CatalogCaller {
  user: string;
  tenant: string;
  roles: readonly string[];
  /** `cosec` when the caller is a CoSec token's; a caller of headers otherwise. */
  via?: "cosec";
}

export const USER_HEADER = "X-User";
export const TENANT_HEADER = "X-Tenant";
export const ROLES_HEADER = "X-Role";

/** The catalog's roles (`AnalyticsCatalogCallers`, D251 ②). */
export const ADMIN_ROLE = "analytics.catalog.admin";
export const APPROVER_ROLE = "analytics.catalog.approver";
export const EDITOR_ROLE = "analytics.catalog.editor";
export const VIEWER_ROLE = "analytics.catalog.viewer";

/** The catalog's tenant: every source and dataset is the platform's. */
export const DEFAULT_TENANT = "(platform)";

/** Roles as a header lists them: comma-separated, blanks dropped. */
export function parseRoles(value: string): string[] {
  return value
    .split(",")
    .map((role) => role.trim())
    .filter((role) => role !== "");
}

/** Whether `caller` runs an analyst's work (drafts, revises, publishes), as the service decides it. */
export function isEditor(caller: CatalogCaller | null): boolean {
  return hasRole(caller, EDITOR_ROLE) || hasRole(caller, ADMIN_ROLE);
}

/** Whether `caller` approves (reviews, sensitivity, sources and publications). */
export function isApprover(caller: CatalogCaller | null): boolean {
  return hasRole(caller, APPROVER_ROLE);
}

/** Whether `caller` has `role`. */
export function hasRole(caller: CatalogCaller | null, role: string): boolean {
  return caller?.roles.includes(role) ?? false;
}

/** A caller read back from storage, or `null` for anything else. */
export function readCaller(value: string | null): CatalogCaller | null {
  if (!value) return null;
  try {
    const parsed = JSON.parse(value) as Partial<CatalogCaller>;
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

/**
 * The caller of the CoSec token `tokens` holds, or `null` without one that is
 * still valid; without a tenant, the catalog's.
 */
export function tokenCaller(tokens: TokenStorage | null): CatalogCaller | null {
  if (!tokens?.authenticated) return null;
  const payload = tokens.currentUser;
  if (!payload?.sub) return null;
  return {
    user: payload.sub,
    tenant: payload.tenantId || DEFAULT_TENANT,
    roles: Array.isArray(payload.roles) ? payload.roles : [],
    via: "cosec",
  };
}

const STORAGE_KEY = "wow-analytics-catalog-console.caller";

/**
 * The caller in force, and who listens for a change: the CoSec token's when
 * one is signed in (`tokens`, kept where CoSec front ends keep it, so a single
 * sign-on that stored one signs the console in too), else the caller of
 * headers named for a local run, kept on this machine.
 */
export class CallerStore {
  private headers: CatalogCaller | null;
  private caller: CatalogCaller | null;
  private readonly listeners = new Set<() => void>();

  constructor(
    private readonly storage: Storage | null = safeStorage(),
    private readonly tokens: TokenStorage | null = null,
  ) {
    this.headers = readCaller(this.read());
    this.caller = this.current();
  }

  get = (): CatalogCaller | null => this.caller;

  /** Whether a CoSec token can be signed in with here. */
  get signsIn(): boolean {
    return this.tokens !== null;
  }

  /**
   * Calls as the caller of headers `caller`, or as nobody with `null`: either
   * way the CoSec token, if any, is signed out.
   */
  set(caller: CatalogCaller | null): void {
    this.tokens?.signOut();
    this.keep(caller);
    this.changed();
  }

  /**
   * Signs in with the CoSec access token `accessToken`; `false`, and nobody
   * signed in, when it is not one or has expired. The console cannot refresh
   * it: the operator signs in again.
   */
  signIn(accessToken: string): boolean {
    if (!this.tokens) return false;
    this.tokens.signIn({ accessToken, refreshToken: accessToken });
    const signedIn = tokenCaller(this.tokens) !== null;
    if (signedIn) this.keep(null);
    else this.tokens.signOut();
    this.changed();
    return signedIn;
  }

  private keep(caller: CatalogCaller | null): void {
    this.headers = caller;
    try {
      if (caller) this.storage?.setItem(STORAGE_KEY, JSON.stringify(caller));
      else this.storage?.removeItem(STORAGE_KEY);
    } catch {
      // Kept for this visit only.
    }
  }

  private current(): CatalogCaller | null {
    return tokenCaller(this.tokens) ?? this.headers;
  }

  private changed(): void {
    this.caller = this.current();
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

/**
 * Sends the caller of headers in force as the headers the catalog reads;
 * a CoSec token's caller goes as its token instead (`withCoSec`).
 */
export class CallerHeaders implements RequestInterceptor {
  readonly name = "CatalogCallerHeaders";
  readonly order = 0;

  constructor(private readonly caller: () => CatalogCaller | null) {}

  intercept(exchange: FetchExchange): void {
    const caller = this.caller();
    if (!caller || caller.via === "cosec") return;
    const headers = exchange.ensureRequestHeaders();
    headers[USER_HEADER] = caller.user;
    headers[TENANT_HEADER] = caller.tenant;
    if (caller.roles.length) headers[ROLES_HEADER] = caller.roles.join(",");
  }
}

/** What a caller is known by: another one reads other records. */
export function callerKey(caller: CatalogCaller): string {
  return `${caller.tenant}/${caller.user}/${caller.roles.join(",")}`;
}
