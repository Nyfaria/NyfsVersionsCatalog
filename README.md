# NyfsModdingTools
Modding Tools Plugin for Nyfaria's Mods

## Overview
This Gradle plugin provides comprehensive tooling for Minecraft mod development:
- **Version Catalog** - Automatic dependency management with pre-configured libraries, versions, and plugins for multiple Minecraft versions
- **Automatic Repository Configuration** - All common modding Maven repositories pre-configured
- **Package Refactoring** - Automatic and manual package renaming when changing mod ID or group
- **Mod Dependencies** - Simplified dependency declaration with automatic metadata file modification

## Installation

In your `settings.gradle.kts`:

```kotlin
plugins {
    id("com.nyfaria.moddingtools") version "1.2.0"
}
```

Or `settings.gradle`:

```groovy
plugins {
    id 'com.nyfaria.moddingtools' version '1.2.0'
}
```

The plugin reads `minecraft_version` (or `minecraftVersion`) from `gradle.properties` to determine which version catalog to load.

## Features

### 1. Version Catalog (`nyfs`)

The plugin creates a version catalog named `nyfs` with pre-configured dependencies for your Minecraft version.

**In your build scripts:**

```kotlin
dependencies {
    implementation(nyfs.geckolib.neoforge)
    implementation(nyfs.config.api.fabric)
    implementation(nyfs.bundles.cc.all)
}

plugins {
    alias(nyfs.plugins.mdg)
    alias(nyfs.plugins.loom)
}
```

**Available versions** (varies by Minecraft version):
- `minecraft`, `neoforgeVersion`, `forgeVersion`, `fabric-loader`, `fabric-api`
- `geckolib`, `config-api`, `commonnetwork`, `sbl`, `dynamictrees`
- And more...

**Available libraries** (varies by Minecraft version):
- `geckolib-common`, `geckolib-fabric`, `geckolib-neoforge`, `geckolib-forge`
- `config-api-common`, `config-api-fabric`, `config-api-neoforge`
- `sbl-common`, `sbl-fabric`, `sbl-neoforge`, `sbl-forge`
- `commonnetwork-common`, `commonnetwork-fabric`, `commonnetwork-neoforge`, `commonnetwork-forge`
- `dynamictrees-fabric`, `dynamictrees-neoforge`, `dynamictrees-forge`
- And more...

**Available plugins**:
- `mdg` / `mdg-loader` - NeoForge ModDevGradle
- `loom` / `loom-loader` - Fabric Loom
- `regutils` / `regutils-loader` - Registration Utils

### 2. Automatic Repository Configuration

The plugin automatically configures all common modding repositories:
- Maven Central
- NeoForged Maven
- Fabric Maven
- ParchmentMC Maven
- GeckoLib Maven
- SmartBrainLib Maven
- Modrinth Maven
- CurseMaven
- And more...

### 3. Automatic Package Refactoring

When you change `group` or `mod_id` in `gradle.properties`, the plugin **automatically detects** the mismatch and refactors:
- Java/Kotlin source files (package declarations and imports)
- Resource directories (`assets/<modid>/`, `data/<modid>/`)
- Mixin JSON files and references
- Service files (`META-INF/services/`)
- `fabric.mod.json` entrypoints
- And more...

Simply change your `gradle.properties` and sync the project - the plugin handles the rest!

### 4. Manual Refactor Task

For manual control, use the `refactorPackages` task:

```bash
./gradlew refactorPackages --newGroup=com.example --newModId=mymod
```

Optional parameters:
- `--oldGroup` - Specify the old group (auto-detected if not provided)
- `--oldModId` - Specify the old mod ID (auto-detected if not provided)

### 5. Mod Dependencies Extension

The `com.nyfaria.moddingtools.dependencies` plugin provides simplified dependency management with automatic metadata modification.

**In your loader-specific `build.gradle`:**

```groovy
plugins {
    id 'com.nyfaria.moddingtools.dependencies'
}

modDependencies {
    requiredMod(nyfs.geckolib.fabric)
    optionalMod(nyfs.jei.fabric)
    embeddedMod(nyfs.config.api.fabric)
}
```

**Methods:**

| Method | Gradle Configuration | fabric.mod.json | neoforge.mods.toml |
|--------|---------------------|-----------------|-------------------|
| `requiredMod()` | `modImplementation` / `implementation` | `depends` | `type="required"` |
| `optionalMod()` | `modCompileOnly` / `compileOnly` | `suggests` | `type="optional"` |
| `embeddedMod()` | `include` + `modImplementation` / `jarJar` + `implementation` | `depends` | `type="required"` |

**With explicit modId/version:**

```groovy
modDependencies {
    requiredMod("geckolib", "4.8.3", nyfs.geckolib.fabric)
    optionalMod("jei", "15.2.0", "mezz.jei:jei-1.20.1-fabric:15.2.0.27")
}
```

**Generated metadata:**

`fabric.mod.json`:
```json
{
  "depends": {
    "geckolib": ">=4.8.3"
  },
  "suggests": {
    "jei": ">=15.2.0"
  }
}
```

`neoforge.mods.toml`:
```toml
[[dependencies.yourmodid]]
modId="geckolib"
type="required"
versionRange="[4.8.3,)"
ordering="NONE"
side="BOTH"
```

## Remote Version Updates

The plugin fetches version definitions from GitHub, so you don't need to update the plugin to get new library versions:
- **Default URL**: `https://raw.githubusercontent.com/Nyfaria/NyfsModdingTools/main/versions`
- **Cache**: `~/.gradle/caches/nyfs-modding-tools/versions/` (24-hour TTL)

### Custom Versions URL

Host your own version definitions:

```kotlin
import com.nyfaria.moddingtools.MinecraftVersions

MinecraftVersions.setVersionsUrl("https://raw.githubusercontent.com/YourUser/YourRepo/main/versions")
```

Set to `null` to reset to default. Your URL should point to a directory containing:
- `_base.json` - Base version definitions
- `index.txt` - List of supported Minecraft versions (one per line)
- `{version}.json` - Version-specific definitions (e.g., `1.21.1.json`)

### Clear Cache

```kotlin
MinecraftVersions.clearCache()
```

Or delete `~/.gradle/caches/nyfs-modding-tools/versions/`

## Supported Minecraft Versions

- 1.20.1
- 1.21.1
- 1.21.3 - 1.21.11

## Links

- [Gradle Version Catalogs Documentation](https://docs.gradle.org/current/userguide/version_catalogs.html)
