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

An owner's aggregate may also take its owner's id as its own, one per owner, such as a basket: its routes without an id mean the caller's own, and its owner never changes ([One aggregate per owner](../guide/ownership#one-aggregate-per-owner)).

## Who the caller is

For each request, the host's caller resolver turns the authenticated principal into a **caller**:

- its **tenant**; a principal without one is in the default tenant;
- its **scope**: the owner and space it acts in;
- its **subject**, the principal's id; none for an anonymous caller;
- whether it may act in **another tenant or scope**, which it then names in the `Wow-Scope` header;
- further **attributes**, such as roles or attributes for access control;
- whether it is a **service** of the system rather than a user.

The caller lives only as long as the request. What a commit keeps of it is the **operator**: the subject, the operator's own tenant, and whether it acted in another tenant or scope. Roles, attributes and personal information never enter the log.

Callers come in two levels. An **owner-level** caller, whose scope has an owner, reads and writes only its own aggregates. A **tenant-level** caller, whose scope has no owner, reads and writes every aggregate of its tenant, for example a member of staff who ships orders. A space the caller acts in narrows both. An aggregate that belongs to its tenant has no owner to compare, so callers of both levels read it ([Who reads it](../guide/ownership#who-reads-it)). Which actions each may perform is decided by authorization, below.

## Identity comes from the principal

Tenant, owner and space come from the authenticated caller, never from the path or the body of a request. A route does not change with the tenant, and a client cannot claim another tenant by writing it into a URL. The only way to act elsewhere is to be a caller that is allowed to, and to say so in `Wow-Scope`.

Commands that a saga sends, compensations and scheduled commands are not sent by a person. They **inherit** the scope and the operator of the root command, so an aggregate a saga creates belongs to whoever sent the first command, and the log says in whose name it was done. The `causation` of each commit says what triggered it. Admission checked the root command's authority; the kernel does not check an inherited scope against an aggregate that already exists.

## Request facts

Admission copies a few facts about the request into the command, and the commit records them:

- the **application** the request came from, `app_id`: recorded only when the caller resolver authenticated it, for example the app of the scope CoSec decided the request in. A header the client sets itself is never trusted;
- on the public port, the client's **remote address** and **user agent**;
- any further headers the host lists.

Commands a saga sends inherit only the facts that are meant to travel, such as the application; the remote address and user agent of the first request do not.

## Access

Access is decided at admission, by policies that commands and queries share:

- **Route-level authorization**, such as CoSec in front of the public routes, decides whether the caller may call a route at all. Whether a caller must be signed in is decided here and by the caller resolver, which may refuse an unauthenticated request (CoSec answers 401): the scope rules below take whatever caller they are handed, an anonymous one included.
- **Action-level authorization** decides whether the caller may perform this command on this aggregate. With CoSec, it can authorize every command as its caller.
- **Scope.** The kernel refuses a command on an aggregate outside the caller's scope, and query admission adds the caller's tenant and space to every query, and its owner on an aggregate of `Ownership.OWNER` ([Read models](./read-models#who-sees-what)). An aggregate that belongs to its tenant is read by every caller of its tenant and space; keeping it to some roles takes a query policy of the service's own ([Authorize reads](../operate/security#authorize-reads)).
- **Attribute-based access**, off by default, matches the caller's attributes against each aggregate's tags. An aggregate's tags are applied by a lifecycle command or derived from its state.

The kernel itself does not authorize: by the time a command reaches it, admission has decided.

## The internal port

Each node listens on two ports. The **public port** serves the HTTP API. The **internal port** serves:

- the cluster channel between the nodes of one service, which forwards commands;
- the signals that complete waits, which may come from any service of the system;
- synchronous calls from other services, marked by the `Wow-Call` header;
- reads from other services: the same query and state routes as the public port, for a service that reads this one as itself.

A signal completes a wait and hands its result to the caller, so whoever can send signals can fake the result of any command. The internal port is therefore never exposed outside the platform, and no public gateway forwards to it. The inside of the platform is trusted: the internal port takes no token, and a network policy that lets only the platform's own pods reach it is its boundary. Every request to it names the service it comes from in the `Wow-Service` header, the caller's `wow.service`. The header is a statement, not a credential: it catches a misconfiguration, such as a call the service is not listed for, or a forward that reached another service's node.

| Request | Accepted from |
| --- | --- |
| A forwarded command | a node of the same service |
| A wait signal | any service that names itself |
| A synchronous call or a read | any service that names itself, as that service's identity (subject `service:<name>`) |

A synchronous call may invoke every domain command unless the service lists the commands each caller may call (`wow.callers.<service>.commands`). Lifecycle and system commands, such as delete or rebuild a snapshot, must be listed for the calling service explicitly. A call carries the scope, operator and request facts of the root command it acts for, and the target takes that claim only on its internal port, from a service that names itself.
