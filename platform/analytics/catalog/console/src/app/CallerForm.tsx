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

import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { useI18n } from "@/i18n.tsx";
import {
  DEFAULT_TENANT,
  EDITOR_ROLE,
  parseRoles,
  type CatalogCaller,
} from "@/services/caller.ts";
import { useCaller, useServices } from "./services.tsx";

/**
 * Signs in with a CoSec access token, which a deployment of the catalog service
 * verifies and reads the data owner from; or, for a local run, names the
 * caller the console sends as headers (`X-User`, `X-Tenant`, `X-Role`), the
 * catalog's stand-in for CoSec, kept on this machine.
 */
export function CallerForm({ onDone }: { onDone?: () => void }) {
  const { callers } = useServices();
  const current = useCaller();
  return (
    <div className="flex w-full max-w-md flex-col gap-4">
      {callers.signsIn ? (
        current?.via === "cosec" ? (
          <SignedIn user={current.user} onDone={onDone} />
        ) : (
          <TokenForm onDone={onDone} />
        )
      ) : null}
      <HeadersForm onDone={onDone} />
    </div>
  );
}

/** Who is signed in with a CoSec token, and the way out. */
function SignedIn({ user, onDone }: { user: string; onDone?: () => void }) {
  const { t } = useI18n();
  const { callers } = useServices();
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("signInTitle")}</CardTitle>
        <CardDescription>{t("signedInAs", { user })}</CardDescription>
      </CardHeader>
      <CardFooter>
        <Button
          type="button"
          variant="outline"
          onClick={() => {
            callers.set(null);
            onDone?.();
          }}
        >
          {t("signOutToken")}
        </Button>
      </CardFooter>
    </Card>
  );
}

/** Signs in with an access token the enterprise's CoSec issued. */
function TokenForm({ onDone }: { onDone?: () => void }) {
  const { t } = useI18n();
  const { callers } = useServices();
  const [token, setToken] = useState("");
  const [refused, setRefused] = useState(false);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const value = token.trim().replace(/^Bearer\s+/i, "");
    if (value === "") return;
    if (!callers.signIn(value)) {
      setRefused(true);
      return;
    }
    onDone?.();
  };

  return (
    <Card>
      <form onSubmit={submit} aria-label={t("signInTitle")}>
        <CardHeader>
          <CardTitle>{t("signInTitle")}</CardTitle>
          <CardDescription>{t("signInDescription")}</CardDescription>
        </CardHeader>
        <CardContent className="py-4">
          <Field data-invalid={refused || undefined}>
            <FieldLabel htmlFor="caller-token">{t("token")}</FieldLabel>
            <Input
              id="caller-token"
              type="password"
              value={token}
              autoComplete="off"
              aria-invalid={refused || undefined}
              onChange={(event) => {
                setToken(event.target.value);
                setRefused(false);
              }}
            />
            {refused ? (
              <FieldDescription role="alert">
                {t("tokenRefused")}
              </FieldDescription>
            ) : null}
          </Field>
        </CardContent>
        <CardFooter>
          <Button type="submit" disabled={token.trim() === ""}>
            {t("signIn")}
          </Button>
        </CardFooter>
      </form>
    </Card>
  );
}

/**
 * Names the caller the console sends as headers, for a local run of the catalog
 * (`wow.platform.trust-identity-headers`).
 */
function HeadersForm({ onDone }: { onDone?: () => void }) {
  const { t } = useI18n();
  const { callers } = useServices();
  const caller = useCaller();
  const current = caller?.via === "cosec" ? null : caller;
  const [user, setUser] = useState(current?.user ?? "");
  const [tenant, setTenant] = useState(current?.tenant ?? DEFAULT_TENANT);
  const [roles, setRoles] = useState(
    current ? current.roles.join(", ") : EDITOR_ROLE,
  );

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (user.trim() === "") return;
    const named: CatalogCaller = {
      user: user.trim(),
      tenant: tenant.trim() || DEFAULT_TENANT,
      roles: parseRoles(roles),
    };
    callers.set(named);
    onDone?.();
  };

  return (
    <Card>
      <form onSubmit={submit} aria-label={t("callerTitle")}>
        <CardHeader>
          <CardTitle>{t("callerTitle")}</CardTitle>
          <CardDescription>{t("callerDescription")}</CardDescription>
        </CardHeader>
        <CardContent className="py-4">
          <FieldGroup>
            <Field>
              <FieldLabel htmlFor="caller-user">{t("user")}</FieldLabel>
              <Input
                id="caller-user"
                value={user}
                required
                autoComplete="off"
                onChange={(event) => setUser(event.target.value)}
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="caller-tenant">{t("tenant")}</FieldLabel>
              <Input
                id="caller-tenant"
                value={tenant}
                autoComplete="off"
                onChange={(event) => setTenant(event.target.value)}
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="caller-roles">{t("roles")}</FieldLabel>
              <Input
                id="caller-roles"
                value={roles}
                autoComplete="off"
                onChange={(event) => setRoles(event.target.value)}
              />
              <FieldDescription>{t("rolesHint")}</FieldDescription>
            </Field>
          </FieldGroup>
        </CardContent>
        <CardFooter className="gap-2">
          <Button type="submit" disabled={user.trim() === ""}>
            {t("save")}
          </Button>
          {current ? (
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                callers.set(null);
                onDone?.();
              }}
            >
              {t("signOut")}
            </Button>
          ) : null}
        </CardFooter>
      </form>
    </Card>
  );
}
