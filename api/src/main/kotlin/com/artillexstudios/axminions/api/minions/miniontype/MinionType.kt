package com.artillexstudios.axminions.api.minions.miniontype

import com.artillexstudios.axapi.config.Config
import com.artillexstudios.axapi.libs.boostedyaml.block.implementation.Section
import com.artillexstudios.axapi.libs.boostedyaml.dvs.versioning.BasicVersioning
import com.artillexstudios.axapi.libs.boostedyaml.settings.dumper.DumperSettings
import com.artillexstudios.axapi.libs.boostedyaml.settings.general.GeneralSettings
import com.artillexstudios.axapi.libs.boostedyaml.settings.loader.LoaderSettings
import com.artillexstudios.axapi.libs.boostedyaml.settings.updater.UpdaterSettings
import com.artillexstudios.axapi.utils.ItemBuilder
import com.artillexstudios.axminions.api.AxMinionsAPI
import com.artillexstudios.axminions.api.minions.Minion
import com.artillexstudios.axminions.api.utils.Keys
import com.artillexstudios.axminions.api.utils.fastFor
import java.io.File
import java.io.InputStream
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

abstract class MinionType(private val name: String, private val defaults: InputStream, private val autoUpdateConfig: Boolean) {
    val faces = arrayOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)
    private lateinit var config: Config

    constructor(name: String, defaults: InputStream) : this(name, defaults, false)

    fun load() {
        if (!autoUpdateConfig) {
            config = Config(
                File(AxMinionsAPI.INSTANCE.getAxMinionsDataFolder(), "/minions/$name.yml"),
                defaults,
                GeneralSettings.builder().setUseDefaults(false).build(),
                LoaderSettings.DEFAULT,
                DumperSettings.DEFAULT,
                UpdaterSettings.DEFAULT
            )
        } else {
            config = Config(
                File(AxMinionsAPI.INSTANCE.getAxMinionsDataFolder(), "/minions/$name.yml"),
                defaults,
                GeneralSettings.builder().setUseDefaults(false).build(),
                LoaderSettings.builder().setAutoUpdate(true).build(),
                DumperSettings.DEFAULT,
                UpdaterSettings.builder().setVersioning(BasicVersioning("config-version")).build()
            )
        }
        AxMinionsAPI.INSTANCE.getDataHandler().insertType(this)
    }

    fun getName(): String {
        return this.name
    }

    open fun onToolDirty(minion: Minion) {

    }

    open fun shouldRun(minion: Minion): Boolean {
        return true
    }

    /**
     * Get the GUI config file name for a specific purpose (e.g. "minion", "upgrade").
     * Reads from the minion config file under the "gui-configs" section.
     * Example config:
     *   gui-configs:
     *     minion: minion.yml
     *     upgrade: miner-upgrade.yml
     *
     * Falls back to "minion" for the main GUI, or "<minion_name>-<purpose>" for other purposes.
     */
    fun getGuiForPurpose(purpose: String): String {
        val guiConfigsSection = config.getSection("gui-configs")
        if (guiConfigsSection != null) {
            val guiName = guiConfigsSection.getString(purpose)
            if (guiName != null) {
                return guiName
            }
        }
        // Fallback: return a default name for the main GUI or use pattern "<minion_name>-<purpose>"
        return when (purpose) {
            "minion" -> "minion"
            else -> "${name}-$purpose"
        }
    }

    fun tick(minion: Minion) {
        if (!com.artillexstudios.axminions.api.config.Config.WORK_WHEN_OWNER_OFFLINE() && !minion.isOwnerOnline()) return
        if (!shouldRun(minion)) return

        minion.resetAnimation()
        run(minion)
    }

    fun getItem(level: Int = 1, actions: Long = 0, charge: Long = 0): ItemStack {
        val builder = ItemBuilder.create(
            config.getSection("item"),
            Placeholder.unparsed("level", level.toString()),
            Placeholder.unparsed("actions", actions.toString())
        )
        val item = builder.clonedGet()
        val meta = item.itemMeta!!
        meta.persistentDataContainer.set(Keys.MINION_TYPE, PersistentDataType.STRING, name)
        meta.persistentDataContainer.set(Keys.LEVEL, PersistentDataType.INTEGER, level)
        meta.persistentDataContainer.set(Keys.STATISTICS, PersistentDataType.LONG, actions)
        meta.persistentDataContainer.set(Keys.CHARGE, PersistentDataType.LONG, charge)
        item.itemMeta = meta
        return item
    }

    fun getConfig(): Config {
        return this.config
    }

    fun getString(key: String, level: Int): String {
        return get(key, level, "---", String::class.java)!!
    }

    fun getDouble(key: String, level: Int): Double {
        return get(key, level, 0.0, Double::class.java)!!
    }

    fun getLong(key: String, level: Int): Long {
        return get(key, level, 0, Long::class.java)!!
    }

    fun getSection(key: String, level: Int): Section? {
        return get(key, level, null, Section::class.java)
    }

    private fun <T> get(key: String, level: Int, defaultValue: T?, clazz: Class<T>): T? {
        var n = defaultValue

        config.getSection("upgrades").getRoutesAsStrings(false).forEach {
            if (it.toInt() > level) {
                return n
            }

            if (config.backingDocument.getAsOptional("upgrades.$it.$key", clazz).isEmpty) return@forEach

            n = config.get("upgrades.$it.$key")
        }

        return n
    }

    fun hasReachedMaxLevel(minion: Minion): Boolean {
        return !config.backingDocument.isSection("upgrades.${minion.getLevel() + 1}")
    }

    fun validateMMOItemsRequirements(logger: java.util.logging.Logger) {
        val upgradesSection = config.getSection("upgrades") ?: return
        val mmoItemsIntegration = try {
            Bukkit.getPluginManager().getPlugin("MMOItems") != null
        } catch (_: Exception) {
            false
        }
        if (!mmoItemsIntegration) return

        for (levelKey in upgradesSection.getRoutesAsStrings(false)) {
            val reqSection = config.backingDocument.getSection("upgrades.$levelKey.requirements.mmoitems") ?: continue
            for (key in reqSection.keys) {
                val itemSection = reqSection.getSection(key.toString()) ?: continue
                val type = itemSection.getString("type") ?: continue
                val id = itemSection.getString("id") ?: continue
                try {
                    val clazz = Class.forName("net.Indyuce.mmoitems.MMOItems")
                    val pluginField = clazz.getField("plugin")
                    val plugin = pluginField.get(null)
                    val getItemMethod = clazz.getMethod("getItem", String::class.java, String::class.java)
                    val item = getItemMethod.invoke(plugin, type, id)
                    if (item == null) {
                        logger.warning("[${getName().uppercase()}] Upgrade level $levelKey: MMOItems item '$type:$id' not found! Check your config.")
                    }
                } catch (_: Exception) {
                    logger.warning("[${getName().uppercase()}] Could not validate MMOItems item '$type:$id' (MMOItems may not be loaded yet).")
                }
            }
        }
    }

    fun hasChestOnSide(block: Block): Boolean {
        faces.fastFor {
            if (block.getRelative(it).type == Material.CHEST) {
                return true
            }
        }

        return false
    }

    abstract fun run(minion: Minion)
}