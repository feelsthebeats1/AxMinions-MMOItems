package com.artillexstudios.axminions.gui

import com.artillexstudios.axapi.config.Config
import com.artillexstudios.axapi.libs.boostedyaml.block.implementation.Section
import com.artillexstudios.axapi.libs.boostedyaml.settings.dumper.DumperSettings
import com.artillexstudios.axapi.libs.boostedyaml.settings.general.GeneralSettings
import com.artillexstudios.axapi.libs.boostedyaml.settings.loader.LoaderSettings
import com.artillexstudios.axapi.libs.boostedyaml.settings.updater.UpdaterSettings
import com.artillexstudios.axapi.libs.boostedyaml.dvs.versioning.BasicVersioning
import com.artillexstudios.axminions.api.AxMinionsAPI
import java.io.File
import java.io.InputStream

/**
 * Represents a GUI layout loaded from an external YAML file in the gui/ folder.
 * Each GUI file defines:
 * - title: inventory title
 * - size: inventory size (rows * 9)
 * - purpose: what type of GUI this is (minion, upgrade)
 * - items: list of clickable items with slot, material, name, lore, and action
 * 
 * Slot can be:
 *   - A single int: slot: 13
 *   - A list of ints: slot: [0, 1, 2, 3]
 *   - A comma-separated string: slot: "0, 1, 2, 3"
 */
class GuiConfig(name: String, defaults: InputStream) {
    private var config: Config

    val title: String
        get() = config.get("title", "")

    val size: Int
        get() = config.get("size", 27)

    val purpose: String
        get() = config.get("purpose", "minion")

    init {
        val guiFolder = File(AxMinionsAPI.INSTANCE.getAxMinionsDataFolder(), "gui")
        if (!guiFolder.exists()) {
            guiFolder.mkdirs()
        }

        config = Config(
            File(guiFolder, name),
            defaults,
            GeneralSettings.builder().setUseDefaults(false).build(),
            LoaderSettings.builder().setAutoUpdate(true).build(),
            DumperSettings.DEFAULT,
            UpdaterSettings.builder().setVersioning(BasicVersioning("config-version")).build()
        )
    }

    fun getItemsSection(): Section? {
        return config.getSection("items")
    }

    /**
     * Get all item entries from the config.
     */
    fun getItemEntries(): List<GuiItemEntry> {
        val itemsSection = getItemsSection() ?: return emptyList()
        val entries = mutableListOf<GuiItemEntry>()

        for (key in itemsSection.getRoutesAsStrings(false)) {
            val section = itemsSection.getSection(key.toString()) ?: continue
            val slots = parseSlots(section)
            val action = section.getString("action", "none")
            val guiRef = section.getString("gui", "")
            entries.add(GuiItemEntry(slots, action, guiRef, section))
        }

        return entries
    }

    /**
     * Parse slot(s) from an item section.
     * Supports:
     *   - slot: 13                    (single int)
     *   - slot: [0, 1, 2]             (YAML list)
     *   - slot: "0, 1, 2"            (comma-separated string)
     */
    private fun parseSlots(section: Section): List<Int> {
        val raw = section.get("slot")
        if (raw is List<*>) {
            return raw.mapNotNull { (it as? Number)?.toInt() }.filter { it in 0 until size }
        }
        if (raw is Number) {
            return listOf(raw.toInt()).filter { it in 0 until size }
        }
        val str = raw?.toString()?.trim()
        if (!str.isNullOrBlank()) {
            val parts = str.split(",").map { it.trim().toIntOrNull() }.filterNotNull()
            if (parts.isNotEmpty()) {
                return parts.filter { it in 0 until size }
            }
        }
        return emptyList()
    }

    /**
     * Represents a parsed GUI item entry with its slot(s), action, gui reference, and raw section.
     */
    data class GuiItemEntry(
        val slots: List<Int>,
        val action: String,
        val guiRef: String,
        val section: Section
    )

    fun getItemAction(slot: Int): String {
        for (entry in getItemEntries()) {
            if (slot in entry.slots) {
                return entry.action
            }
        }
        return "none"
    }

    fun reload() {
        config.reload()
    }

    fun getConfig(): Config {
        return config
    }
}
