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

import type { RequestInterceptor } from "@ahoo-wang/fetcher";
import { developmentCaller } from "@ahoo-wang/wow-client";
import type { TokenStorage } from "@ahoo-wang/fetcher-cosec";

/**
 * Who calls the analytics catalog. A deployment (the server's `cosec`
 * profile) reads it from the CoSec access token the analyst signed in with
 * (`via: "cosec"`): its subject, its tenant and its roles, which the console
 * reads from the token too. A development run of the service (its `dev`
 * profile) reads it from request headers instead (the host's development
 * callers): `Wow-User` names the caller — without it the service answers 401
 * — `Wow-Tenant` its tenant (`(platform)`, the catalog's, sent always),
 * `Wow-Roles` its roles ({@link callerHeaders}).
 */
export interface CatalogCaller {
  user: string;
  tenant: string;
  roles: readonly string[];
  /** `cosec` when the caller is a CoSec token's; a caller of headers otherwise. */
  via?: "cosec";
  /** When a CoSec token's caller stops being one (its `exp`), epoch milliseconds. */
  expiresAt?: number;
}

/** The catalog's roles (`AnalyticsCatalogRoles`, D251 ②). */
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

/** Whether `caller` holds any of the catalog's roles: without one it reads nothing. */
export function isReader(caller: CatalogCaller | null): boolean {
  return [ADMIN_ROLE, APPROVER_ROLE, EDITOR_ROLE, VIEWER_ROLE].some((role) =>
    hasRole(caller, role),
  );
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
    ...(typeof payload.exp === "number"
      ? { expiresAt: payload.exp * 1000 }
      : {}),
  };
}

const STORAGE_KEY = "wow-analytics-catalog-console.caller";

/**
 * Why the console was signed out, to say on the sign-in page: the CoSec
 * token expired, the gateway or the service refused it, or the service is
 * not a development run and reads no caller from headers.
 */
export type SignedOut = "expired" | "refused" | "headers-refused";

/**
 * The caller in force, and who listens for a change: the CoSec token's when
 * one is signed in (`tokens`, kept where CoSec front ends keep it, so a single
 * sign-on that stored one signs the console in too), else the caller of
 * headers named for a local run, kept on this machine.
 */
export class CallerStore {
  private headers: CatalogCaller | null;
  private caller: CatalogCaller | null;
  private why: SignedOut | null = null;
  private readonly listeners = new Set<() => void>();

  constructor(
    private readonly storage: Storage | null = safeStorage(),
    private readonly tokens: TokenStorage | null = null,
  ) {
    this.headers = readCaller(this.read());
    this.caller = this.current();
  }

  get = (): CatalogCaller | null => this.caller;

  /** Why the console was last signed out, until a caller is named again. */
  signedOut = (): SignedOut | null => this.why;

  /**
   * Signs out after the service answered 401: the caller in force, CoSec's
   * or of headers, is let go, and `why` is said on the sign-in page.
   */
  refused(): void {
    const caller = this.caller;
    if (!caller) return;
    const why: SignedOut =
      caller.via !== "cosec"
        ? "headers-refused"
        : caller.expiresAt !== undefined && caller.expiresAt <= Date.now()
          ? "expired"
          : "refused";
    this.tokens?.signOut();
    this.keep(null);
    this.why = why;
    this.changed();
  }

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
    this.why = null;
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
    if (signedIn) {
      this.keep(null);
      this.why = null;
    } else this.tokens.signOut();
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
export function callerHeaders(
  caller: () => CatalogCaller | null,
): RequestInterceptor {
  return developmentCaller(() => {
    const current = caller();
    if (!current || current.via === "cosec") return null;
    return { user: current.user, tenant: current.tenant, roles: current.roles };
  });
}

/** What a caller is known by: another one reads other records. */
export function callerKey(caller: CatalogCaller): string {
  return `${caller.tenant}/${caller.user}/${caller.roles.join(",")}`;
}
