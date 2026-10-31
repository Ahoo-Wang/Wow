---
title: The Analytics Catalog Console
description: Deploy the analytics catalog's console, sign the data owners in, and use it to review sources, publish datasets, approve what needs an approver and act on a dataset's rows.
---

# The Analytics Catalog Console

The analytics catalog is the control plane of the analytics platform: it knows the warehouse's tables as sources, defines datasets as wide tables over them, and publishes each version of a dataset as a ClickHouse view that every service node serves ([Platform Services](../migrate/platform#dataset-registry-→-analytics-catalog)). Its console is where the data owners do that work: review the sources, draft and publish the datasets, approve what needs an approver, and read the rows of every dataset served. This page explains how the console fits together, how to deploy it, who may do what, and how to work with it. The analytics catalog is incubating, and not yet published as a release artifact.

## How it fits together

- **The catalog serves the console.** The console is a single-page application the catalog service serves as static files, from its distribution's `ui/` folder. The browser talks to the catalog alone: the catalog's own routes (`/analytics-catalog/…`) and the dataset routes every Wow node serves (`/wow/dataset/…`).
- **The catalog decides, the console offers.** Every change is one of the catalog's commands, which the catalog authorizes and checks. The console reads the catalog's settings first (below) to offer only what this catalog does, and says why an action is not offered; it never decides anything the catalog would not.
- **A dataset's rows are the data plane's.** The console reads them as any reader does, through the node's query policies: each caller reads the rows of its own tenant, owner and space, each value masked as its sources say. Reading the rows needs no catalog role.
- **An action on a row goes to the service that owns the aggregate.** A row's reference field holds an aggregate's id; the console offers that aggregate's commands, and the catalog relays the one chosen to its owning service in the data owner's name, so that service authorizes it, not the catalog. The catalog reaches a context only through a peer client described by its Manifest (`wow.peers.<context>`, with `contract: manifest`, and `mode: user` to send the data owner's own credential); a context without one offers no actions.

## Deploy it

[`deploy/analytics-catalog`](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/analytics-catalog) holds Kubernetes manifests for two nodes on MongoDB (or PostgreSQL, through its `postgres` component), built on the same choices as the eShop services ([Deployment Model](./topology)):

1. Build the console, then the server's distribution, which copies the console's build into its `ui/` folder, then the image.
2. Provide MongoDB and the ClickHouse warehouse the services' Sinks write to. The catalog creates no table in ClickHouse; its account needs `CREATE DATABASE`, `CREATE VIEW` and `DROP VIEW` on the datasets' database, `wow_datasets`.
3. Run the nodes in the `cosec` profile, behind the CoSec gateway: the gateway verifies each data owner's access token and forwards the identity it verified (`CoSec-Identity`), which the service reads, holding no secret. The service believes that header, so only the gateway may reach its HTTP port; the manifests' network policy says so ([Secure a Deployment](./security#put-the-gateway-in-front)).

The console uses a hash router (`/#/sources`, `/#/datasets`), so the server needs no fallback for its pages.

## Who may do what

The data owner signs in to the console by pasting a CoSec access token, which the console sends with every request (`Authorization: Bearer`, with its application, `CoSec-App-Id: analytics-catalog-console`). A token refused by the gateway or the service (401) signs the console out, and the sign-in page says why, expired or refused. The catalog's callers are of the platform's tenant and have no owner; the catalog reads their roles from the identity:

| Role | May |
| --- | --- |
| `analytics.catalog.viewer` | Read the sources and the datasets |
| `analytics.catalog.editor` | An analyst's work: draft, revise and review the protection of the sources; draft, revise, publish, roll back and retire the datasets |
| `analytics.catalog.approver` | Mark a source reviewed; approve a lower protection; approve a source for datasets; approve or refuse a publication |
| `analytics.catalog.admin` | All an editor does, mark a source reviewed, and the catalog's own commands: sync a source, recompile a dataset, drop a retired dataset's views |

- **Reading the catalog** needs one of the four roles. A caller with none reads nothing: its queries of the sources and datasets are refused with 403 `wow.auth.forbidden`, and the console says so. This guards the control plane, the definitions, columns, protections and lineage, not the datasets' rows.
- **An approval is the approver's alone.** An admin cannot approve a lower protection, a source or a publication: these are the privileged reviews, kept apart from whoever manages the catalog.
- **Where CoSec decides** (`wow.cosec.authorizer.enabled: true`), its policies decide every command, and the catalog's roles decide nothing. The console cannot read a policy ahead, so it then offers every action, and a refusal comes back as the service's answer, `wow.auth.forbidden`.

## Approvals

Two switches decide what takes an approver, both off by default:

| Setting | When on |
| --- | --- |
| `wow.analytics.catalog.require-source-approval` | A dataset reads only the sources an approver approved for datasets, besides being reviewed or adopted |
| `wow.analytics.catalog.require-publication-approval` | Every publication waits for an approver before queries read it |

Some changes take an approver whatever the switches say:

- **A publication that computes from sensitive values or lowers a protection** waits for an approver; the dataset's detail says why it waits.
- **A lower protection of a Wow table's values**, which its `wow_catalog` row now asks for, applies only once an approver accepts it. A Wow table's protection can otherwise only be tightened.
- **An analyst's source becomes reviewed** only by an approver (or an admin), once its tenant column, or its being a platform dimension, is declared and every string column's protection reviewed.

A publication that waits is in _Awaiting approval_, where an approver approves it, which compiles and publishes it, or refuses it. A refused or waiting publication changes nothing that queries read: the version serving goes on serving.

## The settings it reads

`GET /analytics-catalog/settings` answers what the console needs to know of this catalog, as any caller of it reads it:

```json
{
  "connection": "default",
  "requireSourceApproval": false,
  "requirePublicationApproval": false,
  "rolesDecide": true
}
```

- `connection` is the warehouse connection a source's id starts with (`<connection>:<database>:<table>`), `wow.analytics.catalog.connection`. The console drafts a source on it until another is typed.
- `requireSourceApproval` and `requirePublicationApproval` are the two switches above. The console shows _Awaiting approval_ for sources only when the first is on, and says before a publication when every one waits.
- `rolesDecide` is whether the catalog's roles decide the commands: true without CoSec's authorizer. Then an action the caller's roles rule out is offered disabled, naming the role it takes, and so is one the record rules out, saying why. False, CoSec's policies decide, and the console offers the actions as the record allows.

The console reads them once a caller is in force.

## Work with the console

- **Sources** (the home page). The warehouse's tables and views: _All_, _To review_ (drafts), _Awaiting approval_ (only where sources take an approver), _Wow tables_, _Analysts' tables_, _Tables gone_ and _By status_. A source's detail shows its connection, table, status and scope, a Wow table's `wow_catalog` row, the lower protection pending an approver and the rows refused. For an analyst's table it edits the scope (the tenant, owner and space columns, or a platform dimension) and the references (`column=context.aggregate`), and for every source its columns' protection. Its actions: mark reviewed, approve for datasets, approve a lower protection.
- **Datasets.** _All_, _Needs revision_ (with whether each still serves its published version), _Awaiting approval_, _Published_, _Drafts_, _Retired_ and _By status_. A dataset's detail says why it needs revision or an approver, edits its definition (JSON, a mistake said by its line and column) and title, and lists the published versions, the one serving first, each with its view, sources, fields and DDL. Its actions: publish, approve or refuse a publication, roll back to an earlier version (not the one serving, nor one never published), retire.
- **Rows.** A served dataset's rows, as any reader of it reads them. A reference field offers the commands of the aggregate it holds the id of, each with a form of its body. A node without a dataset query backend, such as a local run on the memory backend, serves none, and the page says so.
- **Dashboards.** Boards whose panels are views of any dataset served, side by side. They are kept in this browser: nobody else sees them.
- **Draft.** An analyst's table drafted as a source, by its connection, database and table, and a dataset drafted from its definition; each opens on its page once drafted, where it is reviewed and published.

Every action asks for confirmation, works on one record or a selection, waits for the record's snapshot (`Wow-Wait: snapshot`) and reports its outcome. A refusal is said in the service's words, with its code: `analytics.catalog.incomplete`, `analytics.catalog.definition-invalid`, `wow.auth.forbidden`, and so on. The lists are read again every 15 seconds and right after an action: the catalog streams no changes of its snapshots.

The console speaks English and Chinese, the first of the browser's languages it speaks, until the menu at the top right picks one.

## Run it locally

Without the `cosec` profile, a catalog node in the development profile (`dev`) reads its caller from headers instead of CoSec: `Wow-User`, `Wow-Tenant` and `Wow-Roles`. The console's sign-in page names such a caller and keeps it in this browser. A deployment refuses a caller given so, and the console signs it out and says so. The console's [README](https://github.com/Ahoo-Wang/Wow/tree/main/platform/analytics/catalog/console) has the commands to build it, run it against a local catalog and run its end-to-end suite.
