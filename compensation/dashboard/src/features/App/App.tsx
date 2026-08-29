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
import { useMemo, useState, type ComponentType } from "react";
import { Link, NavLink, Outlet, useLocation } from "react-router";
import { ErrorBoundary } from "../../components/ErrorBoundary/ErrorBoundary.tsx";
import type { NavItem } from "../../routes/constants.tsx";
import { cn } from "@/lib/utils";

interface AppProps {
  navItems: readonly NavItem[];
}

const navIcons: Record<string, ComponentType<{ className?: string }>> = {
  "/dashboard": ChartNoAxesCombined,
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

export default function App({ navItems }: AppProps) {
  const location = useLocation();
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);

  const activeTitle = useMemo(
    () =>
      navItems.find((item) => item.path === location.pathname)?.label ??
      "To Retry",
    [location.pathname, navItems],
  );

  return (
    <ErrorBoundary>
      <div className="app-shell min-h-svh bg-slate-50">
        <a className="skip-link" href="#main-content">
          Skip to main content
        </a>
        <aside
          className={cn("app-sidebar", sidebarCollapsed && "is-collapsed")}
          aria-label="Primary navigation"
        >
          <Link
            className="app-logo"
            to="/dashboard"
            aria-label="Wow compensation dashboard"
          >
            <img src="/logo.svg" alt="Wow" />
          </Link>
          <nav className="app-nav" id="primary-navigation-menu">
            {navItems.map((item) => {
              const Icon = navIcons[item.path] ?? CircleAlert;
              return (
                <NavLink
                  key={item.path}
                  to={item.path}
                  aria-label={item.label}
                  title={item.label}
                  className={({ isActive }) =>
                    cn("app-nav-link", isActive && "is-active")
                  }
                >
                  <Icon className="size-5" />
                  <span>{item.label}</span>
                </NavLink>
              );
            })}
          </nav>
          <button
            type="button"
            className="app-sidebar-toggle"
            aria-controls="primary-navigation-menu"
            aria-expanded={!sidebarCollapsed}
            aria-label={
              sidebarCollapsed ? "Expand navigation" : "Collapse navigation"
            }
            title={
              sidebarCollapsed ? "Expand navigation" : "Collapse navigation"
            }
            onClick={() => setSidebarCollapsed((collapsed) => !collapsed)}
          >
            {sidebarCollapsed ? <PanelLeftOpen /> : <PanelLeftClose />}
            <span>{sidebarCollapsed ? "Expand" : "Collapse"}</span>
          </button>
        </aside>

        <main id="main-content" tabIndex={-1} className="app-main">
          <header className="app-topbar">
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
        </main>
      </div>
    </ErrorBoundary>
  );
}
