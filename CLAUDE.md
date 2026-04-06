## Approach
- Think before acting. Read existing files before writing code.
- Be concise in output but thorough in reasoning.
- Prefer editing over rewriting whole files.
- Do not re-read files you have already read unless the file may have changed.
- Test your code before declaring done.
- No sycophantic openers or closing fluff.
- Keep solutions simple and direct.
- User instructions always override this file.

# GPLink - JetLinks IoT Platform

> **For Claude Code**: This file helps you quickly understand the architecture, critical setup requirements, and development workflow of this repository. Read this first before making changes.

## Project Overview

GPLink is a full-stack IoT platform based on **JetLinks Community Edition 2.10** - a unified device management and data processing system supporting multiple IoT protocols (MQTT, CoAP, HTTP, TCP, UDP, WebSocket).

**Monorepo Structure**:
- `/front` - Vue 3 + Vite + TypeScript frontend application
- `/back/jetlinks-community-2.10/jetlinks-community-2.10/` - Spring Boot 3.4.7 + Java 17 microservices backend

**Core Purpose**: Provide enterprise-grade IoT device lifecycle management, real-time data processing, rule engine automation, and multi-protocol gateway capabilities.

## Architecture & Tech Stack

### Frontend (`/front`)
- **Framework**: Vue 3.3 + Vite 4.5 + TypeScript 5.8
- **State Management**: Pinia 2.x
- **UI Library**: Ant Design Vue 4.x
- **Reactive Programming**: RxJS for async operations
- **Module Federation**: Git submodules for feature modules
- **Routing**: Vue Router 4.x
- **i18n**: Multi-language support (EN/ZH)

### Backend (`/back`)
- **Framework**: Spring Boot 3.4.7 + Spring WebFlux (reactive)
- **Java Version**: 17
- **Reactive Stack**: Project Reactor + R2DBC
- **Database**: PostgreSQL (via R2DBC)
- **Search**: ElasticSearch integration
- **Networking**: Netty-based multi-protocol gateway
- **Build Tool**: Maven

### Key Architectural Patterns
1. **Reactive Programming**: End-to-end reactive streams (RxJS frontend ↔ Reactor backend)
2. **Modular Plugin Architecture**: Component-based extensibility
3. **Git Submodules**: Frontend UI modules are separate git repositories
4. **Multi-Protocol Gateway**: Unified abstraction for MQTT/CoAP/HTTP/TCP/UDP/WebSocket
5. **Event-Driven**: Message-driven microservices communication

## Critical Setup Requirements

### ⚠️ Frontend Setup Sequence (MANDATORY)

The frontend **REQUIRES** git submodules to function. Follow this exact sequence:

```bash
cd front

# 1. Configure SSH keys for GitHub (REQUIRED - submodules use SSH URLs)
# Verify with: ssh -T git@github.com

# 2. Initialize git submodules (FIRST STEP - NOT OPTIONAL)
pnpm modules:init

# 3. Install dependencies
pnpm install

# 4. Update TypeScript configuration (after submodule changes)
pnpm update:tsconfig
```

**Critical Requirements**:
- ✅ SSH keys configured for GitHub (submodules fail with HTTPS)
- ✅ Node.js ≥ 18
- ✅ pnpm package manager
- ✅ Submodules initialized BEFORE `pnpm install`

**Symptom of Missing Submodules**: "Systems initialized 0 menus" error or empty `/front/src/modules/` directory.

### Backend Setup

```bash
cd back/jetlinks-community-2.10/jetlinks-community-2.10

# Build all modules
mvn clean install

# Run standalone application
java -jar jetlinks-standalone/target/jetlinks-standalone-*.jar
```

**Requirements**:
- ✅ Java 17 JDK
- ✅ Maven 3.8+
- ✅ PostgreSQL database (configured in application.yml)
- ✅ ElasticSearch (optional, for advanced search)

## Development Commands

### Frontend (`/front`)

| Command | Purpose |
|---------|---------|
| `pnpm modules:init` | **Initialize git submodules (RUN FIRST)** |
| `pnpm install` | Install all dependencies |
| `pnpm dev` | Start dev server on http://localhost:9100 |
| `pnpm build` | Production build → `dist/` |
| `pnpm build:test` | Test environment build |
| `pnpm build:dev` | Dev environment build |
| `pnpm test` | Run Playwright E2E tests |
| `pnpm update:tsconfig` | Update TypeScript paths after submodule changes |
| `pnpm clean` | Clean build artifacts |

### Backend (`/back`)

| Command | Purpose |
|---------|---------|
| `mvn clean install` | Build all modules |
| `mvn test` | Run JUnit tests |
| `mvn clean package` | Package without tests |
| `java -jar jetlinks-standalone/target/jetlinks-standalone-*.jar` | Run standalone app |

**Backend runs on**: http://localhost:8848 (default)

## Module Structure

### Frontend (`/front/src/`)

```
src/
├── modules/              # Git submodules (CRITICAL - loaded dynamically)
│   ├── device-manager-ui/      # Device management UI
│   ├── rule-engine-manager-ui/ # Rule engine UI
│   ├── network-manager-ui/     # Network config UI
│   ├── link-manager-ui/        # Protocol link UI
│   └── ...
├── components/           # Shared Vue components
│   ├── GlobalHeader/
│   ├── Menu/
│   └── ...
├── api/                  # API integration layer (axios-based)
├── store/                # Pinia state stores
│   ├── system.ts         # System state
│   └── user.ts          # User state
├── router/               # Vue Router configuration
├── hooks/                # Vue composables (from @jetlinks-web/hooks)
├── assets/               # Static assets
└── utils/                # Utility functions
```

**Key Frontend Modules** (Git Submodules):
- `device-manager-ui` - Device lifecycle management
- `rule-engine-manager-ui` - Automation rules
- `network-manager-ui` - Network gateway configuration
- `link-manager-ui` - Protocol adapters (MQTT, CoAP, etc.)
- `edge-gateway-ui` - Edge gateway management
- `notification-ui` - Alert & notification management
- `low-code-ui` - Low-code configuration
- `data-collect-ui` - Data collection pipelines

### Backend (`/back/jetlinks-community-2.10/jetlinks-community-2.10/`)

```
jetlinks-community-2.10/
├── jetlinks-components/         # Reusable reactive components
│   ├── network-component/       # Multi-protocol network abstraction
│   ├── protocol-component/      # Device protocol management
│   ├── rule-engine-component/   # Rule engine core
│   ├── gateway-component/       # Gateway integration
│   ├── notify-component/        # Notification system
│   ├── elasticsearch-component/ # ES integration
│   └── io-component/            # I/O utilities (CSV, Excel, etc.)
├── jetlinks-manager/            # Business logic managers
│   ├── device-manager/          # Device CRUD + lifecycle
│   ├── authentication-manager/  # User auth + permissions
│   ├── notify-manager/          # Notification templates
│   ├── logging-manager/         # System logging
│   └── rule-engine-manager/     # Rule execution
├── jetlinks-standalone/         # Main Spring Boot application
│   ├── src/main/resources/
│   │   ├── application.yml      # Configuration
│   │   └── db/migration/        # Flyway migrations
│   └── target/                  # Build output
└── pom.xml                      # Parent POM
```

**Key Backend Components**:
- `network-component` - Abstracts MQTT/CoAP/TCP/UDP/HTTP/WebSocket
- `protocol-component` - Device protocol parsing & encoding
- `rule-engine-component` - Event-driven automation engine
- `device-manager` - Device registry + shadow state management
- `gateway-component` - Multi-protocol gateway orchestration

## Important Conventions

### Frontend

1. **Global Components** (Auto-registered from `@jetlinks-web/components`):
   - `<j-pro-table>` - Advanced data table
   - `<j-permission-button>` - Permission-aware button
   - `<ValueItem>` - Key-value display component
   - `<BadgeStatus>` - Status badge
   - `<j-input>`, `<j-select>`, etc. - Enhanced form controls

2. **Environment Configuration**:
   - `.env.development` - Dev environment (API proxy to localhost:8848)
   - `.env.production` - Production build
   - `.env.server` - Server deployment config
   - Vite loads env vars prefixed with `VITE_`

3. **API Proxy** (vite.config.ts):
   - `/api/*` → proxied to backend (default: http://localhost:8848)
   - `/upload/*` → file upload proxy

4. **TypeScript**:
   - Strict mode enabled
   - Path aliases: `@/*` → `src/*`, `@jetlinks-web/*` → submodule packages
   - `tsconfig.json` auto-updated by `pnpm update:tsconfig`

5. **Routing**:
   - Dynamic routes loaded from submodules
   - Route guards check user permissions (store/user.ts)

### Backend

1. **Reactive Patterns**:
   - All data access via R2DBC (returns `Mono<T>` or `Flux<T>`)
   - Controllers use `@RestController` with reactive return types
   - WebFlux for non-blocking I/O

2. **Component Initialization**:
   - Components self-register via Spring Boot auto-configuration
   - Protocol components discovered via `@Component` scanning

3. **Database**:
   - PostgreSQL schema managed by Flyway migrations (`db/migration/`)
   - R2DBC connection pool configuration in `application.yml`

4. **Protocol Support**:
   - New protocols added via `ProtocolSupport` interface
   - Network components implement `Network` interface

## Common Issues & Solutions

### Frontend

| Issue | Cause | Solution |
|-------|-------|----------|
| "Systems initialized 0 menus" | Submodules not initialized | Run `pnpm modules:init` |
| Empty `src/modules/` directory | Submodules missing | Run `pnpm modules:init` |
| "Permission denied (publickey)" | SSH keys not configured | Add SSH key to GitHub account |
| Module not found errors | Submodule initialization failed | Delete `src/modules/`, re-run `pnpm modules:init` |
| TypeScript path errors | `tsconfig.json` out of sync | Run `pnpm update:tsconfig` |
| Port 9100 already in use | Dev server conflict | Change port in `vite.config.ts` or kill process |

### Backend

| Issue | Cause | Solution |
|-------|-------|----------|
| Connection refused to PostgreSQL | Database not running | Start PostgreSQL service |
| Class not found errors | Maven build incomplete | Run `mvn clean install` |
| Port 8848 already in use | Another instance running | Change `server.port` in `application.yml` |

## Testing

### Frontend Testing
- **Framework**: Playwright (E2E tests)
- **Test Location**: `/front/tests/e2e/`
- **Run**: `pnpm test`
- **Target**: http://localhost:9000 (configured in playwright.config.ts)

### Backend Testing
- **Framework**: JUnit 5 + Spring Boot Test
- **Coverage**: JaCoCo (reports in `target/site/jacoco/`)
- **Run**: `mvn test`
- **Integration Tests**: Use `@SpringBootTest` with embedded PostgreSQL

## Key Files Reference

### Frontend
- `front/package.json` - Dependencies & scripts
- `front/vite.config.ts` - Vite configuration + API proxy
- `front/tsconfig.json` - TypeScript paths (auto-generated)
- `front/src/main.ts` - App entry point
- `front/src/router/index.ts` - Route registration
- `front/.env.development` - Dev environment config

### Backend
- `back/jetlinks-community-2.10/jetlinks-community-2.10/pom.xml` - Parent POM
- `jetlinks-standalone/src/main/resources/application.yml` - Main config
- `jetlinks-standalone/src/main/java/org/jetlinks/community/standalone/JetLinksApplication.java` - Entry point

## Development Workflow

### Adding a New Frontend Feature
1. Determine if it belongs in a submodule or main app
2. If submodule: work in the submodule repo, then `git pull` in main repo
3. If main app: add component/view in `src/components/` or `src/views/`
4. Register routes in `src/router/index.ts`
5. Add API calls in `src/api/`
6. Update Pinia store if needed

### Adding a New Backend Component
1. Create new module in `jetlinks-components/` or `jetlinks-manager/`
2. Implement reactive service layer (return `Mono<T>`/`Flux<T>`)
3. Add REST controller in `jetlinks-standalone/src/main/java/.../web/`
4. Register component via `@Component` or `@Configuration`
5. Add database migrations if needed in `jetlinks-standalone/src/main/resources/db/migration/`

---

**Last Updated**: 2026-04-05
**JetLinks Version**: Community Edition 2.10
**Repository**: https://github.com/jetlinks/jetlinks-community
