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

import {
  GitCommitHorizontal,
  Languages,
  Menu,
  Monitor,
  Moon,
  Sun,
  X,
} from "lucide-react";
import { useId, useState } from "react";
import { Link, NavLink, Outlet } from "react-router";
import { ErrorBoundary } from "../../components/ErrorBoundary/ErrorBoundary.tsx";
import type { NavItem } from "../../routes/constants.tsx";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { useI18n, type Message } from "@/i18n.tsx";
import { HOME_PATH } from "@/views/navigation.ts";
import { COLOR_MODES, useColorMode, type ColorMode } from "./colorMode.ts";

interface AppProps {
  navItems: readonly NavItem[];
}

const buildVersion = import.meta.env.VITE_APP_VERSION;
const buildCommitSha = import.meta.env.VITE_APP_COMMIT_SHA;
const buildCommitShort = buildCommitSha.slice(0, 7);
const buildCommitUrl = `https://github.com/Ahoo-Wang/Wow/commit/${buildCommitSha}`;

/** The four places, as links; the top bar's and the phone menu's alike. */
function Places({
  navItems,
  onPick,
}: {
  navItems: readonly NavItem[];
  onPick?: () => void;
}) {
  const { t } = useI18n();
  return navItems.map((item) => (
    <NavLink
      key={item.path}
      to={item.path}
      end={item.path === HOME_PATH}
      className="app-place"
      onClick={onPick}
    >
      {t(item.label)}
    </NavLink>
  ));
}

function LanguageMenu() {
  const { locale, setLocale, t } = useI18n();
  const language = locale === "zh-CN" ? "中文" : "English";
  const label = t("Current language: {language}", { language });

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label={label}
            title={label}
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
          {/* A pick is the whole errand: the menu closes behind it. */}
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

const MODE_WORDS: Record<ColorMode, Message> = {
  system: "Follow system",
  light: "Light",
  dark: "Dark",
};

const MODE_ICONS = { system: Monitor, light: Sun, dark: Moon };

/** Light, dark, or the system's (§6): pinned on this machine once picked. */
function ColorModeMenu() {
  const { t } = useI18n();
  const [mode, setMode] = useColorMode();
  const Icon = MODE_ICONS[mode];
  const label = t("Appearance: {mode}", { mode: t(MODE_WORDS[mode]) });

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label={label}
            title={label}
          />
        }
      >
        <Icon />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuGroup>
          <DropdownMenuLabel>{t("Appearance")}</DropdownMenuLabel>
          <DropdownMenuRadioGroup
            value={mode}
            onValueChange={(value) => {
              if (COLOR_MODES.includes(value as ColorMode))
                setMode(value as ColorMode);
            }}
          >
            {COLOR_MODES.map((one) => (
              <DropdownMenuRadioItem key={one} value={one} closeOnClick>
                {t(MODE_WORDS[one])}
              </DropdownMenuRadioItem>
            ))}
          </DropdownMenuRadioGroup>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** The build this is, linked to its commit. */
function BuildVersion() {
  const { t } = useI18n();
  const commit = t("GitHub commit {commit}", { commit: buildCommitSha });
  return (
    <a
      className="app-version"
      href={buildCommitUrl}
      target="_blank"
      rel="noopener noreferrer"
      aria-label={`${t("Version {version}", { version: buildVersion })}, ${commit}`}
      title={commit}
    >
      <span>v{buildVersion}</span>
      <GitCommitHorizontal aria-hidden="true" />
      <code>{buildCommitShort}</code>
    </a>
  );
}

/**
 * The console's shell (console-redesign.md §4, §6): one top bar with the
 * product and its four places, then the language, the appearance and the
 * build. No sidebar of its own — the workbenches bring their view lists,
 * and a second column beside them was the old console's two rails (W15).
 * On a phone the places fold into a menu under the bar.
 */
export default function App({ navItems }: AppProps) {
  const { t } = useI18n();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuId = useId();
  const placesLabel = t("Primary navigation");

  return (
    <ErrorBoundary>
      <a className="skip-link" href="#main-content">
        {t("Skip to main content")}
      </a>
      <div className="app-shell">
        <header className="app-topbar">
          <Button
            type="button"
            variant="outline"
            size="icon"
            className="app-menu-button"
            aria-expanded={menuOpen}
            aria-controls={menuId}
            aria-label={menuOpen ? t("Close navigation") : t("Open navigation")}
            onClick={() => setMenuOpen((open) => !open)}
          >
            {menuOpen ? <X /> : <Menu />}
          </Button>
          <Link to={HOME_PATH} className="app-brand">
            <img src="/logo.svg" alt="" />
            <span>{t("Compensation console")}</span>
          </Link>
          <nav className="app-places" aria-label={placesLabel}>
            <Places navItems={navItems} />
          </nav>
          <div className="app-topbar-actions">
            <LanguageMenu />
            <ColorModeMenu />
            <BuildVersion />
          </div>
        </header>
        {menuOpen && (
          <nav id={menuId} className="app-places-menu" aria-label={placesLabel}>
            <Places navItems={navItems} onPick={() => setMenuOpen(false)} />
          </nav>
        )}
        <main id="main-content" tabIndex={-1} className="app-content">
          <Outlet />
        </main>
      </div>
    </ErrorBoundary>
  );
}
