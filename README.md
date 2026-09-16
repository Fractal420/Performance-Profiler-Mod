# Performance Profiler

Client-side Fabric mod that finds which mods cause FPS drops and overheating.

## Features

- Statistical stack sampler that attributes client-thread time to mods
- Entity counts by mod and by type
- Particle and chunk counters
- Memory and GC statistics
- Client graphics options dump
- Full list of loaded mods with versions
- Rich text reports written to `config/perfprofiler/reports/`
- No chat messages
- Lightweight HUD

## Controls

| Key | Action |
|-----|--------|
| F8  | Toggle HUD |
| F9  | Start / stop profiling session (report written on stop) |
| F10 | Write snapshot report immediately |

Reports:

```
.minecraft/config/perfprofiler/reports/profiler-report-YYYYMMDD-HHMMSS.txt
```

## Usage

1. Go where FPS drops or overheating happens.
2. Press F9 and play 30–90 seconds.
3. Press F9 again — report is saved.
4. Open the newest file under `config/perfprofiler/reports/`.

## Building

Gradle 9.6.0 is included.

```bash
./gradlew build -Pmc=1.21.11
./gradlew build -Pmc=26.1
./gradlew build -Pmc=26.2
./gradlew buildAll
```

- **1.21.11** uses `fabric-loom-remap` + Mojang mappings (Java 21)
- **26.1 / 26.2** use `fabric-loom` (no mappings, unobfuscated) and require Java 25 for the toolchain

If Fabric API / Mod Menu version numbers in `versions/*/gradle.properties` are wrong for your setup, update them from https://fabricmc.net/develop/

## License

MIT
