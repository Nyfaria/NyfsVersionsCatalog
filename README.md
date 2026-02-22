# NyfsModdingTools
Modding Tools Plugin for Nyfaria's Mods

## Overview
This repository provides a Gradle plugin with modding tools for managing dependencies across Nyfaria's mods. Using Gradle's version catalog feature provides type-safe dependency management, consistent versioning, and easier maintenance.

## Remote Version Updates
The plugin automatically fetches the latest version definitions from GitHub, so you don't need to update the plugin to get new library versions. Version data is:
- Fetched from `https://github.com/Nyfaria/NyfsModdingTools/tree/main/versions` by default
- Cached locally for 24 hours in `~/.gradle/caches/nyfs-modding-tools/versions/`
- Falls back to bundled versions if offline or the remote is unreachable

To force a refresh of cached versions, delete the cache directory or wait for the 24-hour TTL to expire.

### Custom Versions URL
You can host your own version definitions and configure the plugin to use them:

```kotlin
import com.nyfaria.moddingtools.MinecraftVersions

MinecraftVersions.setVersionsUrl("https://raw.githubusercontent.com/YourUser/YourRepo/main/versions")
```

Set to `null` to reset to the default URL. The URL should point to a directory containing:
- `_base.json` - Base version definitions
- `index.txt` - List of supported Minecraft versions
- `{version}.json` - Version-specific definitions (e.g., `1.21.1.json`)

## Usage

### 1. Add the Version Catalog to Your Project

In your `settings.gradle` or `settings.gradle.kts`, reference this catalog:

#### Option A: From a Local Copy
```groovy
dependencyResolutionManagement {
    versionCatalogs {
        libs {
            from(files("path/to/gradle/libs.versions.toml"))
        }
    }
}
```

#### Option B: From GitHub (recommended for published catalogs)
```groovy
dependencyResolutionManagement {
    versionCatalogs {
        libs {
            from("com.github.nyfaria:versions-catalog:1.0.0")
        }
    }
}
```

### 2. Use Dependencies in Your Build Scripts

Once configured, you can reference dependencies using type-safe accessors:

#### In `build.gradle`:
```groovy
dependencies {
    // Reference individual libraries
    implementation libs.some.library
    
    // Reference bundles
    implementation libs.bundles.common
}

plugins {
    // Reference plugins
    alias(libs.plugins.forge.gradle)
}
```

#### In `build.gradle.kts`:
```kotlin
dependencies {
    // Reference individual libraries
    implementation(libs.some.library)
    
    // Reference bundles
    implementation(libs.bundles.common)
}

plugins {
    // Reference plugins
    alias(libs.plugins.forge.gradle)
}
```

### 3. Defining Dependencies

Edit `gradle/libs.versions.toml` to add your dependencies:

```toml
[versions]
minecraft = "1.20.1"
forge = "47.1.0"

[libraries]
minecraft-forge = { group = "net.minecraftforge", name = "forge", version.ref = "forge" }

[bundles]
common = ["minecraft-forge"]

[plugins]
forge-gradle = { id = "net.minecraftforge.gradle", version = "6.0.+" }
```

## Documentation

### Mod Dependencies Extension

The plugin provides a `modDependencies` extension for managing mod dependencies with automatic metadata file modification. This extension is automatically applied to projects with names containing "fabric", "neoforge", "forge", or "quilt".

#### Usage

In your loader-specific `build.gradle` (e.g., `fabric/build.gradle` or `neoforge/build.gradle`):

```groovy
modDependencies {
    requiredMod(nyfs.geckolib.fabric)
    optionalMod(nyfs.jei.fabric)
    embeddedMod(nyfs.config.api.fabric)
}
```

The plugin automatically extracts the modId and version from the dependency. If you need to override them:

```groovy
modDependencies {
    requiredMod("geckolib", "4.8.3", nyfs.geckolib.fabric)
    optionalMod("jei", "15.2.0", "mezz.jei:jei-1.20.1-fabric:15.2.0.27")
}
```

#### Methods

- **`requiredMod(dependency)`** - Adds a required dependency (auto-extracts modId/version)
- **`requiredMod(modId, version, dependency)`** - Adds a required dependency with explicit modId/version
  - Adds the dependency to `modImplementation` (Fabric) or `implementation` (NeoForge/Forge)
  - Adds to `depends` block in `fabric.mod.json`
  - Adds `type="required"` in `neoforge.mods.toml`

- **`optionalMod(dependency)`** - Adds an optional dependency (auto-extracts modId/version)
- **`optionalMod(modId, version, dependency)`** - Adds an optional dependency with explicit modId/version
  - Adds the dependency to `modCompileOnly` (Fabric) or `compileOnly` (NeoForge/Forge)
  - Adds to `suggests` block in `fabric.mod.json`
  - Adds `type="optional"` in `neoforge.mods.toml`

- **`embeddedMod(dependency)`** - Adds an embedded/bundled dependency (auto-extracts modId/version)
- **`embeddedMod(modId, version, dependency)`** - Adds an embedded/bundled dependency with explicit modId/version
  - Adds the dependency to `include` + `modImplementation` (Fabric) or `jarJar` + `implementation` (NeoForge/Forge)
  - Adds to `depends` block in `fabric.mod.json`
  - Adds `type="required"` in `neoforge.mods.toml`

#### Automatic Metadata Modification

The plugin automatically modifies `fabric.mod.json` and `neoforge.mods.toml` at build time (in the output jar only, not the source files):

**Fabric (`fabric.mod.json`):**
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

**NeoForge/Forge (`neoforge.mods.toml`):**
```toml
[[dependencies.yourmodid]]
modId="geckolib"
type="required"
versionRange="[4.8.3,)"
ordering="NONE"
side="BOTH"
```

For more information on Gradle version catalogs, see:
- [Official Gradle Documentation](https://docs.gradle.org/current/userguide/version_catalogs.html)
- [Migrate to Version Catalogs](https://developer.android.com/build/migrate-to-catalogs)
