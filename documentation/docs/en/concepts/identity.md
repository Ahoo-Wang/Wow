---
title: Identity, Tenancy and Access
description: How an aggregate is addressed, who it belongs to, who the caller is, how access is decided, and where the trust boundary of a deployment lies.
---

# Identity, Tenancy and Access

Every command and every read answers the same questions: which aggregate, in which tenant, belonging to whom, asked by whom, and is that allowed? Wow answers each question once, in one place, so the answers cannot disagree. This page explains where each answer comes from.

## An aggregate is addressed by one value

An aggregate's identity is one value, its **reference**: context, aggregate type, tenant and id, written `context.aggregate/tenant/id`. The reference is decided once, at admission, and nothing changes it afterwards.

- The **tenant is part of the identity.** Two tenants can use the same id without affecting each other: the reference differs, so does the partition it hashes to, and every unique key, versions, request ids and reserved keys, includes the tenant.
- The **id** comes from the route or the message, or is generated at admission for a new aggregate.
- **The command body holds no identity.** It carries business data only. A command function that needs its aggregate's reference reads it from the admitted command.

## Who an aggregate belongs to

An aggregate always belongs to a tenant. It may also belong to an **owner** and a **space**; together they are its **scope**.

- A new aggregate takes its scope from the command that created it.
- Afterwards, only the lifecycle commands that transfer the owner or the space change it.
- Every commit records the scope the aggregate has after it, and the snapshot carries it. A projection, a query policy or the snapshot subscription can read it without replaying history.

An aggregate type declares its **ownership**:

| Ownership | Belongs to | Use it for |
| --- | --- | --- |
| Owner (default) | its tenant, and the owner and space of the command that created it | data that belongs to a person or an account: an order, a cart, a saved view |
| Tenant | its tenant, and a space if it has one | data every caller of the tenant reads: a product, a price list |

## Who the caller is

For each request, the host's caller resolver turns the authenticated principal into a **caller**:

- its **tenant**; a principal without one is in the default tenant;
- its **scope**: the owner and space it acts in;
- its **subject**, the principal's id; none for an anonymous caller;
- whether it may act in **another tenant or scope**, which it then names in the `Wow-Scope` header;
- further **attributes**, such as roles or attributes for access control;
- whether it is a **service** of the system rather than a user.

The caller lives only as long as the request. What a commit keeps of it is the **operator**: the subject, the operator's own tenant, and whether it acted in another tenant or scope. Roles, attributes and personal information never enter the log.

Callers come in two levels. An **owner-level** caller, whose scope has an owner, reads and writes only its own aggregates. A **tenant-level** caller, whose scope has no owner, reads and writes every aggregate of its tenant, for example a member of staff who ships orders. Which actions each may perform is decided by authorization, below.

## Identity comes from the principal

Tenant, owner and space come from the authenticated caller, never from the path or the body of a request. A route does not change with the tenant, and a client cannot claim another tenant by writing it into a URL. The only way to act elsewhere is to be a caller that is allowed to, and to say so in `Wow-Scope`.

Commands that a saga sends, compensations and scheduled commands are not sent by a person. They **inherit** the scope and the operator of the root command, so an aggregate a saga creates belongs to whoever sent the first command, and the log says in whose name it was done. The `causation` of each commit says what triggered it. Admission checked the root command's authority; the kernel does not check an inherited scope against an aggregate that already exists.

## Request facts

Admission copies a few facts about the request into the command, and the commit records them:

- the **application** the request came from, `app_id`: recorded only when the caller resolver authenticated it, for example when CoSec authorized the request with that application's roles. A header the client sets itself is never trusted;
- on the public port, the client's **remote address** and **user agent**;
- any further headers the host lists.

Commands a saga sends inherit only the facts that are meant to travel, such as the application; the remote address and user agent of the first request do not.

## Access

Access is decided at admission, by policies that commands and queries share:

- **Route-level authorization**, such as CoSec in front of the public routes, decides whether the caller may call a route at all.
- **Action-level authorization** decides whether the caller may perform this command on this aggregate. With CoSec, it can authorize every command as its caller.
- **Scope.** The kernel refuses a command on an aggregate outside the caller's scope, and query admission adds the caller's tenant, owner and space to every query ([Read models](./read-models#who-sees-what)).
- **Attribute-based access**, off by default, matches the caller's attributes against each aggregate's tags. An aggregate's tags are applied by a lifecycle command or derived from its state.

The kernel itself does not authorize: by the time a command reaches it, admission has decided.

## The internal port

Each node listens on two ports. The **public port** serves the HTTP API. The **internal port** serves:

- the cluster channel between the nodes of one service, which forwards commands;
- the signals that complete waits, which may come from any service of the system;
- synchronous calls from other services, marked by the `Wow-Call` header;
- reads from other services: the same query and state routes as the public port, for a service that reads this one with its own service identity.

A signal completes a wait and hands its result to the caller, so whoever can send signals can fake the result of any command. The internal port is therefore never exposed outside the cluster, and no public gateway forwards to it. Inside the cluster, every request to it is authenticated:

| Request | Accepted from |
| --- | --- |
| A forwarded command | a node of the same service, with its cluster token |
| A wait signal | a node of the same service, with its cluster token, or another service with its service identity |
| A synchronous call or a read | another service, with its service identity; a cluster token cannot call |

A synchronous call may invoke only domain commands unless the service is configured to allow more. Lifecycle and system commands, such as delete or rebuild a snapshot, must be listed for the calling service explicitly. A call carries the scope, operator and request facts of the root command it acts for, and the target trusts that claim only from a service identity.
