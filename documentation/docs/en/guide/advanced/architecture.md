# Architecture

This document provides a detailed introduction to the overall architecture design, core component relationships, and processing flows of the Wow framework.

## Overall Architecture

<center>

![Wow-Architecture](../../../public/images/Architecture.svg)
</center>

## Core Components

### Component Relationship Diagram

```mermaid
flowchart TB
    subgraph Client["Client"]
        API[REST API]
    end
    
    subgraph CommandSide["Command Side"]
        CG[CommandGateway]
        CB[CommandBus]
        CP[CommandProcessor]
        AR[Aggregate Root]
    end
    
    subgraph EventSourcing["Event Sourcing"]
        ES[EventStore]
        SR[SnapshotRepository]
    end
    
    subgraph EventSide["Event Side"]
        EB[EventBus]
        SEB[StateEventBus]
        EP[EventProcessor]
        PP[ProjectionProcessor]
        SP[SagaProcessor]
    end
    
    subgraph QuerySide["Query Side"]
        QS[QueryService]
        RM[(Read Model)]
    end
    
    API --> CG
    CG --> CB
    CB --> CP
    CP --> AR
    AR --> ES
    AR --> SR
    AR --> EB
    EB --> EP
    EB --> SP
    SEB --> PP
    PP --> RM
    QS --> RM
```

### Component Description

| Component | Responsibility | Interface |
|------|------|------|
| CommandGateway | Command entry point, supports wait strategies | `CommandGateway` |
| CommandBus | Command transport channel | `CommandBus` |
| CommandProcessor | Command processing, loads aggregate root and executes | `CommandProcessor` |
| EventStore | Event persistent storage | `EventStore` |
| SnapshotRepository | Snapshot storage, optimizes aggregate loading | `SnapshotRepository` |
| EventBus | Domain event transport channel | `EventBus` |
| StateEventBus | State event transport channel | `StateEventBus` |
| EventProcessor | Event processor base class | `EventProcessor` |
| ProjectionProcessor | Projection processing, updates read model | `ProjectionProcessor` |
| SagaProcessor | Saga orchestration, handles distributed transactions | `SagaProcessor` |
| QueryService | Query service, reads from read model | `QueryService` |

## Command Processing Flow

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant CG as CommandGateway
    participant CB as CommandBus
    participant CP as CommandProcessor
    participant SR as SnapshotRepository
    participant ES as EventStore
    participant AR as Aggregate Root
    participant EB as EventBus
    
    Client->>CG: Send Command
    CG->>CB: Route Command
    CB->>CP: Dispatch Command
    
    CP->>SR: Load Snapshot
    SR-->>CP: Return Snapshot
    CP->>ES: Load Incremental Events
    ES-->>CP: Return Events
    CP->>AR: Rebuild Aggregate State
    
    CP->>AR: Execute Command
    AR-->>CP: Return Domain Events
    
    CP->>ES: Append Events
    ES-->>CP: Confirm Storage
    CP->>SR: Save Snapshot
    CP->>EB: Publish Events
    
    EB-->>CG: Notify Completion
    CG-->>Client: Return Result
```

### Flow Description

1. **Command Sending**: Client sends command through CommandGateway
2. **Command Routing**: CommandBus routes to corresponding processor based on aggregate type
3. **Aggregate Loading**: Rebuild aggregate state from snapshot and event store
4. **Command Execution**: Aggregate root processes command, produces domain events
5. **Event Persistence**: Events appended to EventStore
6. **Snapshot Saving**: Save snapshot based on strategy
7. **Event Publishing**: Domain events published to EventBus

## Event Processing Flow

```mermaid
sequenceDiagram
    autonumber
    participant EB as EventBus
    participant EP as EventProcessor
    participant PP as ProjectionProcessor
    participant SP as SagaProcessor
    participant RM as Read Model
    participant CG as CommandGateway
    
    EB->>EP: Dispatch Event
    
    par Projection Processing
        EP->>PP: Process Event
        PP->>RM: Update Read Model
    and Saga Processing
        EP->>SP: Process Event
        SP->>CG: Send Follow-up Command
    end
```

## CQRS Architecture Implementation

The Wow framework implements a complete CQRS (Command Query Responsibility Segregation) architecture:

```mermaid
flowchart LR
    subgraph WriteModel["Write Model"]
        C[Command]
        AR[Aggregate Root]
        ES[(Event Store)]
    end
    
    subgraph ReadModel["Read Model"]
        Q[Query]
        PP[Projection Processor]
        RM[(Read Database)]
    end
    
    C --> AR
    AR --> ES
    ES -->|Events| PP
    PP --> RM
    Q --> RM
```

### Write Model Responsibilities

- Process business commands
- Execute business rule validation
- Produce domain events
- Maintain transactional consistency

### Read Model Responsibilities

- Respond to query requests
- Provide optimized data views
- Support complex query conditions
- Eventual consistency

## Event Sourcing Implementation

### Event Storage Structure

```mermaid
erDiagram
    AGGREGATE_ROOT ||--o{ EVENT_STREAM : has
    EVENT_STREAM ||--|{ DOMAIN_EVENT : contains
    AGGREGATE_ROOT ||--o| SNAPSHOT : has
    
    AGGREGATE_ROOT {
        string aggregateId PK
        string contextName
        string aggregateName
    }
    
    EVENT_STREAM {
        string id PK
        string aggregateId FK
        int version
        string requestId
        timestamp createTime
    }
    
    DOMAIN_EVENT {
        string name
        string revision
        string body
    }
    
    SNAPSHOT {
        string aggregateId PK
        int version
        string state
        timestamp snapshotTime
    }
```

### State Rebuild Flow

```mermaid
flowchart TD
    A[Load Aggregate] --> B{Has Snapshot?}
    B -->|Yes| C[Load Snapshot]
    B -->|No| D[Create Initial State]
    C --> E[Get Snapshot Version]
    E --> F[Load Incremental Events]
    D --> G[Load All Events]
    F --> H[Apply Events]
    G --> H
    H --> I[Return Aggregate Root]
```

## Extension Points

The Wow framework provides rich extension points:

| Extension Point | Description | Use Case |
|--------|------|---------|
| `CommandBus` | Command bus implementation | Custom message transport |
| `EventBus` | Event bus implementation | Custom event distribution |
| `EventStore` | Event store implementation | Custom storage backend |
| `SnapshotRepository` | Snapshot storage implementation | Custom snapshot strategy |
| `IdGenerator` | ID generator | Custom ID format |
| `WaitStrategy` | Wait strategy | Custom synchronization mechanism |

## Design Principles

### Single Responsibility

Each component is responsible for only one concern, complex functionality is achieved through composition.

### Dependency Inversion

Core modules depend on abstract interfaces, concrete implementations are provided through extension modules.

### Open-Closed Principle

Through extension point mechanism, extend functionality without modifying core code.

### Event-Driven

System is decoupled through events, supporting asynchronous processing and eventual consistency.