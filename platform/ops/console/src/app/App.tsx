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

import { Languages, UserRound } from "lucide-react";
import { Link, NavLink, Outlet } from "react-router";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useI18n } from "@/i18n.tsx";
import { isOperator, OPERATOR_ROLE } from "@/services/caller.ts";
import { CALLER_PATH, OVERVIEW_PATH, PARKED_PATH } from "@/views/bindings.ts";
import { OVERVIEW_WORDS } from "@/views/overview.ts";
import { PARKED_WORDS } from "@/views/parked.ts";
import { OpsHost } from "./OpsHost.tsx";
import { useCaller } from "./services.tsx";

function LanguageMenu() {
  const { locale, setLocale, t } = useI18n();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label={t("language")}
            title={t("language")}
          />
        }
      >
        <Languages />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuRadioGroup
          value={locale}
          onValueChange={(value) => {
            if (value === "en" || value === "zh-CN") setLocale(value);
          }}
        >
          <DropdownMenuRadioItem value="en" closeOnClick>
            English
          </DropdownMenuRadioItem>
          <DropdownMenuRadioItem value="zh-CN" closeOnClick>
            中文
          </DropdownMenuRadioItem>
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** Who the console calls as, and the way to change it. */
function CallerLink() {
  const { t } = useI18n();
  const caller = useCaller();
  return (
    <Button
      variant="ghost"
      size="sm"
      render={<Link to={CALLER_PATH} />}
      aria-label={`${t("caller")}: ${caller ? caller.user : t("signedOut")}`}
    >
      <UserRound data-icon="inline-start" />
      {caller ? caller.user : t("signedOut")}
      {caller && isOperator(caller) ? (
        <Badge variant="secondary">{OPERATOR_ROLE}</Badge>
      ) : null}
    </Button>
  );
}

/** The console's places, each a page of the top bar. */
const PLACES = [
  [OVERVIEW_PATH, OVERVIEW_WORDS],
  [PARKED_PATH, PARKED_WORDS],
] as const;

/**
 * The console's shell: one bar with the product, its places — the
 * overview and the parked entries — then the caller and the language; the
 * page below fills the rest of the window and scrolls inside itself.
 */
export default function App() {
  const { locale, t } = useI18n();
  return (
    <OpsHost>
      <div className="fve-tokens bg-background text-foreground">
        <div className="flex h-svh flex-col overflow-hidden">
          <header className="relative z-10 flex h-13 shrink-0 items-center gap-4 border-b border-sidebar-border bg-sidebar px-4 text-sidebar-foreground">
            <Link
              to={OVERVIEW_PATH}
              className="inline-flex items-center gap-2 font-semibold whitespace-nowrap"
            >
              <img src="logo.svg" alt="" className="size-5.5" />
              {t("product")}
            </Link>
            <nav className="flex h-full gap-1" aria-label={t("product")}>
              {PLACES.map(([path, words]) => (
                <NavLink
                  key={path}
                  to={path}
                  className="inline-flex h-full items-center border-b-2 border-transparent px-3 font-medium whitespace-nowrap text-muted-foreground hover:text-foreground aria-[current=page]:border-primary aria-[current=page]:text-foreground"
                >
                  {words[locale].title}
                </NavLink>
              ))}
            </nav>
            <div className="ml-auto flex min-w-0 items-center gap-1.5">
              <CallerLink />
              <LanguageMenu />
            </div>
          </header>
          <main
            id="main-content"
            tabIndex={-1}
            className="flex min-h-0 flex-1 flex-col outline-none"
          >
            <Outlet />
          </main>
        </div>
      </div>
    </OpsHost>
  );
}
