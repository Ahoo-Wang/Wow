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
  OPERATOR_ROLE,
  parseRoles,
  type OpsCaller,
} from "@/services/caller.ts";
import { useCaller, useServices } from "./services.tsx";

/**
 * Names the caller the console sends (`X-User`, `X-Tenant`, `X-Role`), the
 * ops service's stand-in for CoSec. It is kept on this machine.
 */
export function CallerForm({ onDone }: { onDone?: () => void }) {
  const { t } = useI18n();
  const { callers } = useServices();
  const current = useCaller();
  const [user, setUser] = useState(current?.user ?? "");
  const [tenant, setTenant] = useState(current?.tenant ?? DEFAULT_TENANT);
  const [roles, setRoles] = useState(
    current ? current.roles.join(", ") : OPERATOR_ROLE,
  );

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (user.trim() === "") return;
    const caller: OpsCaller = {
      user: user.trim(),
      tenant: tenant.trim() || DEFAULT_TENANT,
      roles: parseRoles(roles),
    };
    callers.set(caller);
    onDone?.();
  };

  return (
    <Card className="w-full max-w-md">
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
