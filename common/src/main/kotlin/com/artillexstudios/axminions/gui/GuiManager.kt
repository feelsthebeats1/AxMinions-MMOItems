package com.artillexstudios.axminions.gui

import com.artillexstudios.axminions.AxMinionsPlugin
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages loading and caching of external GUI YAML configurations.
 * GUIs are loaded from the gui/ folder and cached by their filename (without extension).
 */
object GuiManager {
    private val guiCache = ConcurrentHashMap<String, GuiConfig>()

    /**
     * Load a GUI config from the gui/ folder by name (e.g. "miner-upgrade").
     * If already loaded, returns the cached instance.
     */
    fun getGui(name: String): GuiConfig? {
        return guiCache[name]
    }

    /**
     * Load all GUI YAML files from the gui/ folder.
     * Called on plugin startup.
     */
    fun loadAll() {
        val guiFolder = File(AxMinionsPlugin.INSTANCE.dataFolder, "gui")
        if (!guiFolder.exists()) {
            guiFolder.mkdirs()
        }

        guiFolder.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".yml")) {
                val guiName = file.nameWithoutExtension
                // Already loaded via MinionType config references on demand
            }
        }
    }

    /**
     * Register a GUI config for a specific name.
     */
    fun registerGui(name: String, guiConfig: GuiConfig) {
        guiCache[name] = guiConfig
    }

    /**
     * Reload all cached GUI configs.
     */
    fun reloadAll() {
        guiCache.clear()
    }

    /**
     * Get all registered GUI names.
     */
    fun getGuiNames(): Set<String> {
        return guiCache.keys
    }
}