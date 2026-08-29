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

  return (
    <nav aria-label="Primary navigation">
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
                tooltip={item.label}
                render={
                  <NavLink
                    to={item.path}
                    end={item.path === NavItemPaths.Dashboard}
                    aria-label={item.label}
                    onClick={() => {
                      if (isMobile) {
                        setOpenMobile(false);
                      }
                    }}
                  />
                }
              >
                <Icon />
                <span>{item.label}</span>
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

  return (
    <SidebarMenu>
      <SidebarMenuItem>
        <SidebarMenuButton
          size="lg"
          tooltip="Compensation Control Plane"
          render={
            <Link
              to={NavItemPaths.Dashboard}
              aria-label="Wow compensation dashboard"
              onClick={() => setOpenMobile(false)}
            />
          }
        >
          <img className="size-9 object-contain" src="/logo.svg" alt="" />
          <span className="grid min-w-0 text-left leading-tight">
            <span className="truncate font-medium">Compensation</span>
            <span className="truncate text-xs text-sidebar-foreground/70">
              Control Plane
            </span>
          </span>
        </SidebarMenuButton>
      </SidebarMenuItem>
    </SidebarMenu>
  );
}

function NavigationToggle({ mobile }: { mobile: boolean }) {
  const { isMobile, openMobile, state, toggleSidebar } = useSidebar();
  if (mobile !== isMobile) {
    return null;
  }
  const expanded = mobile ? openMobile : state === "expanded";
  const label = mobile
    ? expanded
      ? "Close navigation"
      : "Open navigation"
    : expanded
      ? "Collapse navigation"
      : "Expand navigation";

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
          <span>{expanded ? "Collapse" : "Expand"}</span>
        </SidebarMenuButton>
      </SidebarMenuItem>
    </SidebarMenu>
  );
}

export default function App({ navItems }: AppProps) {
  const location = useLocation();

  const activeTitle = useMemo(
    () =>
      navItems.find((item) => item.path === location.pathname)?.label ??
      "To Retry",
    [location.pathname, navItems],
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
          Skip to main content
        </a>
        <Sidebar
          collapsible="icon"
          role="complementary"
          aria-label="Application sidebar"
        >
          <SidebarHeader>
            <SidebarBrand />
          </SidebarHeader>
          <SidebarContent>
            <SidebarGroup>
              <SidebarGroupLabel>Navigation</SidebarGroupLabel>
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
              <div className="app-build-info" aria-label="Build information">
                <span
                  className="app-build-info-item"
                  title={`Version ${buildVersion}`}
                >
                  <Tag />
                  <span>v{buildVersion}</span>
                </span>
                <a
                  className="app-build-info-item is-commit"
                  href={buildCommitUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  aria-label={`GitHub commit ${buildCommitSha}`}
                  title={`GitHub commit ${buildCommitSha}`}
                >
                  <GitCommitHorizontal />
                  <code>{buildCommitShort}</code>
                </a>
              </div>
              <nav
                className="app-project-links"
                aria-label="Project repositories"
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
