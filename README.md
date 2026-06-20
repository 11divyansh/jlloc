# jlloc

Automatic JVM heap management for local development.

Run multiple Java services, Elasticsearch, ActiveMQ, and any other JVM
process on one machine without manually tuning `-Xmx` for each one or
running out of memory. jlloc watches every JVM on your machine and
allocates heap automatically — zero config.

> Status: early development (v0.1 in progress). Not yet usable.

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
| `jlloc-native` | C++ | JVMTI agent for low-level heap/safepoint work (later phases) |
| `jlloc-wrapper` | Shell | Drop-in replacement for the `java` command |
| `scripts` | Shell | Install/build/uninstall scripts |
| `docs` | Markdown | Design notes, architecture docs |

## First-time setup

This repo doesn't ship the Gradle wrapper jar yet (it needs to be
generated once with a local Gradle install, then committed). After
cloning, run:

```bash
gradle wrapper --gradle-version 8.7
```

This creates `gradlew`, `gradlew.bat`, and `gradle/wrapper/`. Commit
those — after that, nobody else ever needs Gradle installed locally,
they just run `./gradlew build`.

## Building

Java modules (requires JDK 21):

```bash
./gradlew build
```

Native module (requires CMake + a C++17 compiler):

```bash
cd jlloc-native
cmake -B build
cmake --build build
```

## License

[Apache 2.0](LICENSE) (open-source, enterprise-friendly)
