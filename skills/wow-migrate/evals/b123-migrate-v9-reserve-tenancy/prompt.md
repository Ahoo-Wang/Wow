---
name: b123-migrate-v9-reserve-tenancy
tags: [behavior, migration, read-only, v9-to-v10, source-rewrite, reservation, multi-tenant, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

只读规划把下游多租户 SaaS 的用户领域从 Wow 9.9.0 迁到 V10（源码目标固定为 Wow `v10` 分支 commit 267683da7d7a9773db417ab12b542d6b3546bf33，尚未发布），给出改写后的代码与 `migration-map.json` 条目，不改文件、不迁移数据。用户用登录名登录，登录页不选租户；另有一个员工工号，只要求在本公司（租户）内不重复。9.x 代码：

```kotlin
@PreparableKey(name = "login_name")
interface LoginNamePrepareKey : PrepareKey<UserIndex>

@PreparableKey(name = "employee_no")
interface EmployeeNoPrepareKey : PrepareKey<String> // 值是用户聚合 ID

data class UserIndex(val userId: String)

fun onCommand(command: CommandMessage<Register>, loginNames: LoginNamePrepareKey, employeeNos: EmployeeNoPrepareKey): Mono<Registered> =
    loginNames.usingPrepare(command.body.loginName, UserIndex(command.aggregateId.id)) { loginOk ->
        require(loginOk) { "login name taken" }
        employeeNos.usingPrepare(command.body.employeeNo, command.aggregateId.id) { noOk ->
            require(noOk) { "employee no taken" }
            Registered(command.body.loginName, command.body.employeeNo).toMono()
        }
    }
```

两个 `PrepareKey` 都存在 9.x 的 MongoDB 里。团队计划把两处都改成 `scope.reserve("user.login-name", loginName)` 和 `scope.reserve("user.employee-no", employeeNo)`，并认为预留数据不用迁移。在回答里给出代码和命令。
