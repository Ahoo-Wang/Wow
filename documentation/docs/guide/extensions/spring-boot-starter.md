# Spring-Boot-Starter

_Spring-Boot-Starter_ 模块 集成了所有 _Wow_ 扩展，提供了自动装配的能力，使 _Wow_ 框架在 _Spring Boot_ 项目中更加便捷地使用。

::: tip
该模块的公共配置文档请参考 [配置](../../reference/config/basic)。
:::

## 安装

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-spring-boot-starter")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-spring-boot-starter'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-spring-boot-starter</artifactId>
    <version>${wow.version}</version>
</dependency>
```
:::

## Actuator

`WowEndpoint` 提供了通过 `spring-boot-actuator` 暴露 *Wow 编译时元数据*的能力，以便验证 Wow 元数据(`WowMetadata`) 定义的正确性。

::: code-group

```shell [curl]
curl -X 'GET' \
  'http://localhost:8080/actuator/wow' \
  -H 'accept: application/vnd.spring-boot.actuator.v3+json'
```
```json [响应]
{
  "contexts": {
    "transfer-service": {
      "alias": "transfer",
      "scopes": [
        "me.ahoo.wow.example.transfer.server",
        "me.ahoo.wow.example.transfer.domain",
        "me.ahoo.wow.example.transfer"
      ],
      "aggregates": {
        "account": {
          "scopes": [
            "me.ahoo.wow.example.transfer.api"
          ],
          "type": "me.ahoo.wow.example.transfer.domain.Account",
          "tenantId": "(0)",
          "id": null,
          "commands": [
            "me.ahoo.wow.example.transfer.api.Entry",
            "me.ahoo.wow.example.transfer.api.LockAmount",
            "me.ahoo.wow.example.transfer.api.Confirm",
            "me.ahoo.wow.example.transfer.api.FreezeAccount",
            "me.ahoo.wow.example.transfer.api.Prepare",
            "me.ahoo.wow.example.transfer.api.UnfreezeAccount",
            "me.ahoo.wow.example.transfer.api.CreateAccount",
            "me.ahoo.wow.example.transfer.api.UnlockAmount"
          ],
          "events": [
            "me.ahoo.wow.example.transfer.api.Prepared",
            "me.ahoo.wow.example.transfer.api.AmountEntered",
            "me.ahoo.wow.example.transfer.api.AccountUnfrozen",
            "me.ahoo.wow.example.transfer.api.AmountLocked",
            "me.ahoo.wow.example.transfer.api.AccountCreated",
            "me.ahoo.wow.example.transfer.api.AccountFrozen",
            "me.ahoo.wow.example.transfer.api.Confirmed",
            "me.ahoo.wow.example.transfer.api.AmountUnlocked"
          ]
        }
      }
    },
    "compensation-service": {
      "alias": "compensation",
      "scopes": [
        "me.ahoo.wow.compensation"
      ],
      "aggregates": {
        "execution_failed": {
          "scopes": [
            "me.ahoo.wow.compensation.api"
          ],
          "type": null,
          "tenantId": "(0)",
          "id": null,
          "commands": [],
          "events": []
        }
      }
    }
  }
}
```
:::

### 配置

```yaml {6}
management:
  endpoints:
    web:
      exposure:
        include:
          - wow
```