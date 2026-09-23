# jlloc

JVM memory monitoring and explainable diagnosis for local development.

Run multiple Java services, Elasticsearch, ActiveMQ, and any other JVM
process on one machine without manually correlating JVM and host memory
signals. jlloc watches every JVM on your machine and
explains what is happening; it does not resize or restart services automatically.

The daemon also exposes a Prometheus-style `/metrics` endpoint for
existing scrapers and autoscalers.

> Status: v0.1 release candidate. Linux-first, local CLI tool for explainable JVM memory diagnosis.

## Repo layout

This is a multi-module repo. Each top-level folder is a self-contained
module with its own build file — they are not meant to be built by
hand from a generic `src/`.

| Module | Language | What it is |
|---|---|---|
| `jlloc-common` | Java | Shared data classes used by every other module |
| `jlloc-daemon` | Java | Background service: detects JVMs, tracks memory, decides heap budgets |
| `jlloc-agent` | Java | Tiny agent injected into every JVM via `-javaagent` |
| `jlloc-cli` | Java | `jlloc status` / `jlloc logs` developer-facing commands |
| `jlloc-wrapper` | Shell | Drop-in replacement for the `java` command |
| `scripts` | Shell | Install/build/uninstall scripts |
| `docs` | Markdown | Design notes, architecture docs |

## First-time setup

The Gradle wrapper is committed. After cloning, use:

```bash
./gradlew build
```

The v0.1 scope is intentionally CLI-first: jlloc does not restart services,
mutate Kubernetes resources, or provide a dashboard.

The wrapper files are committed; after that, nobody else needs Gradle
installed locally, they just run `./gradlew build`.

## First use

The preferred local-dev entrypoint is the wrapper script:

```powershell
.\scripts\jlloc.ps1 start
.\scripts\jlloc.ps1 status
.\scripts\jlloc.ps1 metrics
.\scripts\jlloc.ps1 explain <service>
.\scripts\jlloc.ps1 stop
```

That wrapper builds the local distributions if needed, starts the
daemon, and forwards CLI commands to the packaged binaries.

## Building

Java modules (requires JDK 21):

```bash
./gradlew build
```

Daemon metrics endpoint defaults:

- `http://127.0.0.1:8001/metrics`
- Versioned scrape path: `http://127.0.0.1:8001/metrics/v1`
- `JLLOC_METRICS_PORT` overrides the port
- `JLLOC_METRICS_BIND` overrides the bind address

Release packaging and real-environment validation are documented in
`docs/release_checklist.md` and `docs/real_environment_testing.md`.

## License

Operational guidance, release packaging, and real-environment test procedures
are in [`docs/operations.md`](docs/operations.md) and
[`docs/real_environment_testing.md`](docs/real_environment_testing.md).

[Apache 2.0](LICENSE) (open-source, enterprise-friendly)
