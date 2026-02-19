package com.nyfaria.moddingtools

import org.gradle.api.Project
import org.gradle.api.logging.Logger
import java.io.File
import java.util.Properties

object AutoPackageSync {

    private const val STATE_FILE_NAME = ".package-state"

    fun syncIfNeeded(project: Project) {
        val logger = project.logger
        val rootDir = project.rootDir
        val propsFile = File(rootDir, "gradle.properties")
        val stateFile = File(rootDir, STATE_FILE_NAME)

        if (!propsFile.exists()) {
            return
        }

        val currentGroup = project.findProperty("group")?.toString() ?: return
        val currentModId = project.findProperty("mod_id")?.toString() ?: return
        val expectedPackage = "$currentGroup.$currentModId"

        val detectedPackage = detectActualPackage(rootDir)

        if (detectedPackage == null) {
            saveState(stateFile, currentGroup, currentModId)
            return
        }

        if (detectedPackage == expectedPackage) {
            saveState(stateFile, currentGroup, currentModId)
            return
        }

        val parts = detectedPackage.split(".")
        if (parts.size < 2) {
            saveState(stateFile, currentGroup, currentModId)
            return
        }

        val oldModId = parts.last()
        val oldGroup = parts.dropLast(1).joinToString(".")

        logger.lifecycle("[ModdingTools] Detected package mismatch:")
        logger.lifecycle("[ModdingTools]   Current: $detectedPackage")
        logger.lifecycle("[ModdingTools]   Expected: $expectedPackage")
        logger.lifecycle("[ModdingTools] Refactoring packages...")

        refactorPackages(rootDir, oldGroup, oldModId, currentGroup, currentModId, logger)

        saveState(stateFile, currentGroup, currentModId)
        logger.lifecycle("[ModdingTools] Package refactoring complete!")
    }

    private fun detectActualPackage(rootDir: File): String? {
        val moduleNames = listOf("common", "fabric", "forge", "neoforge")

        for (moduleName in moduleNames) {
            val javaSrcDir = File(rootDir, "$moduleName/src/main/java")
            if (javaSrcDir.exists() && javaSrcDir.isDirectory) {
                val javaFiles = javaSrcDir.walkTopDown()
                    .maxDepth(20)
                    .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                    .filter { it.isFile && it.extension == "java" }
                    .take(100)
                    .toList()
                for (javaFile in javaFiles) {
                    val packageLine = javaFile.useLines { lines ->
                        lines.find { it.trim().startsWith("package ") }
                    }
                    if (packageLine != null) {
                        val pkg = packageLine.trim()
                            .removePrefix("package ")
                            .removeSuffix(";")
                            .trim()
                        val basePkg = pkg.split(".").take(3).joinToString(".")
                        if (basePkg.isNotEmpty() && basePkg.contains(".")) {
                            return basePkg
                        }
                    }
                }
            }
        }
        return null
    }

    private fun refactorPackages(
        rootDir: File,
        oldGroup: String,
        oldModId: String,
        newGroup: String,
        newModId: String,
        logger: Logger
    ) {
        val oldPackage = "$oldGroup.$oldModId"
        val newPackage = "$newGroup.$newModId"

        val moduleNames = listOf("common", "fabric", "forge", "neoforge")

        for (moduleName in moduleNames) {
            val moduleDir = File(rootDir, moduleName)
            if (!moduleDir.exists()) continue

            val javaSrcDir = File(moduleDir, "src/main/java")
            val kotlinSrcDir = File(moduleDir, "src/main/kotlin")

            listOf(javaSrcDir, kotlinSrcDir).forEach { srcDir ->
                if (srcDir.exists()) {
                    updateSourceFiles(srcDir, oldPackage, newPackage, logger)
                    movePackageDirectory(srcDir, oldPackage, newPackage, oldGroup, logger)
                }
            }

            val resourcesDir = File(moduleDir, "src/main/resources")
            if (resourcesDir.exists()) {
                updateResourceFiles(resourcesDir, oldPackage, newPackage, oldModId, newModId, logger)
            }
        }

        if (oldModId != newModId) {
            updateConstantsModId(rootDir, newPackage, oldModId, newModId, logger)
        }

        updateFabricEntrypoints(rootDir, logger)
    }

    private fun updateConstantsModId(
        rootDir: File,
        newPackage: String,
        oldModId: String,
        newModId: String,
        logger: Logger
    ) {
        val moduleNames = listOf("common", "fabric", "forge", "neoforge")
        val packagePath = newPackage.replace('.', File.separatorChar)

        for (moduleName in moduleNames) {
            val javaSrcDir = File(rootDir, "$moduleName/src/main/java")
            val kotlinSrcDir = File(rootDir, "$moduleName/src/main/kotlin")

            listOf(javaSrcDir, kotlinSrcDir).forEach { srcDir ->
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
        }
    }

    private fun updateFabricEntrypoints(rootDir: File, logger: Logger) {
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

    private fun updateSourceFiles(srcDir: File, oldPackage: String, newPackage: String, logger: Logger) {
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

    private fun movePackageDirectory(srcDir: File, oldPackage: String, newPackage: String, oldGroup: String, logger: Logger) {
        val oldPackagePath = oldPackage.replace('.', File.separatorChar)
        val newPackagePath = newPackage.replace('.', File.separatorChar)

        val oldPackageDir = File(srcDir, oldPackagePath).canonicalFile
        val newPackageDir = File(srcDir, newPackagePath).canonicalFile

        if (!oldPackageDir.exists()) return
        if (oldPackageDir.absolutePath == newPackageDir.absolutePath) return

        if (newPackageDir.absolutePath.startsWith(oldPackageDir.absolutePath + File.separator) ||
            oldPackageDir.absolutePath.startsWith(newPackageDir.absolutePath + File.separator)) {
            logger.warn("[ModdingTools] Skipping move: nested directory structure detected")
            return
        }

        logger.lifecycle("[ModdingTools] Moving: ${oldPackageDir.relativeTo(srcDir)} -> ${newPackageDir.relativeTo(srcDir)}")

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

    private fun updateResourceFiles(resourcesDir: File, oldPackage: String, newPackage: String, oldModId: String, newModId: String, logger: Logger) {
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

        updateServiceFiles(resourcesDir, oldPackage, newPackage, logger)
        renameMixinFiles(resourcesDir, oldModId, newModId, logger)
        renameAssetAndDataFolders(resourcesDir, oldModId, newModId, logger)
    }

    private fun renameMixinFiles(resourcesDir: File, oldModId: String, newModId: String, logger: Logger) {
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

    private fun renameAssetAndDataFolders(resourcesDir: File, oldModId: String, newModId: String, logger: Logger) {
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

    private fun updateServiceFiles(resourcesDir: File, oldPackage: String, newPackage: String, logger: Logger) {
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
                currentDir.delete()
            }
            val parent = currentDir.parentFile
            if (parent == currentDir) break
            currentDir = parent
            depth++
        }
    }

    private fun saveState(stateFile: File, group: String, modId: String) {
        val props = Properties()
        props.setProperty("group", group)
        props.setProperty("mod_id", modId)
        stateFile.outputStream().use { props.store(it, null) }
    }
}
