package com.nyfaria.moddingtools

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

data class LibraryDef(
    val group: String,
    val artifact: String,
    val versionRef: String
)

data class PluginDef(
    val id: String,
    val version: String
)

data class VersionData(
    val versions: Map<String, String>,
    val libraries: Map<String, LibraryDef>,
    val bundles: Map<String, List<String>>,
    val plugins: Map<String, PluginDef>
)

object MinecraftVersions {
    private val gson = Gson()
    private val versionCache = mutableMapOf<String, VersionData>()
    private var baseData: VersionData? = null
    private var availableVersions: Set<String>? = null

    private const val DEFAULT_VERSIONS_URL = "https://raw.githubusercontent.com/Nyfaria/NyfsModdingTools/main/versions"
    private var customVersionsUrl: String? = null

    private val versionsBaseUrl: String
        get() = customVersionsUrl ?: DEFAULT_VERSIONS_URL

    private val cacheDir: File by lazy {
        val urlHash = versionsBaseUrl.hashCode().toString(16)
        File(System.getProperty("user.home"), ".gradle/caches/nyfs-modding-tools/versions/$urlHash").apply { mkdirs() }
    }
    private val CACHE_TTL_MS = TimeUnit.HOURS.toMillis(24)

    fun setVersionsUrl(url: String?) {
        if (customVersionsUrl != url) {
            customVersionsUrl = url?.trimEnd('/')
            versionCache.clear()
            baseData = null
            availableVersions = null
        }
    }

    fun getVersionsUrl(): String = versionsBaseUrl

    fun getVersionsFor(minecraftVersion: String): VersionData {
        return getVersionsForInternal(minecraftVersion, mutableSetOf())
    }

    private fun getVersionsForInternal(minecraftVersion: String, tried: MutableSet<String>): VersionData {
        versionCache[minecraftVersion]?.let { return it }

        if (minecraftVersion in tried) {
            val base = getBaseData()
            versionCache[minecraftVersion] = base
            return base
        }
        tried.add(minecraftVersion)

        val base = getBaseData()

        val jsonContent = loadVersionJson("$minecraftVersion.json")

        if (jsonContent != null) {
            val versionSpecific = parseVersionJson(jsonContent)
            val merged = mergeVersionData(base, versionSpecific)
            versionCache[minecraftVersion] = merged
            return merged
        }

        val fallback = getSupportedVersions().find {
            it != minecraftVersion && minecraftVersion.startsWith(it.substringBeforeLast('.'))
        } ?: getSupportedVersions().firstOrNull { it !in tried } ?: return base

        return getVersionsForInternal(fallback, tried)
    }

    private fun loadVersionJson(filename: String): String? {
        return fetchFromRemote(filename)
            ?: loadFromCache(filename)
            ?: loadFromResources(filename)
    }

    private fun fetchFromRemote(filename: String): String? {
        return try {
            val url = URL("$versionsBaseUrl/$filename")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"

            if (connection.responseCode == 200) {
                val content = connection.inputStream.bufferedReader().readText()
                saveToCache(filename, content)
                content
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadFromCache(filename: String): String? {
        val cacheFile = File(cacheDir, filename)
        if (cacheFile.exists()) {
            val age = System.currentTimeMillis() - cacheFile.lastModified()
            if (age < CACHE_TTL_MS) {
                return cacheFile.readText()
            }
        }
        return null
    }

    private fun saveToCache(filename: String, content: String) {
        try {
            File(cacheDir, filename).writeText(content)
        } catch (e: Exception) {
        }
    }

    private fun loadFromResources(filename: String): String? {
        return javaClass.getResourceAsStream("/versions/$filename")
            ?.bufferedReader()
            ?.readText()
    }

    private fun getBaseData(): VersionData {
        baseData?.let { return it }

        val baseJson = loadVersionJson("_base.json")

        val data = if (baseJson != null) {
            parseVersionJson(baseJson)
        } else {
            VersionData(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }

        baseData = data
        return data
    }

    private fun mergeVersionData(base: VersionData, override: VersionData): VersionData {
        return VersionData(
            versions = base.versions + override.versions,
            libraries = base.libraries + override.libraries,
            bundles = base.bundles + override.bundles,
            plugins = base.plugins + override.plugins
        )
    }

    private fun parseVersionJson(json: String): VersionData {
        val obj = gson.fromJson(json, JsonObject::class.java)

        val versions = mutableMapOf<String, String>()
        obj.getAsJsonObject("versions")?.entrySet()?.forEach { (key, value) ->
            versions[key] = value.asString
        }

        val libraries = mutableMapOf<String, LibraryDef>()
        obj.getAsJsonObject("libraries")?.entrySet()?.forEach { (key, value) ->
            val libObj = value.asJsonObject
            libraries[key] = LibraryDef(
                group = libObj.get("group").asString,
                artifact = libObj.get("artifact").asString,
                versionRef = libObj.get("version").asString
            )
        }

        val bundles = mutableMapOf<String, List<String>>()
        obj.getAsJsonObject("bundles")?.entrySet()?.forEach { (key, value) ->
            bundles[key] = value.asJsonArray.map { it.asString }
        }

        val plugins = mutableMapOf<String, PluginDef>()
        obj.getAsJsonObject("plugins")?.entrySet()?.forEach { (key, value) ->
            val pluginObj = value.asJsonObject
            plugins[key] = PluginDef(
                id = pluginObj.get("id").asString,
                version = pluginObj.get("version").asString
            )
        }

        return VersionData(
            versions = versions,
            libraries = libraries,
            bundles = bundles,
            plugins = plugins
        )
    }

    fun getSupportedVersions(): Set<String> {
        availableVersions?.let { return it }

        val versions = mutableSetOf<String>()
        val indexContent = loadVersionJson("index.txt")
            ?.lines()

        indexContent?.forEach { line ->
            if (line.isNotBlank() && !line.startsWith("_")) {
                versions.add(line.trim())
            }
        }

        availableVersions = versions
        return versions
    }

    fun clearCache() {
        versionCache.clear()
        baseData = null
        availableVersions = null
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
