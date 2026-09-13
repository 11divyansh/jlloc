# jlloc v0.1 Release Shape

This document describes the minimum release contract for the first
public version of jlloc.

## Support boundary

- Linux first
- local development first
- container-aware
- Windows best-effort until proven

## First-use flow

The preferred entrypoint is the wrapper script:

```powershell
.\scripts\jlloc.ps1 start
.\scripts\jlloc.ps1 status
.\scripts\jlloc.ps1 metrics
.\scripts\jlloc.ps1 explain <service>
.\scripts\jlloc.ps1 stop
```

What each command means:

- `start` builds the local distribution if needed and starts the daemon
- `status` shows monitored JVMs and their diagnoses
- `metrics` checks the Prometheus scrape endpoint
- `explain` prints the evidence trail for one JVM
- `stop` shuts the daemon down cleanly


