package com.nyfaria.moddingtools

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.File

abstract class RefactorPackagesTask : DefaultTask() {

    @get:Input
    @get:Option(option = "newGroup", description = "The new group/package base (e.g., com.example)")
    abstract val newGroup: Property<String>

    @get:Input
    @get:Option(option = "newModId", description = "The new mod ID (e.g., mymod)")
    abstract val newModId: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "oldGroup", description = "The old group/package base to replace")
    abstract val oldGroup: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "oldModId", description = "The old mod ID to replace")
    abstract val oldModId: Property<String>

    init {
        group = "modding tools"
        description = "Refactors package names across all modules based on group and mod_id"
    }

    @TaskAction
    fun refactorPackages() {
        val rootDir = project.rootDir
        val propsFile = File(rootDir, "gradle.properties")

        val currentGroup = oldGroup.orNull ?: readPropertyFromFile(propsFile, "group") ?: "com.example"
        val currentModId = oldModId.orNull ?: readPropertyFromFile(propsFile, "mod_id") ?: "examplemod"

        val targetGroup = newGroup.get()
        val targetModId = newModId.get()

        if (currentGroup == targetGroup && currentModId == targetModId) {
            logger.lifecycle("No changes needed - group and mod_id are already set to target values")
            return
        }

        val oldPackage = "$currentGroup.$currentModId"
        val newPackage = "$targetGroup.$targetModId"

        logger.lifecycle("Refactoring packages from '$oldPackage' to '$newPackage'")

        val moduleNames = listOf("common", "fabric", "forge", "neoforge")

        for (moduleName in moduleNames) {
            val moduleDir = File(rootDir, moduleName)
            if (!moduleDir.exists()) {
                logger.info("Module '$moduleName' does not exist, skipping")
                continue
            }

            refactorModule(moduleDir, oldPackage, newPackage, currentGroup, targetGroup, currentModId, targetModId)
        }

        updateGradleProperties(propsFile, targetGroup, targetModId)

        updateFabricEntrypoints(rootDir)

        logger.lifecycle("Package refactoring complete!")
        logger.lifecycle("Old package: $oldPackage")
        logger.lifecycle("New package: $newPackage")
    }

    private fun refactorModule(
        moduleDir: File,
        oldPackage: String,
        newPackage: String,
        oldGroup: String,
        newGroup: String,
        oldModId: String,
        newModId: String
    ) {
        val javaSrcDir = File(moduleDir, "src/main/java")
        val kotlinSrcDir = File(moduleDir, "src/main/kotlin")

        listOf(javaSrcDir, kotlinSrcDir).forEach { srcDir ->
            if (srcDir.exists()) {
                refactorSourceDirectory(srcDir, oldPackage, newPackage, oldGroup, newGroup, oldModId, newModId)
            }
        }

        val resourcesDir = File(moduleDir, "src/main/resources")
        if (resourcesDir.exists()) {
            updateResourceFiles(resourcesDir, oldPackage, newPackage, oldModId, newModId)
        }

        if (oldModId != newModId) {
            listOf(javaSrcDir, kotlinSrcDir).forEach { srcDir ->
                if (srcDir.exists()) {
                    updateConstantsModId(srcDir, newPackage, oldModId, newModId)
                }
            }
        }
    }

    private fun updateConstantsModId(srcDir: File, newPackage: String, oldModId: String, newModId: String) {
        val packagePath = newPackage.replace('.', File.separatorChar)
        val constantsJava = File(srcDir, "$packagePath/Constants.java")
        val constantsKt = File(srcDir, "$packagePath/Constants.kt")

        listOf(constantsJava, constantsKt).forEach { constantsFile ->
            if (constantsFile.exists()) {
                var content = constantsFile.readText()
                var modified = false

                val modIdPattern = Regex("""(MODID\s*=\s*")${Regex.escape(oldModId)}(")""")
                if (modIdPattern.containsMatchIn(content)) {
                    content = modIdPattern.replace(content, "$1$newModId$2")
                    modified = true
                }

                val modIdPattern2 = Regex("""(MOD_ID\s*=\s*")${Regex.escape(oldModId)}(")""")
                if (modIdPattern2.containsMatchIn(content)) {
                    content = modIdPattern2.replace(content, "$1$newModId$2")
                    modified = true
                }

                if (modified) {
                    constantsFile.writeText(content)
                    logger.lifecycle("Updated Constants MODID: ${constantsFile.name}")
                }
            }
        }
    }

    private fun updateFabricEntrypoints(rootDir: File) {
        val fabricDir = File(rootDir, "fabric")
        if (!fabricDir.exists()) return

        val fabricModJson = File(fabricDir, "src/main/resources/fabric.mod.json")
        if (!fabricModJson.exists()) return

        val javaSrcDir = File(fabricDir, "src/main/java")
        val kotlinSrcDir = File(fabricDir, "src/main/kotlin")

        val mainEntrypoints = mutableListOf<String>()
        val clientEntrypoints = mutableListOf<String>()
        val serverEntrypoints = mutableListOf<String>()

        listOf(javaSrcDir, kotlinSrcDir).forEach { srcDir ->
            if (srcDir.exists()) {
                srcDir.walkTopDown()
                    .maxDepth(20)
                    .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                    .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
                    .forEach { file ->
                        val content = file.readText()
                        val packageMatch = Regex("""package\s+([\w.]+)""").find(content)
                        val pkg = packageMatch?.groupValues?.get(1) ?: return@forEach

                        val classNameMatch = Regex("""(?:class|object)\s+(\w+)""").find(content)
                        val className = classNameMatch?.groupValues?.get(1) ?: return@forEach

                        val fullClassName = "$pkg.$className"

                        if (content.contains("implements ModInitializer") ||
                            content.contains(": ModInitializer")) {
                            mainEntrypoints.add(fullClassName)
                        }
                        if (content.contains("implements ClientModInitializer") ||
                            content.contains(": ClientModInitializer")) {
                            clientEntrypoints.add(fullClassName)
                        }
                        if (content.contains("implements DedicatedServerModInitializer") ||
                            content.contains(": DedicatedServerModInitializer")) {
                            serverEntrypoints.add(fullClassName)
                        }
                    }
            }
        }

        if (mainEntrypoints.isEmpty() && clientEntrypoints.isEmpty() && serverEntrypoints.isEmpty()) {
            return
        }

        var content = fabricModJson.readText()
        var modified = false

        if (mainEntrypoints.isNotEmpty()) {
            val mainArrayStr = mainEntrypoints.joinToString(",\n            ") { "\"$it\"" }
            val mainPattern = Regex(""""main"\s*:\s*\[\s*"[^"]*"(?:\s*,\s*"[^"]*")*\s*\]""")
            if (mainPattern.containsMatchIn(content)) {
                content = mainPattern.replace(content, "\"main\": [\n            $mainArrayStr\n        ]")
                modified = true
            }
        }

        if (clientEntrypoints.isNotEmpty()) {
            val clientArrayStr = clientEntrypoints.joinToString(",\n            ") { "\"$it\"" }
            val clientPattern = Regex(""""client"\s*:\s*\[\s*"[^"]*"(?:\s*,\s*"[^"]*")*\s*\]""")
            if (clientPattern.containsMatchIn(content)) {
                content = clientPattern.replace(content, "\"client\": [\n            $clientArrayStr\n        ]")
                modified = true
            } else {
                val entrypointsPattern = Regex("""("entrypoints"\s*:\s*\{)""")
                if (entrypointsPattern.containsMatchIn(content)) {
                    content = entrypointsPattern.replace(content, "$1\n        \"client\": [\n            $clientArrayStr\n        ],")
                    modified = true
                }
            }
        }

        if (serverEntrypoints.isNotEmpty()) {
            val serverArrayStr = serverEntrypoints.joinToString(",\n            ") { "\"$it\"" }
            val serverPattern = Regex(""""server"\s*:\s*\[\s*"[^"]*"(?:\s*,\s*"[^"]*")*\s*\]""")
            if (serverPattern.containsMatchIn(content)) {
                content = serverPattern.replace(content, "\"server\": [\n            $serverArrayStr\n        ]")
                modified = true
            } else {
                val entrypointsPattern = Regex("""("entrypoints"\s*:\s*\{)""")
                if (entrypointsPattern.containsMatchIn(content)) {
                    content = entrypointsPattern.replace(content, "$1\n        \"server\": [\n            $serverArrayStr\n        ],")
                    modified = true
                }
            }
        }

        if (modified) {
            fabricModJson.writeText(content)
            logger.lifecycle("Updated fabric.mod.json entrypoints")
        }
    }

    private fun refactorSourceDirectory(
        srcDir: File,
        oldPackage: String,
        newPackage: String,
        oldGroup: String,
        newGroup: String,
        oldModId: String,
        newModId: String
    ) {
        val oldPackagePath = oldPackage.replace('.', File.separatorChar)
        val newPackagePath = newPackage.replace('.', File.separatorChar)

        val oldPackageDir = File(srcDir, oldPackagePath).canonicalFile
        val newPackageDir = File(srcDir, newPackagePath).canonicalFile

        if (!oldPackageDir.exists()) {
            logger.info("Old package directory does not exist: ${oldPackageDir.absolutePath}")
            return
        }

        updatePackageDeclarationsAndImports(oldPackageDir, oldPackage, newPackage)

        if (oldPackageDir.absolutePath != newPackageDir.absolutePath) {
            if (newPackageDir.absolutePath.startsWith(oldPackageDir.absolutePath + File.separator) ||
                oldPackageDir.absolutePath.startsWith(newPackageDir.absolutePath + File.separator)) {
                logger.warn("[ModdingTools] Skipping move: nested directory structure detected")
                return
            }

            newPackageDir.parentFile.mkdirs()

            if (newPackageDir.exists()) {
                oldPackageDir.listFiles()?.forEach { file ->
                    val targetFile = File(newPackageDir, file.name)
                    if (file.isDirectory) {
                        file.copyRecursively(targetFile, overwrite = true)
                        file.deleteRecursively()
                    } else {
                        file.copyTo(targetFile, overwrite = true)
                        file.delete()
                    }
                }
            } else {
                oldPackageDir.renameTo(newPackageDir)
            }

            cleanupEmptyDirectories(srcDir, oldGroup.replace('.', File.separatorChar))

            logger.lifecycle("Moved: ${oldPackageDir.relativeTo(srcDir)} -> ${newPackageDir.relativeTo(srcDir)}")
        }
    }

    private fun updatePackageDeclarationsAndImports(directory: File, oldPackage: String, newPackage: String) {
        directory.walkTopDown()
            .maxDepth(20)
            .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .forEach { file ->
            var content = file.readText()
            var modified = false

            val oldPackagePattern = Regex("(package\\s+)${Regex.escape(oldPackage)}")
            if (oldPackagePattern.containsMatchIn(content)) {
                content = oldPackagePattern.replace(content, "$1$newPackage")
                modified = true
            }

            val oldImportPattern = Regex("(import\\s+)${Regex.escape(oldPackage)}")
            if (oldImportPattern.containsMatchIn(content)) {
                content = oldImportPattern.replace(content, "$1$newPackage")
                modified = true
            }

            val oldStaticImportPattern = Regex("(import\\s+static\\s+)${Regex.escape(oldPackage)}")
            if (oldStaticImportPattern.containsMatchIn(content)) {
                content = oldStaticImportPattern.replace(content, "$1$newPackage")
                modified = true
            }

            if (modified) {
                file.writeText(content)
                logger.info("Updated: ${file.name}")
            }
        }
    }

    private fun updateResourceFiles(resourcesDir: File, oldPackage: String, newPackage: String, oldModId: String, newModId: String) {
        val mixinFiles = resourcesDir.walkTopDown()
            .maxDepth(10)
            .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter {
                it.isFile && (it.name.endsWith(".mixins.json") || it.name == "fabric.mod.json" || it.name == "mods.toml" || it.name == "neoforge.mods.toml")
            }

        mixinFiles.forEach { file ->
            var content = file.readText()
            val oldPackageQuoted = "\"${oldPackage}"
            val newPackageQuoted = "\"${newPackage}"

            if (content.contains(oldPackageQuoted)) {
                content = content.replace(oldPackageQuoted, newPackageQuoted)
                file.writeText(content)
                logger.info("Updated resource: ${file.name}")
            }
        }

        updateServiceFiles(resourcesDir, oldPackage, newPackage)
        renameMixinFiles(resourcesDir, oldModId, newModId)
        renameAssetAndDataFolders(resourcesDir, oldModId, newModId)
    }

    private fun renameMixinFiles(resourcesDir: File, oldModId: String, newModId: String) {
        if (oldModId == newModId) return

        resourcesDir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".mixins.json") && it.name.contains(oldModId)
        }?.forEach { mixinFile ->
            val newFileName = mixinFile.name.replace(oldModId, newModId)
            val newFile = File(resourcesDir, newFileName)
            mixinFile.renameTo(newFile)
            logger.lifecycle("Renamed mixin file: ${mixinFile.name} -> $newFileName")
        }
    }

    private fun renameAssetAndDataFolders(resourcesDir: File, oldModId: String, newModId: String) {
        if (oldModId == newModId) return

        listOf("assets", "data").forEach { folderName ->
            val parentDir = File(resourcesDir, folderName)
            if (!parentDir.exists() || !parentDir.isDirectory) return@forEach

            val oldModDir = File(parentDir, oldModId)
            if (oldModDir.exists() && oldModDir.isDirectory) {
                val newModDir = File(parentDir, newModId)
                if (oldModDir.renameTo(newModDir)) {
                    logger.lifecycle("Renamed $folderName folder: $oldModId -> $newModId")
                } else {
                    oldModDir.copyRecursively(newModDir, overwrite = true)
                    oldModDir.deleteRecursively()
                    logger.lifecycle("Renamed $folderName folder: $oldModId -> $newModId")
                }
            }
        }
    }

    private fun updateServiceFiles(resourcesDir: File, oldPackage: String, newPackage: String) {
        val servicesDir = File(resourcesDir, "META-INF/services")
        if (!servicesDir.exists() || !servicesDir.isDirectory) return

        servicesDir.listFiles()?.forEach { serviceFile ->
            if (!serviceFile.isFile) return@forEach

            var content = serviceFile.readText()
            var contentModified = false
            if (content.contains(oldPackage)) {
                content = content.replace(oldPackage, newPackage)
                serviceFile.writeText(content)
                contentModified = true
                logger.lifecycle("Updated service file content: ${serviceFile.name}")
            }

            if (serviceFile.name.contains(oldPackage)) {
                val newFileName = serviceFile.name.replace(oldPackage, newPackage)
                val newFile = File(servicesDir, newFileName)
                if (contentModified) {
                    newFile.writeText(content)
                } else {
                    serviceFile.copyTo(newFile, overwrite = true)
                }
                serviceFile.delete()
                logger.lifecycle("Renamed service file: ${serviceFile.name} -> $newFileName")
            }
        }
    }

    private fun cleanupEmptyDirectories(baseDir: File, oldGroupPath: String) {
        val oldGroupDir = File(baseDir, oldGroupPath)
        var currentDir: File? = oldGroupDir
        var depth = 0
        val maxDepth = 20

        while (currentDir != null && currentDir != baseDir && currentDir.exists() && depth < maxDepth) {
            if (currentDir.isDirectory && (currentDir.listFiles()?.isEmpty() == true)) {
                currentDir.delete()
                logger.info("Removed empty directory: ${currentDir.relativeTo(baseDir)}")
            }
            val parent = currentDir.parentFile
            if (parent == currentDir) break
            currentDir = parent
            depth++
        }
    }

    private fun updateGradleProperties(propsFile: File, newGroup: String, newModId: String) {
        if (!propsFile.exists()) return

        var content = propsFile.readText()

        content = content.replace(Regex("^group=.*$", RegexOption.MULTILINE), "group=$newGroup")
        content = content.replace(Regex("^mod_id=.*$", RegexOption.MULTILINE), "mod_id=$newModId")

        propsFile.writeText(content)
        logger.lifecycle("Updated gradle.properties with new group and mod_id")
    }

    private fun readPropertyFromFile(file: File, key: String): String? {
        if (!file.exists()) return null
        return file.readLines()
            .filter { it.startsWith("$key=") }
            .map { it.substringAfter("=").trim() }
            .firstOrNull()
    }
}
