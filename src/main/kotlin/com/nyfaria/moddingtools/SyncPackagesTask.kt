 package com.nyfaria.moddingtools

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.Properties

abstract class SyncPackagesTask : DefaultTask() {

    private val stateFileName = ".package-state"

    init {
        group = "modding tools"
        description = "Syncs package names to match current group and mod_id in gradle.properties"
    }

    @TaskAction
    fun syncPackages() {
        val rootDir = project.rootDir
        val propsFile = File(rootDir, "gradle.properties")
        val stateFile = File(rootDir, stateFileName)

        if (!propsFile.exists()) {
            logger.error("gradle.properties not found!")
            return
        }

        val currentGroup = project.findProperty("group")?.toString() ?: readPropertyFromFile(propsFile, "group") ?: return
        val currentModId = project.findProperty("mod_id")?.toString() ?: readPropertyFromFile(propsFile, "mod_id") ?: return

        val (previousGroup, previousModId) = readPreviousState(stateFile)

        if (previousGroup == null || previousModId == null) {
            saveState(stateFile, currentGroup, currentModId)
            logger.lifecycle("Package state initialized: $currentGroup.$currentModId")
            return
        }

        if (previousGroup == currentGroup && previousModId == currentModId) {
            logger.lifecycle("Packages are in sync: $currentGroup.$currentModId")
            return
        }

        logger.lifecycle("Detected package change:")
        logger.lifecycle("  From: $previousGroup.$previousModId")
        logger.lifecycle("  To:   $currentGroup.$currentModId")

        val refactorer = PackageRefactorer(logger)
        refactorer.refactor(
            rootDir = rootDir,
            oldGroup = previousGroup,
            oldModId = previousModId,
            newGroup = currentGroup,
            newModId = currentModId
        )

        saveState(stateFile, currentGroup, currentModId)
        logger.lifecycle("Package sync complete!")
    }

    private fun readPreviousState(stateFile: File): Pair<String?, String?> {
        if (!stateFile.exists()) return Pair(null, null)

        val props = Properties()
        stateFile.inputStream().use { props.load(it) }

        return Pair(
            props.getProperty("group"),
            props.getProperty("mod_id")
        )
    }

    private fun saveState(stateFile: File, group: String, modId: String) {
        val props = Properties()
        props.setProperty("group", group)
        props.setProperty("mod_id", modId)
        stateFile.outputStream().use { props.store(it, "Package state - do not edit manually") }
    }

    private fun readPropertyFromFile(file: File, key: String): String? {
        if (!file.exists()) return null
        return file.readLines()
            .filter { it.startsWith("$key=") }
            .map { it.substringAfter("=").trim() }
            .firstOrNull()
    }
}

class PackageRefactorer(private val logger: org.gradle.api.logging.Logger) {

    fun refactor(
        rootDir: File,
        oldGroup: String,
        oldModId: String,
        newGroup: String,
        newModId: String
    ) {
        val oldPackage = "$oldGroup.$oldModId"
        val newPackage = "$newGroup.$newModId"

        logger.lifecycle("[ModdingTools] Refactoring from '$oldPackage' to '$newPackage'")

        val moduleNames = listOf("common", "fabric", "forge", "neoforge")

        for (moduleName in moduleNames) {
            val moduleDir = File(rootDir, moduleName)
            if (!moduleDir.exists()) continue

            refactorModule(moduleDir, oldPackage, newPackage, oldGroup, newGroup, oldModId, newModId)
        }

        updateFabricEntrypoints(rootDir)
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
                updateAllSourceFiles(srcDir, oldPackage, newPackage)
                movePackageDirectory(srcDir, oldPackage, newPackage, oldGroup, newGroup)
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
                    logger.lifecycle("[ModdingTools] Updated Constants MODID: ${constantsFile.name}")
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
            logger.lifecycle("[ModdingTools] Updated fabric.mod.json entrypoints")
        }
    }

    private fun updateAllSourceFiles(srcDir: File, oldPackage: String, newPackage: String) {
        srcDir.walkTopDown()
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
                logger.info("[ModdingTools] Updated: ${file.absolutePath}")
            }
        }
    }

    private fun movePackageDirectory(srcDir: File, oldPackage: String, newPackage: String, oldGroup: String, newGroup: String) {
        val oldPackagePath = oldPackage.replace('.', File.separatorChar)
        val newPackagePath = newPackage.replace('.', File.separatorChar)

        val oldPackageDir = File(srcDir, oldPackagePath).canonicalFile
        val newPackageDir = File(srcDir, newPackagePath).canonicalFile

        if (!oldPackageDir.exists()) {
            logger.info("[ModdingTools] Old package dir does not exist: ${oldPackageDir.absolutePath}")
            return
        }

        if (oldPackageDir.absolutePath == newPackageDir.absolutePath) {
            return
        }

        if (newPackageDir.absolutePath.startsWith(oldPackageDir.absolutePath + File.separator) ||
            oldPackageDir.absolutePath.startsWith(newPackageDir.absolutePath + File.separator)) {
            logger.warn("[ModdingTools] Skipping move: nested directory structure detected")
            return
        }

        logger.lifecycle("[ModdingTools] Moving directory: ${oldPackageDir.relativeTo(srcDir)} -> ${newPackageDir.relativeTo(srcDir)}")

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
            if (oldPackageDir.listFiles()?.isEmpty() == true) {
                oldPackageDir.delete()
            }
        } else {
            val success = oldPackageDir.renameTo(newPackageDir)
            if (!success) {
                oldPackageDir.copyRecursively(newPackageDir, overwrite = true)
                oldPackageDir.deleteRecursively()
            }
        }

        cleanupEmptyDirectories(srcDir, oldGroup.replace('.', File.separatorChar))
    }

    private fun updateResourceFiles(resourcesDir: File, oldPackage: String, newPackage: String, oldModId: String, newModId: String) {
        resourcesDir.walkTopDown()
            .maxDepth(10)
            .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter {
            it.isFile && (it.name.endsWith(".mixins.json") || it.name == "fabric.mod.json" ||
                    it.name == "mods.toml" || it.name == "neoforge.mods.toml")
        }.forEach { file ->
            var content = file.readText()
            if (content.contains(oldPackage)) {
                content = content.replace(oldPackage, newPackage)
                file.writeText(content)
                logger.lifecycle("[ModdingTools] Updated resource: ${file.name}")
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
            logger.lifecycle("[ModdingTools] Renamed mixin file: ${mixinFile.name} -> $newFileName")
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
                    logger.lifecycle("[ModdingTools] Renamed $folderName folder: $oldModId -> $newModId")
                } else {
                    oldModDir.copyRecursively(newModDir, overwrite = true)
                    oldModDir.deleteRecursively()
                    logger.lifecycle("[ModdingTools] Renamed $folderName folder: $oldModId -> $newModId")
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
                logger.lifecycle("[ModdingTools] Updated service file content: ${serviceFile.name}")
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
                logger.lifecycle("[ModdingTools] Renamed service file: ${serviceFile.name} -> $newFileName")
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
                logger.info("[ModdingTools] Removing empty dir: ${currentDir.relativeTo(baseDir)}")
                currentDir.delete()
            }
            val parent = currentDir.parentFile
            if (parent == currentDir) break
            currentDir = parent
            depth++
        }
    }
}
