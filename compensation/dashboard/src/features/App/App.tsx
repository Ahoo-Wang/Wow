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

import classes from "./App.module.css";
import { Layout, Menu, type MenuProps, Watermark, theme } from "antd";
import { GithubOutlined, CodepenOutlined } from "@ant-design/icons";
import { ErrorBoundary } from "../../components/ErrorBoundary/ErrorBoundary.tsx";
import { Link, Outlet, useLocation } from "react-router";
import type { NavItem } from "../../routes/constants.tsx";
import * as React from "react";
import "@ahoo-wang/fetcher-eventstream";
const { Header, Content, Footer } = Layout;

type MenuItem = Required<MenuProps>["items"][number];

interface AppProps {
  navItems: NavItem[];
}

/**
 * 创建外部链接菜单项
 * @param key 唯一标识
 * @param label 显示文本
 * @param url 链接地址
 * @param icon 图标
 * @param style 样式
 * @returns MenuItem
 */
const createExternalLinkItem = (
  key: string,
  label: string,
  url: string,
  icon?: React.ReactNode,
  style?: React.CSSProperties,
): MenuItem => ({
  key,
  icon,
  style,
  label: (
    <a href={url} target="_blank" rel="noopener noreferrer">
      {label}
    </a>
  ),
});

/**
 * 创建导航菜单项
 * @param navItem 导航项配置
 * @returns MenuItem
 */
const createNavItem = (navItem: NavItem): MenuItem => ({
  key: navItem.path,
  icon: navItem.icon,
  label: <Link to={navItem.path}>{navItem.label}</Link>,
});

export default function App({ navItems }: AppProps) {
  const location = useLocation();

  // 构建菜单项数组
  const menuItems: MenuItem[] = [
    // 导航菜单项
    ...navItems.map(createNavItem),

    // 外部链接菜单项
    createExternalLinkItem(
      "github",
      "GitHub",
      "https://github.com/Ahoo-Wang/Wow",
      <GithubOutlined />,
      { marginLeft: "auto" },
    ),
    createExternalLinkItem(
      "gitee",
      "Gitee",
      "https://gitee.com/AhooWang/Wow",
      <CodepenOutlined />,
    ),
  ];
  const {
    token: { colorBgContainer, borderRadiusLG },
  } = theme.useToken();
  return (
    <ErrorBoundary>
      <Layout style={{ minHeight: "100vh" }}>
        <Header style={{ display: "flex", alignItems: "center" }}>
          <div className={classes.logo}>
            <Link to={"/to-retry"}>
              <img src={"/logo.svg"} alt={"logo"} />
            </Link>
          </div>
          <Menu
            theme="dark"
            mode="horizontal"
            selectedKeys={[location.pathname]}
            style={{ flex: 1 }}
            items={menuItems}
          />
        </Header>
        <Content
          style={{
            padding: "24px",
            background: colorBgContainer,
            borderRadius: borderRadiusLG,
          }}
        >
          <Watermark content={["Wow", "Compensation Dashboard"]}>
            <Outlet />
          </Watermark>
        </Content>
        <Footer style={{ textAlign: "center" }}>
          Wow Compensation Dashboard ©{new Date().getFullYear()}
        </Footer>
      </Layout>
    </ErrorBoundary>
  );
}
