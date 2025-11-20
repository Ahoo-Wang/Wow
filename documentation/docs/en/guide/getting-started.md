# Getting Started

> Use the [Wow Project Template](https://github.com/Ahoo-Wang/wow-project-template) to quickly create a DDD project based on the _Wow_ framework.

## Install Template

[IntelliJ IDEA Project Template Guide](https://www.jetbrains.com/help/idea/saving-project-as-template.html)

[IntelliJ IDEA Configuration Directory Guide](https://www.jetbrains.com/help/idea/directories-used-by-the-ide-to-store-settings-caches-plugins-and-logs.html#config-directory)

- _IDEA_ project template directory: `<IDE config home>/projectTemplates`
    - _Windows_: `C:\Users\<USERNAME>\AppData\Roaming\JetBrains\<PRODUCT><VERSION>\projectTemplates\`
    - _Mac OS_:`~/Library/Application\ Support/JetBrains/<PRODUCT><VERSION/projectTemplates/`
    - _Linux_: `~/.config/JetBrains/<PRODUCT><VERSION>/projectTemplates/`
- Place the template zip package in the _IDEA_ project template directory
    - Template zip package: [wow-project-template.zip](https://gitee.com/AhooWang/wow-project-template/releases/download/v6.5.0/wow-project-template.zip)

## Create Project

> [Create project from template](https://www.jetbrains.com/help/idea/saving-project-as-template.html#create-project-from-template)

![Create Project](../../public/images/getting-started/new-project.png)

- Modify the `settings.gradle.kts` file, change `rootProject.name` to the project name
- Modify `api/{package}/DemoService`
- Modify `domain/{package}/DemoBoundedContext`

::: tip
When IDEA creates a project based on a template, the `gradlew` script may be commented out, you need to copy it again from the template project.
> Enable executable permissions:
```shell
chmod +x ./gradlew
```
:::


## Project Modules

| Module                   | Description                                                                                                                                |
|----------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| api                  | **API Layer**, defines aggregate commands (Command), domain events (Domain Event), and query view models (Query View Model). Acts as the "published language" for communication between modules, while providing detailed API documentation to help developers understand and use the interfaces.             |
| domain               | **Domain Layer**, contains aggregate root and business constraint implementations. The aggregate root serves as the entry point for the domain model, responsible for coordinating domain object operations and ensuring correct execution of business rules. Business constraints include domain object validation rules, domain event processing, etc. The module includes detailed domain model documentation to help the team deeply understand the business logic.                 |
| server               | **Host Service**, the application startup point. Responsible for integrating other modules and providing the application entry point. Involves configuring dependencies, connecting to databases, starting API services, etc. Additionally, the server module provides containerized deployment support, including Docker image building and Kubernetes deployment files, simplifying the deployment process. |
| client               | **Client Library**, uses [fetcher-generator](https://github.com/Ahoo-Wang/fetcher) to automatically generate TypeScript client libraries, providing type-safe API call interfaces for convenient interaction between frontend or other services and the backend.      |
| code-coverage-report | **Test Coverage**, used to generate detailed test coverage reports and verify that coverage meets requirements. Helps development teams understand the comprehensiveness and quality of project testing.                                                                       |
| dependencies         | **Dependency Management**, this module is responsible for managing project dependencies, ensuring that modules can correctly reference and use required external libraries and tools.                                                                              |
| bom                  | **Project BOM (Bill of Materials)**                                                                                                    |
| libs.versions.toml   | **Dependency Version Configuration File**, clearly defines the versions of various libraries in the project, facilitating team collaboration and maintaining version consistency.                                                                                        |
| deploy               | **Kubernetes Deployment Files**, provides configuration files needed to deploy applications on Kubernetes, simplifying the deployment process.                                                                       |
| Dockerfile           | **Server Docker Build Image**, defines the containerized build steps for the application through the Dockerfile, facilitating deployment and scaling.                                                                   |
| document             | **Project Documentation**, includes UML diagrams and context maps, providing team members with a clear understanding of the overall project structure and business logic.                                                                               |

## Install _server_ Dependencies

1. Use _Kafka_ as the messaging engine: command bus and event bus

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-kafka")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-kafka'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-kafka</artifactId>
    <version>${wow.version}</version>
</dependency>
```
:::

2. Use _MongoDB_ as event store and snapshot repository

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-mongo")
implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-mongo'
implementation 'org.springframework.boot:spring-boot-starter-data-mongodb-reactive'
```
```xml [Maven]
  <dependencies>
    <dependency>
        <groupId>me.ahoo.wow</groupId>
        <artifactId>wow-mongo</artifactId>
        <version>${wow.version}</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-mongodb-reactive</artifactId>
    </dependency>
  </dependencies>
```
:::

3. Use [CosId](https://github.com/Ahoo-Wang/CosId) as global and aggregate root ID generator

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.cosid:cosid-mongo")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.cosid:cosid-mongo'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.cosid</groupId>
    <artifactId>cosid-mongo</artifactId>
    <version>${cosid.version}</version>
</dependency>
```
:::

## Application Configuration

```yaml {20,23,29,34}
management:
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include:
          - health
          - wow
          - cosid
          - cosidGenerator
          - cosidStringGenerator
springdoc:
  show-actuator: true
spring:
  application:
    name: <your-service-name>
  data:
    mongodb:
      uri: <mongodb-uri>

cosid:
  machine:
    enabled: true
    distributor:
      type: mongo
  generator:
    enabled: true
wow:
  kafka:
    bootstrap-servers: <kafka-bootstrap-servers>
```

## Start Service

![Start Service](../../public/images/getting-started/run-server.png)

> Access: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

![Swagger-UI](../../public/images/getting-started/swagger-ui.png)

## Domain Modeling

::: tip Aggregate Pattern
In the following examples, we will use the [aggregate pattern](modeling) for modeling.
:::

### Command Aggregate Root

The *command aggregate root* is responsible for receiving command handler functions, executing corresponding business logic, and returning domain events.

```kotlin {2,5}
@Suppress("unused")
@AggregateRoot
class Demo(private val state: DemoState) {

    @OnCommand
    fun onCreate(command: CreateDemo): DemoCreated {
        return DemoCreated(
            data = command.data,
        )
    }

    @OnCommand
    fun onUpdate(command: UpdateDemo): DemoUpdated {
        return DemoUpdated(
            data = command.data
        )
    }
}
```

### State Aggregate Root

The *state aggregate root* is responsible for maintaining aggregate state data, receiving and processing domain events, and changing aggregate state data.

::: warning
The state aggregate root's `setter` accessor is set to `private` to prevent the command aggregate root from directly changing aggregate state data.
:::

```kotlin {3,5}
class DemoState(override val id: String) : Identifier {
    var data: String? = null
        private set

    @OnSourcing
    fun onCreated(event: DemoCreated) {
        data = event.data
    }

    @OnSourcing
    fun onUpdated(event: DemoUpdated) {
        data = event.data
    }
}
```

## Writing Unit Tests

To ensure code quality, we need to write unit tests to verify that aggregate root behavior meets expectations.

### Test Aggregate Root

```kotlin
class DemoSpec : AggregateSpec<Demo, DemoState>({
  on {
    val create = CreateDemo(
      data = "data"
    )
    whenCommand(create) {
      expectNoError()
      expectEventType(DemoCreated::class)
      expectState {
        data.assert().isEqualTo(create.data)
      }
      fork {
        val update = UpdateDemo(
          data = "newData"
        )
        whenCommand(update) {
          expectNoError()
          expectEventType(DemoUpdated::class)
          expectState {
            data.assert().isEqualTo(update.data)
          }
        }
      }
    }
  }
})
```

## CI/CD Pipeline

![Wow-CI-Flow](../../public/images/getting-started/ci-flow.png)

### Test Stage

![test-coverage](../../public/images/getting-started/test-coverage.png)

::: code-group
```shell [Code Style Check]
./gradlew detekt
```
```shell [Domain Model Unit Tests]
./gradlew domain:check
```
```shell [Test Coverage Verification]
./gradlew domain:jacocoTestCoverageVerification
```
:::

### Build Stage

::: code-group
```shell [Generate Deployment Package]
./gradlew server:installDist
```
```shell [Publish Docker Image]
docker login --username=<username> --password=<******> <registry>
docker build -t <registry>/<image>:<tag> server
docker push <registry>/<image>:<tag>
```
:::

### Deploy Stage

```shell [Deploy to Kubernetes]
kubectl apply -f deploy
```

### Pipeline Configuration (Alibaba Cloud Flow)

```yaml
sources:
  wow_project_template_repo:
    type: codeup
    name: Wow Project Template Source
    endpoint: <your-project-repo>
    branch: main
    certificate:
      type: serviceConnection
      serviceConnection: <your-service-connection-id>
stages:
  test:
    name: "Test"
    jobs:
      code_style:
        name: "Check CodeStyle"
        runsOn: public/cn-hongkong
        steps:
          code_style:
            name: "Code Style Check"
            step: "JavaBuild"
            runsOn: public/
            with:
              jdkVersion: "17"
              run: ./gradlew detekt

      test:
        name: "Check Domain"
        runsOn: public/cn-hongkong
        steps:
          test:
            name: "Check Domain"
            step: "GradleUnitTest"
            with:
              jdkVersion: "17"
              run: ./gradlew domain:check
              reportDir: "domain/build/reports/tests/test"
              reportIndex: "index.html"
          coverage:
            name: "Check CodeCoverage"
            step: "JaCoCo"
            with:
              jdkVersion: "17"
              run: ./gradlew domain:jacocoTestCoverageVerification
              reportDir: "domain/build/reports/jacoco/test/html"
  build:
    name: "Build"
    jobs:
      build:
        name: "Build Server And Push Image"
        runsOn: public/cn-hongkong
        steps:
          build:
            name: "Build Server"
            step: "JavaBuild"
            with:
              jdkVersion: "17"
              run: ./gradlew server:installDist
          publish_image:
            name: "Push Image"
            step: "ACRDockerBuild"
            with:
              artifact: "image"
              dockerfilePath: "server/Dockerfile"
              dockerRegistry: "<your-docker-registry—url>"
              dockerTag: ${DATETIME}
              region: "cn-hangzhou"
              serviceConnection: "<your-service-connection-id>"
  deploy:
    name: "Deploy"
    jobs:
      deploy:
        name: "Deploy"
        runsOn: public/cn-hongkong
        steps:
          deploy:
            name: "Deploy"
            step: "KubectlApply"
            with:
              skipTlsVerify: false
              kubernetesCluster: "<your-kubernetes-id>"
              useReplace: false
              namespace: "dev"
              kubectlVersion: "1.22.9"
              yamlPath: "deploy"
              skipVariableVerify: false
              variables:
                - key: IMAGE
                  value: $[stages.build.build.publish_image.artifacts.image]
                - key: REPLICAS
                  value: 2
                - key: SERVICE_NAME
                  value: demo-service
```
