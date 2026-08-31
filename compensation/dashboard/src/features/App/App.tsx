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
  CircleAlert,
  CircleCheck,
  CircleX,
  ChartNoAxesCombined,
  Clock3,
  GitCommitHorizontal,
  Languages,
  PanelLeftClose,
  PanelLeftOpen,
  Play,
  RefreshCcw,
  Tag,
} from "lucide-react";
import { useMemo, type ComponentType, type CSSProperties } from "react";
import { Link, NavLink, Outlet, useLocation } from "react-router";
import { ErrorBoundary } from "../../components/ErrorBoundary/ErrorBoundary.tsx";
import { NavItemPaths, type NavItem } from "../../routes/constants.tsx";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  SidebarTrigger,
  useSidebar,
} from "@/components/ui/sidebar";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useI18n } from "@/i18n.tsx";
import { Button } from "@/components/ui/button";

interface AppProps {
  navItems: readonly NavItem[];
}

const navIcons: Record<string, ComponentType<{ className?: string }>> = {
  [NavItemPaths.Dashboard]: ChartNoAxesCombined,
  "/to-retry": RefreshCcw,
  "/executing": Play,
  "/next-retry": Clock3,
  "/non-retryable": CircleX,
  "/succeeded": CircleCheck,
  "/unrecoverable": CircleAlert,
};

const buildVersion = import.meta.env.VITE_APP_VERSION;
const buildCommitSha = import.meta.env.VITE_APP_COMMIT_SHA;
const buildCommitShort = buildCommitSha.slice(0, 7);
const buildCommitUrl = `https://github.com/Ahoo-Wang/Wow/commit/${buildCommitSha}`;

function PrimaryNavigation({
  navItems,
  pathname,
}: {
  navItems: readonly NavItem[];
  pathname: string;
}) {
  const { isMobile, setOpenMobile } = useSidebar();
  const { t } = useI18n();

  return (
    <nav aria-label={t("Primary navigation")}>
      <SidebarMenu>
        {navItems.map((item) => {
          const Icon = navIcons[item.path] ?? CircleAlert;
          const isActive =
            item.path === NavItemPaths.Dashboard
              ? pathname === NavItemPaths.Dashboard
              : pathname === item.path || pathname.startsWith(`${item.path}/`);

          return (
            <SidebarMenuItem key={item.path}>
              <SidebarMenuButton
                isActive={isActive}
                size="lg"
                tooltip={t(item.label)}
                render={
                  <NavLink
                    to={item.path}
                    end={item.path === NavItemPaths.Dashboard}
                    aria-label={t(item.label)}
                    onClick={() => {
                      if (isMobile) {
                        setOpenMobile(false);
                      }
                    }}
                  />
                }
              >
                <Icon />
                <span>{t(item.label)}</span>
              </SidebarMenuButton>
            </SidebarMenuItem>
          );
        })}
      </SidebarMenu>
    </nav>
  );
}

function SidebarBrand() {
  const { setOpenMobile } = useSidebar();
  const { t } = useI18n();

  return (
    <SidebarMenu>
      <SidebarMenuItem>
        <SidebarMenuButton
          size="lg"
          tooltip={t("Compensation Control Plane")}
          render={
            <Link
              to={NavItemPaths.Dashboard}
              aria-label={t("Wow compensation dashboard")}
              onClick={() => setOpenMobile(false)}
            />
          }
        >
          <img className="size-9 object-contain" src="/logo.svg" alt="" />
          <span className="grid min-w-0 text-left leading-tight">
            <span className="truncate font-medium">{t("Compensation")}</span>
            <span className="truncate text-xs text-sidebar-foreground/70">
              {t("Control Plane")}
            </span>
          </span>
        </SidebarMenuButton>
      </SidebarMenuItem>
    </SidebarMenu>
  );
}

function NavigationToggle({ mobile }: { mobile: boolean }) {
  const { isMobile, openMobile, state, toggleSidebar } = useSidebar();
  const { t } = useI18n();
  if (mobile !== isMobile) {
    return null;
  }
  const expanded = mobile ? openMobile : state === "expanded";
  const label = mobile
    ? expanded
      ? t("Close navigation")
      : t("Open navigation")
    : expanded
      ? t("Collapse navigation")
      : t("Expand navigation");

  if (mobile) {
    return (
      <SidebarTrigger
        aria-expanded={expanded}
        aria-label={label}
        title={label}
      />
    );
  }

  const Icon = expanded ? PanelLeftClose : PanelLeftOpen;
  return (
    <SidebarMenu>
      <SidebarMenuItem>
        <SidebarMenuButton
          type="button"
          aria-expanded={expanded}
          aria-label={label}
          tooltip={label}
          onClick={toggleSidebar}
        >
          <Icon />
          <span>{expanded ? t("Collapse") : t("Expand")}</span>
        </SidebarMenuButton>
      </SidebarMenuItem>
    </SidebarMenu>
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
            className="app-language-trigger"
            aria-label={label}
          />
        }
      >
        <Languages />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuRadioGroup
          value={locale}
          onValueChange={(value) => {
            if (value === "en" || value === "zh-CN") {
              setLocale(value);
            }
          }}
        >
          <DropdownMenuRadioItem value="en">English</DropdownMenuRadioItem>
          <DropdownMenuRadioItem value="zh-CN">中文</DropdownMenuRadioItem>
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

export default function App({ navItems }: AppProps) {
  const location = useLocation();
  const { t } = useI18n();

  const activeTitle = useMemo(
    () =>
      t(
        navItems.find((item) => item.path === location.pathname)?.label ??
          "To Retry",
      ),
    [location.pathname, navItems, t],
  );

  return (
    <ErrorBoundary>
      <SidebarProvider
        style={
          {
            "--sidebar-width": "11rem",
            "--sidebar-width-icon": "3.5rem",
          } as CSSProperties
        }
      >
        <a className="skip-link" href="#main-content">
          {t("Skip to main content")}
        </a>
        <Sidebar
          collapsible="icon"
          role="complementary"
          aria-label={t("Application sidebar")}
        >
          <SidebarHeader>
            <SidebarBrand />
          </SidebarHeader>
          <SidebarContent>
            <SidebarGroup>
              <SidebarGroupLabel>{t("Navigation")}</SidebarGroupLabel>
              <SidebarGroupContent>
                <PrimaryNavigation
                  navItems={navItems}
                  pathname={location.pathname}
                />
              </SidebarGroupContent>
            </SidebarGroup>
          </SidebarContent>
          <SidebarFooter>
            <NavigationToggle mobile={false} />
          </SidebarFooter>
        </Sidebar>

        <SidebarInset id="main-content" tabIndex={-1}>
          <header className="app-topbar">
            <NavigationToggle mobile />
            <h1>{activeTitle}</h1>
            <div className="app-topbar-actions">
              <div className="app-build-info" aria-label={t("Build information")}>
                <span
                  className="app-build-info-item"
                  title={t("Version {version}", { version: buildVersion })}
                >
                  <Tag />
                  <span>v{buildVersion}</span>
                </span>
                <a
                  className="app-build-info-item is-commit"
                  href={buildCommitUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  aria-label={t("GitHub commit {commit}", {
                    commit: buildCommitSha,
                  })}
                  title={t("GitHub commit {commit}", {
                    commit: buildCommitSha,
                  })}
                >
                  <GitCommitHorizontal />
                  <code>{buildCommitShort}</code>
                </a>
              </div>
              <LanguageMenu />
              <nav
                className="app-project-links"
                aria-label={t("Project repositories")}
              >
                <a
                  className="app-project-link is-github"
                  href="https://github.com/Ahoo-Wang/Wow"
                  target="_blank"
                  rel="noopener noreferrer"
                  aria-label="GitHub"
                  title="GitHub"
                >
                  <img src="/github.svg" alt="" />
                </a>
                <a
                  className="app-project-link is-gitee"
                  href="https://gitee.com/AhooWang/Wow"
                  target="_blank"
                  rel="noopener noreferrer"
                  aria-label="Gitee"
                  title="Gitee"
                >
                  <img src="/gitee.svg" alt="" />
                </a>
              </nav>
            </div>
          </header>
          <div className="app-content">
            <Outlet />
          </div>
        </SidebarInset>
      </SidebarProvider>
    </ErrorBoundary>
  );
}
