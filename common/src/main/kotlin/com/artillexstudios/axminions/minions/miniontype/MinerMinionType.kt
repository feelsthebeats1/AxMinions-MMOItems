package com.artillexstudios.axminions.minions.miniontype

import com.artillexstudios.axapi.scheduler.Scheduler
import com.artillexstudios.axapi.scheduler.impl.FoliaScheduler
import com.artillexstudios.axminions.AxMinionsPlugin
import com.artillexstudios.axminions.api.AxMinionsAPI
import com.artillexstudios.axminions.api.config.Config
import com.artillexstudios.axminions.api.minions.Minion
import com.artillexstudios.axminions.api.minions.miniontype.MinionType
import com.artillexstudios.axminions.api.utils.LocationUtils
import com.artillexstudios.axminions.api.utils.MinionUtils
import com.artillexstudios.axminions.api.utils.fastFor
import com.artillexstudios.axminions.api.warnings.Warnings
import com.artillexstudios.axminions.minions.MinionTicker
import com.artillexstudios.axminions.utils.Enchantments
import com.artillexstudios.axminions.nms.NMSHandler
import dev.lone.itemsadder.api.CustomBlock
import me.kryniowesegryderiusz.kgenerators.Main
import net.Indyuce.mmoitems.MMOItems
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.inventory.DoubleChestInventory
import org.bukkit.inventory.FurnaceRecipe
import org.bukkit.inventory.ItemStack
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MinerMinionType : MinionType("miner", AxMinionsPlugin.INSTANCE.getResource("minions/miner.yml")!!, true) {
    companion object {
        private var asyncExecutor: ExecutorService? = null
        private val smeltingRecipes = ArrayList<FurnaceRecipe>()
        private val faces = arrayOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

        init {
            Bukkit.recipeIterator().forEachRemaining {
                if (it is FurnaceRecipe) {
                    smeltingRecipes.add(it)
                }
            }
        }
    }

    private var generatorMode = false
    private val whitelist = arrayListOf<Material>()
    private data class VoidDrop(val item: ItemStack, val weight: Int)
    private var voidDrops = listOf<VoidDrop>()
    private var voidTotalWeight = 0

    private fun loadVoidDrops(minion: Minion) {
        voidDrops = mutableListOf()
        voidTotalWeight = 0
        val dropSection = getSection("void-drops", minion.getLevel()) ?: return

        val parsed = mutableListOf<VoidDrop>()

        for (key in dropSection.keys) {
            val weight = dropSection.getInt(key.toString(), 100)
            if (weight <= 0) continue

            // Check if key contains ':' -> MMOItems format: TYPE:ID
            val keyStr = key?.toString() ?: continue
            if (keyStr.contains(":") && AxMinionsPlugin.integrations.mmoitemsIntegration) {
                val idx = keyStr.indexOf(":")
                val mmoTypeName = keyStr.substring(0, idx)
                val mmoId = keyStr.substring(idx + 1)

                // MMOItems.getItem(type: String?, id: String?) returns ItemStack?
                val mmoItemStack = MMOItems.plugin.getItem(mmoTypeName, mmoId)
                if (mmoItemStack != null) {
                    parsed.add(VoidDrop(mmoItemStack, weight))
                    continue
                }
            }

            // Vanilla material
            val material = Material.matchMaterial(keyStr.uppercase(Locale.ENGLISH))
            if (material != null) {
                parsed.add(VoidDrop(ItemStack(material, 1), weight))
            }
        }

        // Normalize weights if total > 100
        val totalWeight = parsed.sumOf { it.weight }
        if (totalWeight > 100) {
            val scale = 100.0 / totalWeight
            voidDrops = parsed.map { it.copy(weight = (it.weight * scale).toInt().coerceAtLeast(1)) }
        } else {
            voidDrops = parsed
        }
        voidTotalWeight = voidDrops.sumOf { it.weight }
        if (voidTotalWeight == 0) voidDrops = emptyList()
    }

    private fun rollVoidDrop(): ItemStack? {
        if (voidDrops.isEmpty()) return null
        val roll = Random().nextInt(voidTotalWeight)
        var cumulative = 0
        for (drop in voidDrops) {
            cumulative += drop.weight
            if (roll < cumulative) return drop.item.clone()
        }
        return voidDrops.last().item.clone()
    }

    override fun shouldRun(minion: Minion): Boolean {
        return MinionTicker.getTick() % minion.getNextAction() == 0L
    }

    override fun onToolDirty(minion: Minion) {
        val minionImpl = minion as com.artillexstudios.axminions.minions.Minion
        val mode = getConfig().getString("mode", "line").lowercase(Locale.ENGLISH)

        minionImpl.setRange(getDouble("range", minion.getLevel()))
        val tool = Enchantments.EFFICIENCY?.let { minion.getTool()?.getEnchantmentLevel(it)?.div(10.0) } ?: 0.1
        val efficiency = 1.0 - if (tool > 0.9) 0.9 else tool
        minionImpl.setNextAction((getLong("speed", minion.getLevel()) * efficiency).roundToInt())

        if (mode == "void") {
            loadVoidDrops(minion)
            return
        }

        generatorMode = getConfig().getString("break", "generator").equals("generator", true)
        whitelist.clear()
        getConfig().getStringList("whitelist").fastFor {
            whitelist.add(Material.matchMaterial(it.uppercase(Locale.ENGLISH)) ?: return@fastFor)
        }
    }

    override fun run(minion: Minion) {
        if (minion.getLinkedInventory() != null && minion.getLinkedInventory()?.firstEmpty() != -1) {
            Warnings.remove(minion, Warnings.CONTAINER_FULL)
        }

        if (minion.getLinkedChest() != null && minion.getLinkedInventory() != null) {
            val type = minion.getLinkedChest()!!.block.type
            if (type == Material.CHEST && minion.getLinkedInventory() !is DoubleChestInventory && hasChestOnSide(minion.getLinkedChest()!!.block)) {
                minion.setLinkedChest(minion.getLinkedChest())
            }

            if (type == Material.CHEST && minion.getLinkedInventory() is DoubleChestInventory && !hasChestOnSide(minion.getLinkedChest()!!.block)) {
                minion.setLinkedChest(minion.getLinkedChest())
            }

            if (type != Material.CHEST && type != Material.TRAPPED_CHEST && type != Material.BARREL) {
                minion.setLinkedChest(null)
            }
        }

        if (!minion.canUseTool()) {
            Warnings.NO_TOOL.display(minion)
            return
        }

        if (minion.getLinkedInventory()?.firstEmpty() == -1) {
            Warnings.CONTAINER_FULL.display(minion)
            return
        }

        Warnings.remove(minion, Warnings.NO_TOOL)

        val mode = getConfig().getString("mode", "line").lowercase(Locale.ENGLISH)

        // Void mode: generate items directly instead of breaking blocks
        if (mode == "void") {
            val drop = rollVoidDrop()
            if (drop != null) {
                minion.addToContainerOrDrop(drop)
                minion.setActions(minion.getActionAmount() + drop.amount)
                for (i in 0 until drop.amount) {
                    minion.damageTool()
                }
            }
            return
        }

        var amount = 0
        var xp = 0
        when (getConfig().getString("mode").lowercase(Locale.ENGLISH)) {
            "sphere" -> {
                LocationUtils.getAllBlocksInRadius(minion.getLocation(), minion.getRange(), false).fastFor { location ->
                    if (AxMinionsPlugin.integrations.kGeneratorsIntegration) {
                        val gen = Main.getPlacedGenerators().getLoaded(location)
                        if (gen != null) {
                            val possible = gen.isBlockPossibleToMine(location)

                            if (possible) {
                                minion.addToContainerOrDrop(
                                    gen.lastGeneratedObject.customDrops?.item?.clone() ?: return@fastFor
                                )
                                gen.scheduleGeneratorRegeneration()
                                return@fastFor
                            } else {
                                return@fastFor
                            }
                        }
                    }

                    val canBreak = if (generatorMode) {
                        MinionUtils.isStoneGenerator(location)
                    } else {
                        whitelist.contains(location.block.type)
                    }

                    if (canBreak) {
                        val block = location.block
                        val drops = block.getDrops(minion.getTool())
                        xp += NMSHandler.get().getExp(block, minion.getTool() ?: return)
                        drops.forEach {
                            amount += it.amount
                        }
                        val integration = AxMinionsAPI.INSTANCE.getIntegrations().getIslandIntegration()
                        integration?.handleBlockBreak(block)

                        minion.addToContainerOrDrop(drops)
                        block.type = Material.AIR
                    }
                }
            }

            "asphere" -> {
                if (Scheduler.get() !is FoliaScheduler) {
                    if (asyncExecutor == null) {
                        asyncExecutor = Executors.newFixedThreadPool(3)
                    }

                    asyncExecutor!!.execute {
                        LocationUtils.getAllBlocksInRadius(minion.getLocation(), minion.getRange(), false)
                            .fastFor { location ->
                                if (AxMinionsPlugin.integrations.kGeneratorsIntegration) {
                                    val gen = Main.getPlacedGenerators().getLoaded(location)
                                    if (gen != null) {
                                        val possible = gen.isBlockPossibleToMine(location)

                                        if (possible) {
                                            minion.addToContainerOrDrop(
                                                gen.lastGeneratedObject.customDrops?.item?.clone() ?: return@fastFor
                                            )
                                            gen.scheduleGeneratorRegeneration()
                                            return@fastFor
                                        } else {
                                            return@fastFor
                                        }
                                    }
                                }

                                val canBreak = if (generatorMode) {
                                    MinionUtils.isStoneGenerator(location)
                                } else {
                                    whitelist.contains(location.block.type)
                                }

                                if (canBreak) {
                                    Scheduler.get().run { task ->
                                        val block = location.block
                                        val drops = block.getDrops(minion.getTool())
                                        xp += NMSHandler.get().getExp(block, minion.getTool() ?: return@run)
                                        drops.forEach {
                                            amount += it.amount
                                        }
                                        val integration = AxMinionsAPI.INSTANCE.getIntegrations().getIslandIntegration()
                                        integration?.handleBlockBreak(block)

                                        minion.addToContainerOrDrop(drops)
                                        block.type = Material.AIR
                                    }
                                }
                            }
                    }
                } else {
                    val locCopy = minion.getLocation().clone()
                    locCopy.setX(locCopy.getBlockX().toDouble())
                    locCopy.setY(locCopy.getBlockY().toDouble())
                    locCopy.setZ(locCopy.getBlockZ().toDouble())
                    LocationUtils.getAllBlocksInRadius(locCopy, minion.getRange(), false)
                        .fastFor { location ->
                            if (AxMinionsPlugin.integrations.kGeneratorsIntegration) {
                                val gen = Main.getPlacedGenerators().getLoaded(location)
                                if (gen != null) {
                                    val possible = gen.isBlockPossibleToMine(location)

                                    if (possible) {
                                        minion.addToContainerOrDrop(
                                            gen.lastGeneratedObject.customDrops?.item?.clone() ?: return@fastFor
                                        )
                                        gen.scheduleGeneratorRegeneration()
                                        return@fastFor
                                    } else {
                                        return@fastFor
                                    }
                                }
                            }

                            val canBreak = if (generatorMode) {
                                MinionUtils.isStoneGenerator(location)
                            } else {
                                whitelist.contains(location.block.type)
                            }

                            if (canBreak) {
                                val block = location.block
                                val drops = block.getDrops(minion.getTool())
                                xp += NMSHandler.get().getExp(block, minion.getTool() ?: return)
                                drops.forEach {
                                    amount += it.amount
                                }
                                val integration = AxMinionsAPI.INSTANCE.getIntegrations().getIslandIntegration()
                                integration?.handleBlockBreak(block)

                                minion.addToContainerOrDrop(drops)
                                block.type = Material.AIR
                            }
                        }
                }
            }

            "line" -> {
                faces.fastFor {
                    val locCopy = minion.getLocation().clone()
                    locCopy.setX(locCopy.getBlockX().toDouble())
                    locCopy.setY(locCopy.getBlockY().toDouble())
                    locCopy.setZ(locCopy.getBlockZ().toDouble())
                    LocationUtils.getAllBlocksFacing(locCopy, minion.getRange(), it).fastFor { location ->
                        if (AxMinionsPlugin.integrations.kGeneratorsIntegration) {
                            if (Config.DEBUG()) {
                                println("KGenerators integration!")
                            }
                            val gen = Main.getPlacedGenerators().getLoaded(location)
                            if (gen != null) {
                                if (Config.DEBUG()) {
                                    println("Gen not null")
                                }
                                val possible = gen.isBlockPossibleToMine(location)

                                if (possible) {
                                    if (Config.DEBUG()) {
                                        println("Not possible")
                                    }
                                    minion.addToContainerOrDrop(
                                        gen.lastGeneratedObject.customDrops?.item?.clone() ?: return@fastFor
                                    )
                                    gen.scheduleGeneratorRegeneration()
                                    return@fastFor
                                } else {
                                    return@fastFor
                                }
                            }
                        } else {
                            if (Config.DEBUG()) {
                                println("Else")
                            }
                        }

                        val canBreak = if (generatorMode) {
                            MinionUtils.isStoneGenerator(location)
                        } else {
                            whitelist.contains(location.block.type)
                        }

                        if (canBreak) {
                            val block = location.block
                            val drops = block.getDrops(minion.getTool())
                            xp += NMSHandler.get().getExp(block, minion.getTool() ?: return)
                            drops.forEach { item ->
                                amount += item.amount
                            }
                            val integration = AxMinionsAPI.INSTANCE.getIntegrations().getIslandIntegration()
                            integration?.handleBlockBreak(block)

                            minion.addToContainerOrDrop(drops)
                            block.type = Material.AIR
                        }
                    }
                }
            }

            "face" -> {
                val locCopy = minion.getLocation().clone()
                locCopy.setX(locCopy.getBlockX().toDouble())
                locCopy.setY(locCopy.getBlockY().toDouble())
                locCopy.setZ(locCopy.getBlockZ().toDouble())
                LocationUtils.getAllBlocksFacing(locCopy, minion.getRange(), minion.getDirection().facing)
                    .fastFor { location ->
                        if (AxMinionsPlugin.integrations.kGeneratorsIntegration) {
                            val gen = Main.getPlacedGenerators().getLoaded(location)
                            if (gen != null) {
                                val possible = gen.isBlockPossibleToMine(location)

                                if (possible) {
                                    minion.addToContainerOrDrop(
                                        gen.lastGeneratedObject.customDrops?.item?.clone() ?: return@fastFor
                                    )
                                    gen.scheduleGeneratorRegeneration()
                                    return@fastFor
                                } else {
                                    return@fastFor
                                }
                            }
                        }
                        if (AxMinionsPlugin.integrations.itemsAdderIntegration) {
                            val block = CustomBlock.byAlreadyPlaced(location.block)
                            if (block !== null) {
                                val drops = block.getLoot(minion.getTool(), false)
                                drops.forEach {
                                    amount += it.amount
                                }
                                minion.addToContainerOrDrop(drops)
                                block.remove()
                                return@fastFor
                            }
                        }

                        val canBreak = if (generatorMode) {
                            MinionUtils.isStoneGenerator(location)
                        } else {
                            whitelist.contains(location.block.type)
                        }

                        if (canBreak) {
                            val block = location.block
                            val drops = block.getDrops(minion.getTool())
                            xp += NMSHandler.get().getExp(block, minion.getTool() ?: return)
                            drops.forEach {
                                amount += it.amount
                            }

                            val integration = AxMinionsAPI.INSTANCE.getIntegrations().getIslandIntegration()
                            integration?.handleBlockBreak(block)

                            minion.addToContainerOrDrop(drops)
                            block.type = Material.AIR
                        }
                    }
            }
        }

        val coerced =
            (minion.getStorage() + xp).coerceIn(0.0, minion.getType().getLong("storage", minion.getLevel()).toDouble())
        minion.setStorage(coerced)
        minion.setActions(minion.getActionAmount() + amount)

        for (i in 0 until amount) {
            minion.damageTool()
        }
    }
}