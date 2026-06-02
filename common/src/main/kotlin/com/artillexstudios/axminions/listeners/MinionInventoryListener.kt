package com.artillexstudios.axminions.listeners

import com.artillexstudios.axapi.utils.ItemBuilder
import com.artillexstudios.axapi.utils.StringUtils
import com.artillexstudios.axminions.AxMinionsPlugin
import com.artillexstudios.axminions.api.AxMinionsAPI
import com.artillexstudios.axminions.api.config.Config
import com.artillexstudios.axminions.api.config.Messages
import com.artillexstudios.axminions.api.events.MinionToolEvent
import com.artillexstudios.axminions.api.minions.Direction
import com.artillexstudios.axminions.api.minions.Minion
import com.artillexstudios.axminions.api.minions.miniontype.MinionTypes
import com.artillexstudios.axminions.api.utils.CoolDown
import com.artillexstudios.axminions.api.utils.Keys
import com.artillexstudios.axminions.api.utils.fastFor
import net.Indyuce.mmoitems.MMOItems
import java.util.Locale
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class MinionInventoryListener : Listener {
    private val coolDown = CoolDown<Player>()

    @EventHandler
    fun onInventoryDragEvent(event: InventoryDragEvent) {
        if (event.inventory.holder !is Minion) return

        event.isCancelled = true
    }

    @EventHandler
    fun onInventoryClickEvent(event: InventoryClickEvent) {
        val minion = event.inventory.holder as? Minion ?: return
        if (event.clickedInventory == null) return
        event.isCancelled = true
        val player = event.whoClicked as Player

        if (coolDown.contains(player)) {
            return
        }

        coolDown.add(player, 250)

        val allowedTools = arrayListOf<Material>()
        run breaking@{
            minion.getType().getConfig().getStringList("tool.material").fastFor {
                if (it.equals("*")) {
                    allowedTools.addAll(Material.entries)
                    return@breaking
                }

                allowedTools.add(Material.matchMaterial(it) ?: return@fastFor)
            }
        }

        if (event.clickedInventory == player.inventory && event.currentItem != null) {
            if (event.currentItem!!.type !in allowedTools) {
                player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.WRONG_TOOL()))
                return
            }

            val minionToolEvent = MinionToolEvent(minion, player, event.currentItem!!.clone(), minion.getTool()!!.clone())
            Bukkit.getPluginManager().callEvent(minionToolEvent)
            if (minionToolEvent.isCancelled) return

            if (minion.getTool()?.type != Material.AIR) {
                val current = minionToolEvent.newTool
                val tool = minionToolEvent.oldTool
                minion.setTool(current, false)
                minion.updateArmour()
                event.currentItem!!.amount = 0
                AxMinionsPlugin.dataHandler.saveMinion(minion)
                event.clickedInventory!!.addItem(tool)
            } else {
                minion.setTool(minionToolEvent.newTool, false)
                event.currentItem!!.amount = 0
                AxMinionsPlugin.dataHandler.saveMinion(minion)
            }

            minion.updateInventories()
            return
        }

        if (event.slot == AxMinionsAPI.INSTANCE.getConfig().get("gui.items.item.slot")) {
            if (minion.getTool()?.type == Material.AIR) return
            if (player.inventory.firstEmpty() == -1) {
                player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.ERROR_INVENTORY_FULL()))
                return
            }
            val tool = minion.getTool()?.clone() ?: return

            val minionToolEvent = MinionToolEvent(minion, player, ItemStack(Material.AIR), tool)
            Bukkit.getPluginManager().callEvent(minionToolEvent)
            if (minionToolEvent.isCancelled) return

            minion.setTool(ItemStack(Material.AIR), false)
            minion.updateArmour()
            AxMinionsPlugin.dataHandler.saveMinion(minion)
            val toolMeta = minionToolEvent.oldTool.itemMeta ?: return
            toolMeta.persistentDataContainer.remove(Keys.GUI)
            minionToolEvent.oldTool.setItemMeta(toolMeta)

            player.inventory.addItem(minionToolEvent.oldTool)
            minion.updateInventories()
            return
        }

        if (!(event.clickedInventory?.getItem(event.slot)?.hasItemMeta() ?: return)) {
            return
        }

        val meta = event.clickedInventory?.getItem(event.slot)?.itemMeta ?: return
        if (!meta.persistentDataContainer.has(Keys.GUI, PersistentDataType.STRING)) return
        val type = meta.persistentDataContainer.get(Keys.GUI, PersistentDataType.STRING) ?: return

        when {
            type.startsWith("open_gui") -> {
                val guiPurpose = type.removePrefix("open_gui_")
                if (guiPurpose == "upgrade") {
                    if (event.isRightClick && requiresMmoItemsUpgrade(minion)) {
                        minion.openGui(player, "upgrade")
                    } else {
                        performUpgrade(minion, player)
                    }
                } else if (guiPurpose.isNotBlank()) {
                    minion.openGui(player, guiPurpose)
                }
                return
            }

            type == "back" -> {
                minion.openGui(player, "minion")
                return
            }

            type == "rotate" -> {
                when (minion.getDirection()) {
                    Direction.NORTH -> minion.setDirection(Direction.WEST)
                    Direction.EAST -> minion.setDirection(Direction.NORTH)
                    Direction.SOUTH -> minion.setDirection(Direction.EAST)
                    Direction.WEST -> minion.setDirection(Direction.SOUTH)
                }
            }

            type == "link" -> {
                if (minion.getLinkedChest() != null) {
                    minion.setLinkedChest(null)
                    player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.LINK_UNLINK()))
                    return
                }

                player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.LINK_START()))
                LinkingListener.linking[player] = minion
                player.closeInventory()
            }

            type == "upgrade" -> {
                performUpgrade(minion, player)
            }

            type == "statistics" -> {
                val stored = minion.getStorage()

                if (stored == 0.0) {
                    return
                }

                if (minion.getType() == MinionTypes.getMinionTypes()["seller"]) {
                    AxMinionsPlugin.integrations.getEconomyIntegration()?.let {
                        minion.getOwner()?.let { player ->
                            it.giveBalance(player, stored)
                            minion.setStorage(0.0)
                        }
                    }
                } else {
                    player.giveExp(stored.toInt())
                    minion.setStorage(0.0)
                }

                AxMinionsPlugin.dataQueue.submit {
                    AxMinionsPlugin.dataHandler.saveMinion(minion)
                }
            }

            type == "charge" -> {

                if (event.isShiftClick) {
                    while (true) {
                        val chargeSeconds = (minion.getCharge() - System.currentTimeMillis()) / 1000

                        if ((Config.MAX_CHARGE() * 60) - chargeSeconds < Config.MINIMUM_CHARGE()) {
                            player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE_NOT_ENOUGH_TIME_PASSED()))
                            return
                        }

                        var chargeAmount = Config.CHARGE_AMOUNT()
                        var itemCharge = false
                        val section = Config.CHARGE_ITEMS()

                        for (key in section.keys) {
                            val item = ItemBuilder.create(section.getSection(key.toString())).get()
                            if (player.inventory.containsAtLeast(item, 1)) {
                                itemCharge = true
                                chargeAmount = section.getSection(key.toString()).getInt("charge")
                                player.inventory.removeItem(item)
                                break
                            }
                        }

                        if (Config.CHARGE_PRICE() <= 0 && !itemCharge) {
                            player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE_FAIL()))
                            return
                        }

                        if (!itemCharge) {
                            if ((AxMinionsPlugin.integrations.getEconomyIntegration()?.getBalance(player)
                                    ?: return) < Config.CHARGE_PRICE()
                            ) {
                                player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE_FAIL()))
                                return
                            }

                            AxMinionsPlugin.integrations.getEconomyIntegration()?.let {
                                minion.getOwner()?.let { player ->
                                    it.takeBalance(player, Config.CHARGE_PRICE())
                                }
                            }
                        }

                        if (chargeSeconds + chargeAmount > Config.MAX_CHARGE() * 60L) {
                            minion.setCharge(System.currentTimeMillis() + Config.MAX_CHARGE() * 60L * 1000L)
                            return
                        }

                        if (minion.getCharge() < System.currentTimeMillis()) {
                            minion.setCharge(System.currentTimeMillis() + chargeAmount * 1000)
                        } else {
                            minion.setCharge(minion.getCharge() + chargeAmount * 1000)
                        }

                        if (Messages.CHARGE().isNotBlank()) {
                            player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE()))
                        }
                    }
                } else {
                    val chargeSeconds = (minion.getCharge() - System.currentTimeMillis()) / 1000

                    if ((Config.MAX_CHARGE() * 60) - chargeSeconds < Config.MINIMUM_CHARGE()) {
                        player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE_NOT_ENOUGH_TIME_PASSED()))
                        return
                    }

                    var chargeAmount = Config.CHARGE_AMOUNT()
                    var itemCharge = false
                    val section = Config.CHARGE_ITEMS()

                    for (key in section.keys) {
                        val item = ItemBuilder.create(section.getSection(key.toString())).get()
                        if (player.inventory.containsAtLeast(item, 1)) {
                            itemCharge = true
                            chargeAmount = section.getSection(key.toString()).getInt("charge")
                            player.inventory.removeItem(item)
                            break
                        }
                    }

                    if (Config.CHARGE_PRICE() <= 0 && !itemCharge) {
                        player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE_FAIL()))
                        return
                    }

                    if (!itemCharge) {
                        if ((AxMinionsPlugin.integrations.getEconomyIntegration()?.getBalance(player)
                                ?: return) < Config.CHARGE_PRICE()
                        ) {
                            player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE_FAIL()))
                            return
                        }

                        AxMinionsPlugin.integrations.getEconomyIntegration()?.let {
                            minion.getOwner()?.let { player ->
                                it.takeBalance(player, Config.CHARGE_PRICE())
                            }
                        }
                    }

                    if (chargeSeconds + chargeAmount > Config.MAX_CHARGE() * 60L) {
                        minion.setCharge(System.currentTimeMillis() + Config.MAX_CHARGE() * 60L * 1000L)
                        return
                    }

                    if (minion.getCharge() < System.currentTimeMillis()) {
                        minion.setCharge(System.currentTimeMillis() + chargeAmount * 1000)
                    } else {
                        minion.setCharge(minion.getCharge() + chargeAmount * 1000)
                    }

                    if (Messages.CHARGE().isNotBlank()) {
                        player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.CHARGE()))
                    }
                }
            }
        }

        minion.updateInventories()
    }

    @EventHandler
    fun onInventoryCloseEvent(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? Minion ?: return

        holder.removeOpenInventory(event.inventory)
    }

    private fun performUpgrade(minion: Minion, player: Player) {
        val money = minion.getType().getDouble("requirements.money", minion.getLevel() + 1)
        val actions = minion.getType().getDouble("requirements.actions", minion.getLevel() + 1)

        if (minion.getType().hasReachedMaxLevel(minion)) {
            return
        }

        if (minion.getActionAmount() < actions) {
            sendFail(player)
            return
        }

        val economyIntegration = AxMinionsPlugin.integrations.getEconomyIntegration()
        if (economyIntegration != null && economyIntegration.getBalance(player) < money) {
            sendFail(player)
            return
        }

        val mmoItemRequirements = mutableListOf<MmoItemRequirement>()
        if (AxMinionsPlugin.integrations.mmoitemsIntegration) {
            val mmoItemsSection = minion.getType().getSection("requirements.mmoitems", minion.getLevel() + 1)
            if (mmoItemsSection != null) {
                for (key in mmoItemsSection.keys) {
                    val section = mmoItemsSection.getSection(key.toString()) ?: continue
                    val requiredType = section.getString("type") ?: continue
                    val requiredId = section.getString("id") ?: continue
                    val amount = section.getInt("amount", 1)

                    val mmoItemStack = MMOItems.plugin.getItem(requiredType, requiredId)
                    if (mmoItemStack == null) {
                        AxMinionsPlugin.INSTANCE.logger.warning("[Upgrade] MMOItems item '$requiredType:$requiredId' not found! Check minion config for ${minion.getType().getName()}.")
                        player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + "&cInvalid MMOItems requirement: &f$requiredType:$requiredId"))
                        return
                    }

                    val found = countMmoItems(player, requiredType, requiredId)
                    if (found < amount) {
                        sendFail(player)
                        return
                    }

                    mmoItemRequirements.add(MmoItemRequirement(requiredType, requiredId, amount))
                }
            }
        }

        economyIntegration?.takeBalance(player, money)

        for (requirement in mmoItemRequirements) {
            removeMmoItems(player, requirement)
        }

        if (Config.UPGRADE_SOUND().isNotBlank()) {
            player.playSound(
                player,
                Sound.valueOf(Config.UPGRADE_SOUND().uppercase(Locale.ENGLISH)),
                1.0f,
                1.0f
            )
        }

        minion.setLevel(minion.getLevel() + 1)
    }

    private fun requiresMmoItemsUpgrade(minion: Minion): Boolean {
        return AxMinionsPlugin.integrations.mmoitemsIntegration &&
            !minion.getType().hasReachedMaxLevel(minion) &&
            minion.getType().getSection("requirements.mmoitems", minion.getLevel() + 1) != null
    }

    private fun countMmoItems(player: Player, requiredType: String, requiredId: String): Int {
        var found = 0

        for (item in player.inventory.contents) {
            if (item == null || item.type.isAir) continue
            val itemType = MMOItems.getType(item)
            val itemId = MMOItems.getID(item)
            if (itemType != null && itemId != null && itemType.id == requiredType && itemId == requiredId) {
                found += item.amount
            }
        }

        return found
    }

    private fun removeMmoItems(player: Player, requirement: MmoItemRequirement) {
        var remaining = requirement.amount

        for (item in player.inventory.contents) {
            if (remaining <= 0) return
            if (item == null || item.type.isAir) continue

            val itemType = MMOItems.getType(item)
            val itemId = MMOItems.getID(item)
            if (itemType != null && itemId != null && itemType.id == requirement.type && itemId == requirement.id) {
                val toRemove = minOf(remaining, item.amount)
                item.amount -= toRemove
                remaining -= toRemove
            }
        }
    }

    private data class MmoItemRequirement(
        val type: String,
        val id: String,
        val amount: Int
    )

    private fun sendFail(player: Player) {
        when (Config.UPGRADE_FAIL()) {
            "title" -> {
                player.closeInventory()
                player.sendTitle(StringUtils.formatToString(Messages.UPGRADE_FAIL()), "", 10, 70, 20)
            }

            "subtitle" -> {
                player.closeInventory()
                player.sendTitle("", StringUtils.formatToString(Messages.UPGRADE_FAIL()), 10, 70, 20)
            }

            "actionbar" -> {
                player.closeInventory()
                player.spigot().sendMessage(
                    ChatMessageType.ACTION_BAR,
                    *TextComponent.fromLegacyText(StringUtils.formatToString(Messages.UPGRADE_FAIL()))
                )
            }

            else -> {
                player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.UPGRADE_FAIL()))
            }
        }
    }
}
