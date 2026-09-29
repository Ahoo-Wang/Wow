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
} from "lucide-react";
import { Link, Outlet } from "react-router";
import {
  useColorMode,
  useViewNavigation,
  type ColorMode,
} from "@ahoo-wang/wow-view-engine/ui";
import { ErrorBoundary } from "../../components/ErrorBoundary/ErrorBoundary.tsx";
import type { Place } from "../../routes/constants.tsx";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { useI18n, type Message } from "@/i18n.tsx";
import { HOME_PATH } from "@/views/routes.ts";

interface AppProps {
  places: readonly Place[];
}

/** A place as the bar draws it: its word, where it is, whether it is here. */
interface PlaceLink {
  label: Message;
  path: string;
  current: boolean;
}

/**
 * The places, from the engine's navigation (host-integration.md 4.3): each
 * resource's page — or one of its system views — where the route table
 * puts it, and whether the address is on it.
 */
function usePlaces(places: readonly Place[]): PlaceLink[] {
  const navigation = useViewNavigation();
  return places.flatMap(({ label, resource, view }) => {
    const item = navigation.find(({ id }) => id === resource);
    const at =
      view === undefined ? item : item?.views.find(({ id }) => id === view);
    return at ? [{ label, path: at.path, current: at.current }] : [];
  });
}

const buildVersion = import.meta.env.VITE_APP_VERSION;
const buildCommitSha = import.meta.env.VITE_APP_COMMIT_SHA;
const buildCommitShort = buildCommitSha.slice(0, 7);
const buildCommitUrl = `https://github.com/Ahoo-Wang/Wow/commit/${buildCommitSha}`;

/**
 * The four places, as links in the bar: the current one underlined in the
 * primary colour, as the approved mockup draws it; the rest quiet until the
 * pointer finds them.
 */
function Places({ places }: { places: readonly PlaceLink[] }) {
  const { t } = useI18n();
  return places.map((item) => (
    <Link
      key={item.path}
      to={item.path}
      aria-current={item.current ? "page" : undefined}
      className="inline-flex h-full items-center border-b-2 border-transparent px-3 font-medium whitespace-nowrap text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 aria-[current=page]:border-primary aria-[current=page]:text-foreground"
    >
      {t(item.label)}
    </Link>
  ));
}

/** On a phone the places are a menu under one button, the current one checked. */
function PlacesMenu({ places }: { places: readonly PlaceLink[] }) {
  const { t } = useI18n();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button
            type="button"
            variant="outline"
            size="icon"
            className="md:hidden"
            aria-label={t("Open navigation")}
          />
        }
      >
        <Menu />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        <DropdownMenuGroup>
          {places.map((item) => (
            <DropdownMenuItem
              key={item.path}
              aria-current={item.current ? "page" : undefined}
              className="aria-[current=page]:font-medium aria-[current=page]:text-primary"
              render={<Link to={item.path} />}
            >
              {t(item.label)}
            </DropdownMenuItem>
          ))}
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
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

const COLOR_MODES = Object.keys(MODE_WORDS) as ColorMode[];

/**
 * Light, dark, or the system's (§6): the engine paints it (`ViewHost`),
 * pinned on this machine once picked.
 */
function ColorModeMenu() {
  const { t } = useI18n();
  const { mode: painted, setMode } = useColorMode();
  const mode = painted === "host" ? "system" : painted;
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
    <Button
      variant="ghost"
      size="sm"
      className="font-normal text-muted-foreground tabular-nums max-md:hidden"
      aria-label={`${t("Version {version}", { version: buildVersion })}, ${commit}`}
      title={commit}
      render={
        <a href={buildCommitUrl} target="_blank" rel="noopener noreferrer" />
      }
    >
      v{buildVersion}
      <GitCommitHorizontal data-icon="inline-start" />
      <code>{buildCommitShort}</code>
    </Button>
  );
}

/**
 * The console's shell (console-redesign.md §4, §6): one bar with the product
 * and its four places, then the language, the appearance and the build. The
 * bar is the window's frame, in the sidebar's material, so it and a
 * workbench's view list read as one frame round the content (D59). No
 * sidebar of its own — the workbenches bring their view lists, and a second
 * column beside them was the old console's two rails (W15). On a phone the
 * places fold into a menu.
 */
export default function App({ places: named }: AppProps) {
  const { t } = useI18n();
  const placesLabel = t("Primary navigation");
  const places = usePlaces(named);

  return (
    // The engine's theme on the console's own chrome (`fve-tokens`, 4.1):
    // on the shell that wears it, not on `<body>`.
    <div className="fve-tokens bg-background text-foreground">
      <ErrorBoundary>
        <a
          className="fixed top-2 left-2 z-50 -translate-y-[160%] rounded-md bg-popover px-3.5 py-2.5 text-sm font-semibold text-popover-foreground shadow-md focus:translate-y-0 focus:outline-2 focus:outline-offset-2 focus:outline-ring"
          href="#main-content"
        >
          {t("Skip to main content")}
        </a>
        <div className="flex min-h-svh flex-col">
          <header className="sticky top-0 z-10 flex h-13 shrink-0 items-center gap-4 border-b border-sidebar-border bg-sidebar px-4 text-sidebar-foreground">
            {/* A phone's way to the places: a button, whose menu is the
              navigation; one landmark on the page, not two. */}
            <PlacesMenu places={places} />
            <Link
              to={HOME_PATH}
              className="inline-flex items-center gap-2 font-semibold whitespace-nowrap outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
            >
              <img src="/logo.svg" alt="" className="size-5.5" />
              {t("Compensation console")}
            </Link>
            <nav
              aria-label={placesLabel}
              className="flex h-full gap-1 max-md:hidden"
            >
              <Places places={places} />
            </nav>
            <div className="ml-auto flex min-w-0 items-center gap-1.5">
              <LanguageMenu />
              <ColorModeMenu />
              <BuildVersion />
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
      </ErrorBoundary>
    </div>
  );
}
