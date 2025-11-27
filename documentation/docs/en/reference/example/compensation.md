# Event Compensation

_[Event Compensation](https://github.com/Ahoo-Wang/Wow/tree/main/compensation)_ is a real-world application built with the _Wow_ framework, designed to handle and recover from data inconsistencies caused by event processing failures.

## Module Structure

| Module                  | Description                                                                                                                              |
|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| wow-compensation-api    | API layer, defines aggregate commands, domain events, and query view models.                                                             |
| wow-compensation-core   | Core layer, contains the core implementation of the compensation mechanism.                                                              |
| wow-compensation-domain | Domain layer, contains aggregate roots and business constraint implementations.                                                          |
| wow-compensation-server | Host service, the application entry point. Responsible for integrating other modules and providing the application entry.               |
| dashboard               | Frontend console, developed with Vue 3 + TypeScript, providing a visual event compensation management interface.                        |

## Features

- **Distributed Automatic Compensation**: Intelligently solves eventual consistency issues
- **Visual Console**: Intuitive monitoring and management of compensation events
- **WeChat Work Notifications**: Timely receive execution failure notifications
- **OpenAPI Interface**: Easy integration and invocation

## Console Screenshot

![Event-Compensation-Dashboard](../../../public/images/compensation/dashboard.png)

## Detailed Documentation

For detailed usage instructions on event compensation, please refer to the [Event Compensation Guide](/guide/event-compensation).
