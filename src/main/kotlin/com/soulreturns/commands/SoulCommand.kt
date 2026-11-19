package com.soulreturns.commands

import com.soulreturns.api.LocationApi
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.data.hypixel.PlayerIdentity
import com.soulreturns.data.hypixel.PlayerIdentityResolver
import com.soulreturns.data.hypixel.SoulStatsClient
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.hypixel.SkillCapsClient
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.hypixel.path
import com.soulreturns.data.skyblock.SkyblockSelfProfile
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.data.skyblock.TabWidgets
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.soulreturns.features.dev.BackendTest
import com.soulreturns.features.dev.CatalogDebug
import com.soulreturns.features.dev.DevOutput
import com.soulreturns.features.dev.PacketLogger
import com.soulreturns.features.dev.DevOutputScreen
import com.soulreturns.features.fishing.FishingDebug
import com.soulreturns.features.notifications.BackendNotificationCenter
import com.soulreturns.features.dungeons.DungeonRunState
import com.soulreturns.features.dungeons.map.DungeonDataset
import com.soulreturns.features.dungeons.map.DungeonGrid
import com.soulreturns.features.dungeons.map.DungeonMapConfigScreen
import com.soulreturns.features.mining.mineshaft.MineshaftSummary
import com.soulreturns.features.inventorybuttons.InventoryButtonsScreen
import com.soulreturns.features.search.SearchEverywhere
import com.soulreturns.features.keybinds.CommandKeybindScreen
import com.soulreturns.features.party.PartyManager
import com.soulreturns.features.profit.dragon.DragonDebug
import com.soulreturns.features.mining.metaldetector.MetalDetectorDebug
import com.soulreturns.features.mining.mineshaft.CorpseSpotsDebug
import com.soulreturns.features.profit.dragon.DragonSummary
import com.soulreturns.features.profit.dragon.gui.DragonSessionScreen
import com.soulreturns.features.profileviewer.SpvCommand
import com.soulreturns.features.sacks.SackDebug
import com.soulreturns.features.qol.HideFoliage
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.devaccess.DevAccess
import com.soulreturns.platform.presence.PresenceService
import com.soulreturns.ui.AutoUiScale
import com.soulreturns.ui.hud.alert.Alerts
import com.soulreturns.ui.hud.movable.HudMoveScreen
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.MojangUuid
import com.soulreturns.util.soulChat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.data.hypixel.HypixelProfileClient
import com.soulreturns.data.hypixel.calc.XpTables
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import kotlinx.coroutines.launch
import java.util.Locale
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.soulreturns.sound.SoundDebug
import com.soulreturns.sound.gui.SoundLibraryScreen
import com.soulreturns.sound.gui.SoundReplacerScreen
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource

/**
 * `/soul` — opens the config screen. `/soul config` does the same; `/soul reload` re-reads the
 * config file from disk.
 *
 * Opening a screen is deferred via `client.execute { }` so it runs after the chat screen (the one
 * the command was typed into) closes — setting it inline can conflict with chat teardown.
 */
object SoulCommand {
    /** Names per `/soul dev caps` scan — /skyblock/profiles allows 30 fetches per minute. */
    private const val MAX_CAP_SCAN = 8

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("soul")
                    .executes {
                        openConfig()
                        1
                    }
                    .then(
                        ClientCommands.literal("config").executes {
                            openConfig()
                            1
                        },
                    )
                    .then(
                        ClientCommands.literal("reload").executes {
                            ConfigManager.init()
                            AutoUiScale.pickIfUnchosen()
                            soulChat("Config reloaded from disk.")
                            1
                        },
                    )
                    .then(
                        ClientCommands.literal("map").executes {
                            openMapConfig()
                            1
                        },
                    )
                    .then(
                        ClientCommands.literal("gui").executes {
                            openMoveGui()
                            1
                        },
                    )
                    .then(
                        ClientCommands.literal("keybinds").executes {
                            CommandKeybindScreen.open()
                            1
                        },
                    )
                    .then(
                        ClientCommands.literal("buttons").executes {
                            InventoryButtonsScreen.open()
                            1
                        },
                    )
                    // `/soul search` opens Search everywhere; `/soul chests` stays from its island-chest days.
                    .then(ClientCommands.literal("search").executes { context -> context.source.client.execute { SearchEverywhere.open() }; 1 })
                    .then(ClientCommands.literal("chests").executes { context -> context.source.client.execute { SearchEverywhere.open() }; 1 })
                    .then(
                        ClientCommands.literal("sounds")
                            .executes {
                                SoundLibraryScreen.open()
                                1
                            }
                            .then(
                                ClientCommands.literal("replacer").executes {
                                    SoundReplacerScreen.open()
                                    1
                                },
                            ),
                    )
                    // `/soul spv …` — the same subtree SpvCommand registers at top level.
                    .then(SpvCommand.node())
                    // `/soul update [check|install|skip|close]` — the updater's chat buttons run these.
                    .then(com.soulreturns.features.updater.UpdateNotices.node())
                    .then(
                        ClientCommands.literal("dungeon").requires { DevAccess.unlocked }.executes {
                            dungeonDebug()
                            1
                        },
                    )
                    .then(
                        ClientCommands.literal("mineshaft").executes {
                            MineshaftSummary.print()
                            1
                        },
                    )
                    .then(
                        ClientCommands.literal("fishing").executes {
                            FishingDebug.summary()
                            1
                        },
                    )
                    .then(
                        ClientCommands.literal("dragons")
                            .executes {
                                DragonSessionScreen.open()
                                1
                            }
                            .then(
                                ClientCommands.literal("summary").executes {
                                    DragonSummary.print()
                                    1
                                },
                            ),
                    )
                    .then(
                        // Developer-only: absent from suggestions and execution until DevAccess is
                        // unlocked. Fabric copies the suggestion tree on join, so a mid-session
                        // unlock/lock shows in tab completion only after the next join; execution
                        // re-checks every time.
                        ClientCommands.literal("dev")
                            .requires { DevAccess.unlocked }
                            .executes {
                                DevOutput.begin("/soul dev")
                                DevOutput.line(
                                    "Debug commands: /soul dev <location|tab|scoreboard|party|foliage|backend|realtime|presence|profile|" +
                                        "soulstats|caps|fishing|dragons|catalogs|prices|sounds|notify|output>",
                                )
                                DevOutput.line("  /soul dev backend test [fresh] — live end-to-end backend check")
                                DevOutput.line("  /soul dev realtime — the shared realtime (SSE) connection")
                                DevOutput.line("  /soul dev profile [reload] — the loaded self SkyBlock profile snapshot")
                                DevOutput.line("  /soul dev tab [raw] — parsed tab widgets; raw includes escaped display names")
                                DevOutput.line("  /soul dev soulstats [name] — probe GET /player/<uuid>/soul-stats (self by default)")
                                DevOutput.line("  /soul dev caps [name…] — skill caps vs the raw profile data; several names = scan for a test subject")
                                DevOutput.line("  /soul dev presence [push] — presence state + the exact payload; 'push' forces one now")
                                DevOutput.line("  /soul dev notify <info|warning|error> <message> — fake a backend notification")
                                DevOutput.line("  /soul dev alert [seconds] [title] — a timed test alert on the shared alert HUD (own id per title, so several stack)")
                                DevOutput.line("  /soul dev fishing [unmatched|refresh|simulate <line>] — catalog, HUD gates, counters, chat injection")
                                DevOutput.line("  /soul dev dragons — dragon gates, catalogs, prices, counters and the last 20 loot stands")
                                DevOutput.line("  /soul dev catalogs [refresh] — every backend catalog this client mirrors")
                                DevOutput.line("  /soul dev prices [refresh] — the /prices catalog, both upstream halves and a few probes")
                                DevOutput.line("  /soul dev packets [start [all] [out] [filter…]|stop] — log every packet to logs/soul-packets/")
                                DevOutput.line("  /soul dev sounds — sound library, replacer rules, recently heard sounds (subcommands listed there)")
                                1
                            }
                            .then(
                                ClientCommands.literal("packets")
                                    .executes {
                                        PacketLogger.status()
                                        1
                                    }
                                    .then(
                                        ClientCommands.literal("start")
                                            .executes {
                                                PacketLogger.start("")
                                                PacketLogger.status()
                                                1
                                            }
                                            .then(
                                                ClientCommands.argument("options", StringArgumentType.greedyString())
                                                    .executes { ctx ->
                                                        PacketLogger.start(StringArgumentType.getString(ctx, "options"))
                                                        PacketLogger.status()
                                                        1
                                                    },
                                            ),
                                    )
                                    .then(
                                        ClientCommands.literal("stop").executes {
                                            if (!PacketLogger.stop()) soulChat("§7Packet log is not running.")
                                            1
                                        },
                                    ),
                            )
                            .then(
                                ClientCommands.literal("alert")
                                    .executes { devAlert(4, "Test alert"); 1 }
                                    .then(
                                        ClientCommands.argument("seconds", IntegerArgumentType.integer(1, 120))
                                            .executes { ctx -> devAlert(IntegerArgumentType.getInteger(ctx, "seconds"), "Test alert"); 1 }
                                            .then(
                                                ClientCommands.argument("title", StringArgumentType.greedyString())
                                                    .executes { ctx ->
                                                        devAlert(IntegerArgumentType.getInteger(ctx, "seconds"), StringArgumentType.getString(ctx, "title"))
                                                        1
                                                    },
                                            ),
                                    ),
                            )
                            .then(
                                ClientCommands.literal("location").executes {
                                    devLocation()
                                    1
                                },
                            )
                            // `/soul dev update <state>`: the updater UI with fake releases (spec mocks/updater/).
                            .then(com.soulreturns.features.updater.UpdateNotices.previewNode())
                            .then(
                                ClientCommands.literal("tab")
                                    .executes { devTab(false); 1 }
                                    .then(ClientCommands.literal("raw").executes { devTab(true); 1 }),
                            )
                            .then(
                                ClientCommands.literal("dragons").executes {
                                    DragonDebug.dump()
                                    1
                                },
                            )
                            .then(
                                ClientCommands.literal("metaldetector").executes {
                                    MetalDetectorDebug.dump()
                                    1
                                },
                            )
                            .then(
                                ClientCommands.literal("corpsespots").executes {
                                    CorpseSpotsDebug.dump()
                                    1
                                },
                            )
                            .then(
                                ClientCommands.literal("catalogs")
                                    .executes {
                                        CatalogDebug.catalogs()
                                        1
                                    }
                                    .then(
                                        ClientCommands.literal("refresh").executes {
                                            CatalogDebug.refreshCatalogs()
                                            1
                                        },
                                    ),
                            )
                            .then(
                                ClientCommands.literal("prices")
                                    .executes {
                                        CatalogDebug.prices()
                                        1
                                    }
                                    .then(
                                        ClientCommands.literal("refresh").executes {
                                            CatalogDebug.refreshPrices()
                                            1
                                        },
                                    ),
                            )
                            .then(
                                ClientCommands.literal("market")
                                    .executes {
                                        CatalogDebug.market()
                                        1
                                    }
                                    .then(
                                        ClientCommands.literal("refresh").executes {
                                            CatalogDebug.refreshMarket()
                                            1
                                        },
                                    ),
                            )
                            .then(
                                ClientCommands.literal("scoreboard").executes {
                                    devScoreboard()
                                    1
                                },
                            )
                            .then(
                                ClientCommands.literal("party").executes {
                                    devParty()
                                    1
                                },
                            )
                            .then(
                                ClientCommands.literal("foliage").executes {
                                    devFoliage()
                                    1
                                },
                            )
                            .then(
                                ClientCommands.literal("realtime").executes {
                                    BackendTest.realtime()
                                    1
                                },
                            )
                            .then(
                                ClientCommands.literal("sacks").executes {
                                    SackDebug.dump()
                                    1
                                },
                            )
                            .then(
                                ClientCommands.literal("sounds")
                                    .executes {
                                        SoundDebug.overview()
                                        1
                                    }
                                    .then(soundArg("import") { SoundDebug.import(it) })
                                    .then(soundArg("play") { SoundDebug.play(it, preview = false) })
                                    .then(soundArg("preview") { SoundDebug.play(it, preview = true) })
                                    .then(
                                        ClientCommands.literal("stop").executes {
                                            SoundDebug.stop()
                                            1
                                        },
                                    )
                                    .then(soundArg("rename") { SoundDebug.rename(it) })
                                    .then(soundArg("delete") { SoundDebug.delete(it) })
                                    .then(soundArg("fav") { SoundDebug.favorite(it) })
                                    .then(soundArg("rule") { SoundDebug.rule(it) })
                                    .then(
                                        ClientCommands.literal("recent")
                                            .executes {
                                                SoundDebug.recent(clear = false)
                                                1
                                            }
                                            .then(
                                                ClientCommands.literal("clear").executes {
                                                    SoundDebug.recent(clear = true)
                                                    1
                                                },
                                            ),
                                    )
                                    .then(
                                        ClientCommands.literal("catalog")
                                            .executes {
                                                SoundDebug.catalog("")
                                                1
                                            }
                                            .then(
                                                ClientCommands.argument("filter", StringArgumentType.greedyString())
                                                    .executes { ctx ->
                                                        SoundDebug.catalog(StringArgumentType.getString(ctx, "filter"))
                                                        1
                                                    },
                                            ),
                                    )
                                    .then(
                                        ClientCommands.literal("replacer")
                                            .then(ClientCommands.literal("on").executes { SoundDebug.replacer(true); 1 })
                                            .then(ClientCommands.literal("off").executes { SoundDebug.replacer(false); 1 }),
                                    ),
                            )
                            .then(
                                ClientCommands.literal("fishing")
                                    .executes {
                                        FishingDebug.dump()
                                        1
                                    }
                                    .then(
                                        ClientCommands.literal("unmatched").executes {
                                            FishingDebug.unmatched()
                                            1
                                        },
                                    )
                                    .then(
                                        ClientCommands.literal("refresh").executes {
                                            FishingDebug.refresh()
                                            1
                                        },
                                    )
                                    .then(
                                        ClientCommands.literal("simulate")
                                            .then(
                                                ClientCommands.argument("line", StringArgumentType.greedyString())
                                                    .executes { ctx ->
                                                        FishingDebug.simulate(StringArgumentType.getString(ctx, "line"))
                                                        1
                                                    },
                                            ),
                                    ),
                            )
                            .then(
                                ClientCommands.literal("profile")
                                    .executes {
                                        devSelfProfile()
                                        1
                                    }
                                    .then(
                                        ClientCommands.literal("reload").executes {
                                            DevOutput.begin("/soul dev profile reload")
                                            DevOutput.line("§7" + SkyblockSelfProfile.reload())
                                            1
                                        },
                                    ),
                            )
                            .then(
                                ClientCommands.literal("forge")
                                    .then(
                                        ClientCommands.literal("api")
                                            .then(
                                                ClientCommands.argument("slot", IntegerArgumentType.integer(1, 7))
                                                    .then(
                                                        ClientCommands.argument("id", StringArgumentType.word())
                                                            .then(
                                                                ClientCommands.argument("minutesAgo", IntegerArgumentType.integer(0, 100_000))
                                                                    .executes { ctx ->
                                                                        // A synthetic SkyBlock API answer for the forge fallback: one process in a slot.
                                                                        val now = System.currentTimeMillis()
                                                                        val slot = IntegerArgumentType.getInteger(ctx, "slot")
                                                                        val id = StringArgumentType.getString(ctx, "id")
                                                                        val started = now - IntegerArgumentType.getInteger(ctx, "minutesAgo") * 60_000L
                                                                        com.soulreturns.features.search.forge.Forge.applyApi(
                                                                            listOf(com.soulreturns.features.search.forge.ForgeProcesses.ApiProcess(slot, id, started)), now, null,
                                                                        )
                                                                        DevOutput.begin("/soul dev forge api")
                                                                        DevOutput.line("§7API answer applied: slot $slot $id started ${IntegerArgumentType.getInteger(ctx, "minutesAgo")} min ago")
                                                                        1
                                                                    },
                                                            ),
                                                    ),
                                            ),
                                    ),
                            )
                            .then(
                                ClientCommands.literal("soulstats")
                                    .executes {
                                        devSoulStats(null)
                                        1
                                    }
                                    .then(
                                        ClientCommands.argument("name", StringArgumentType.word())
                                            .executes { ctx ->
                                                devSoulStats(StringArgumentType.getString(ctx, "name"))
                                                1
                                            },
                                    ),
                            )
                            .then(
                                ClientCommands.literal("output").executes {
                                    // Reopen the last capture. Closing the window used to mean losing
                                    // the dump, since nothing else keeps it.
                                    if (DevOutput.isEmpty()) {
                                        soulChat("§8No dev output captured yet — run a §f/soul dev§8 command first.")
                                    } else {
                                        DevOutputScreen.open()
                                    }
                                    1
                                },
                            )
                            .then(
                                ClientCommands.literal("caps")
                                    .executes {
                                        devSkillCaps(null)
                                        1
                                    }
                                    .then(
                                        ClientCommands.argument("names", StringArgumentType.greedyString())
                                            .executes { ctx ->
                                                devSkillCaps(StringArgumentType.getString(ctx, "names"))
                                                1
                                            },
                                    ),
                            )
                            .then(
                                ClientCommands.literal("notify")
                                    .executes {
                                        DevOutput.begin("/soul dev notify")
                                        DevOutput.line("Usage: /soul dev notify <info|warning|error> <message>")
                                        1
                                    }
                                    .then(
                                        ClientCommands.argument("severity", StringArgumentType.word())
                                            .then(
                                                ClientCommands.argument("message", StringArgumentType.greedyString())
                                                    .executes { ctx ->
                                                        BackendNotificationCenter.test(
                                                            StringArgumentType.getString(ctx, "message"),
                                                            StringArgumentType.getString(ctx, "severity"),
                                                        )
                                                        1
                                                    },
                                            ),
                                    ),
                            )
                            .then(
                                ClientCommands.literal("presence")
                                    .executes {
                                        BackendTest.presence()
                                        1
                                    }
                                    .then(
                                        ClientCommands.literal("push").executes {
                                            DevOutput.begin("/soul dev presence push")
                                            PresenceService.forcePush()
                                            DevOutput.line("§7Forced a presence push §8(bypasses the debounce + dedupe) — " +
                                                "check §f/soul dev presence§8 for the result")
                                            1
                                        },
                                    ),
                            )
                            .then(
                                ClientCommands.literal("backend")
                                    .executes {
                                        BackendTest.info()
                                        1
                                    }
                                    .then(
                                        ClientCommands.literal("test")
                                            .executes {
                                                BackendTest.test(fresh = false)
                                                1
                                            }
                                            .then(
                                                ClientCommands.literal("fresh").executes {
                                                    BackendTest.test(fresh = true)
                                                    1
                                                },
                                            ),
                                    ),
                            ),
                    ),
            )
        }
    }

    /** `/soul dev sounds <name> <args…>`: the args stay one greedy string, parsed by [SoundDebug]. */
    private fun soundArg(
        name: String,
        run: (String) -> Unit,
    ): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal(name).then(
            ClientCommands.argument("args", StringArgumentType.greedyString()).executes { ctx ->
                run(StringArgumentType.getString(ctx, "args"))
                1
            },
        )

    private fun openConfig() {
        val client = Minecraft.getInstance()
        // 26.2: Minecraft.setScreen(Screen) → setScreenAndShow(Screen)
        client.execute { client.setScreenAndShow(SoulConfigScreen()) }
    }

    /** `/soul map` — opens the dedicated dungeon-map settings screen (live preview + options). */
    private fun openMapConfig() {
        val client = Minecraft.getInstance()
        client.execute { client.setScreenAndShow(DungeonMapConfigScreen()) }
    }

    /** `/soul gui` — opens the Move GUI (drag/scale/anchor HUD overlays). */
    private fun openMoveGui() {
        val client = Minecraft.getInstance()
        client.execute { client.setScreenAndShow(HudMoveScreen()) }
    }

    /** Timed alert in gold; its id is the title, so two titles stack. */
    private fun devAlert(seconds: Int, title: String) {
        Alerts.show("dev:$title", title, "Dev test alert, ${seconds}s", color = 0xFFFFAA00.toInt(), durationMs = seconds * 1000L)
    }

    /**
     * `/soul dev location` — the raw Hypixel Mod API location state (verifies gating modes) plus the
     * polled SkyBlock state, which is the one diagnostic every area/profile-gated feature wants.
     */
    private fun devLocation() {
        DevOutput.begin("/soul dev location")
        DevOutput.line("Location:")
        DevOutput.line("  serverId=${LocationApi.serverId}")
        DevOutput.line("  serverType=${LocationApi.serverType}")
        DevOutput.line("  mode=${LocationApi.mode}")
        DevOutput.line("  map=${LocationApi.map}")
        DevOutput.line("  inSkyblock=${LocationApi.inSkyblock} inDungeon=${LocationApi.inDungeon}")
        DevOutput.line(
            "  skyblock: onSkyblock=${SkyblockState.onSkyblock} profileType=${SkyblockState.profileType.name} " +
                "area=${SkyblockLocation.area ?: "-"} sub=${SkyblockLocation.subLocation ?: "-"} " +
                "profile=${SkyblockProfileState.current ?: "-"} profileId=${SkyblockProfileState.profileId ?: "-"}",
        )
    }

    private fun devTab(raw: Boolean) {
        DevOutput.begin("/soul dev tab" + if (raw) " raw" else "")
        val s = TabWidgets.snapshot
        DevOutput.line("Tab widgets: onSkyblock=${SkyblockState.onSkyblock} profile=${SkyblockProfileState.current ?: "-"}")
        DevOutput.line("  Last known on this profile; absent widgets keep their readings.")
        DevOutput.line("  Visible headers: ${TabWidgets.lastHeaders.joinToString().ifEmpty { "-" }}")
        val level = s.skyblockLevel
        DevOutput.line("  SB Level: ${level?.level ?: "-"}  XP: ${level?.xp ?: "-"}/${level?.xpForNext ?: "-"}")
        val bank = s.bank
        DevOutput.line("  Bank: ${bank?.text ?: "-"}  coins=${bank?.coins ?: "-"} personal=${bank?.personalCoins ?: "-"}")
        val interest = s.interest
        DevOutput.line("  Interest: ${interest?.timeText ?: "-"} (${interest?.seconds ?: "-"}s), payout=${interest?.amountText ?: "-"}")
        DevOutput.line("  Gems: ${s.gems ?: "-"}  Fairy Souls (island): ${s.fairySouls?.let { "${it.current}/${it.total}" } ?: "-"}")
        DevOutput.line("  Skills (${s.skills.size}), average=${s.skillAverage ?: "-"}:")
        for (skill in s.skills.values) DevOutput.line("    ${skill.name} ${skill.level}: ${if (skill.maxed) "MAX" else "${skill.percent ?: "-"}%"}")
        DevOutput.line("  Stats (${s.stats.size}):")
        for (stat in s.stats) DevOutput.line("    ${stat.name}: ${stat.value ?: "-"}  icon=${MessageDetector.escapeInvisible(stat.icon)}")
        val pet = s.pet
        DevOutput.line("  Pet: " + if (pet == null) { if (s.petKnown) "none selected" else "unknown (widget not read)" }
            else "[Lvl ${pet.level}] ${pet.name}  rarity=${pet.rarity?.name ?: "-"} skinned=${pet.skinned}")
        if (pet != null) DevOutput.line("    XP: ${pet.xpText ?: "-"}  current=${pet.xp ?: "-"} next=${pet.xpForNext ?: "-"} maxed=${pet.maxed}")
        if (raw) {
            DevOutput.line("Raw tab (${TabWidgets.lastLines.size} entries; blanks omitted):")
            for ((index, line) in TabWidgets.lastLines.withIndex()) {
                val text = MessageDetector.stripColorCodes(line)
                if (text.isNotBlank()) DevOutput.line("  [$index] ${MessageDetector.escapeInvisible(text)}")
            }
        }
    }

    /**
     * `/soul dev scoreboard` — the sidebar exactly as the client holds it: title, and every line
     * rebuilt the way vanilla's own renderer does (team prefix + owner name + team suffix). Non-ASCII
     * is escaped to `\uXXXX` so a glyph that only *looks* right in chat can be told apart from the one
     * the parsers actually match, which is otherwise invisible in a log.
     */
    private fun devScoreboard() {
        DevOutput.begin("/soul dev scoreboard")
        val connection = Minecraft.getInstance().player?.connection
        if (connection == null) {
            DevOutput.line("§7Not connected.")
            return
        }
        val scoreboard = connection.scoreboard()
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
        if (objective == null) {
            DevOutput.line("§7No sidebar objective (nothing displayed in DisplaySlot.SIDEBAR).")
            return
        }
        val entries = scoreboard.listPlayerScores(objective).sortedByDescending { it.value() }
        DevOutput.line("Sidebar (${entries.size} lines):")
        DevOutput.line("  title=${MessageDetector.escapeInvisible(MessageDetector.stripColorCodes(objective.displayName.string).trim())}")
        DevOutput.line("  profileType=${SkyblockState.profileType.name} sub=${SkyblockLocation.subLocation ?: "-"}")
        for (entry in entries) {
            val team = scoreboard.getPlayersTeam(entry.owner())
            val rendered =
                if (team == null) entry.ownerName().string else PlayerTeam.formatNameForTeam(team, entry.ownerName()).string
            val stripped = MessageDetector.stripColorCodes(rendered)
            DevOutput.line(
                "  [${entry.value()}] team=${team != null} " + MessageDetector.escapeInvisible(stripped),
            )
        }
    }

    /** `/soul dev party` — the tracked party state, where it came from and the tracker's counters. */
    private fun devParty() {
        DevOutput.begin("/soul dev party")
        DevOutput.line("Party:")
        DevOutput.line("  inParty=${PartyManager.isInParty()} leaderIsSelf=${PartyManager.isLeader()} size=${PartyManager.getPartySize()}")
        DevOutput.line("  leader=${PartyManager.getLeader()?.name}")
        val members = PartyManager.getMembers()
        DevOutput.line("  members (${members.size}): " + members.joinToString { "${it.name}(${it.role.name.lowercase()})" })
        val invites = PartyManager.getPendingInvites()
        if (invites.isNotEmpty()) {
            DevOutput.line("  invites: " + invites.joinToString { "${it.from}→${it.to}${if (it.outgoing) " (out)" else ""}" })
        }
        val d = PartyManager.diagnostics()
        DevOutput.line("  unnamed=${d["unnamed"]} source=${d["source"]} updated=${d["updated_ms_ago"]}ms ago uncertain=${d["uncertain"]}")
        DevOutput.line("  lines: ${d["lines"]}")
        DevOutput.line("  events: ${d["events"]}")
        DevOutput.line("  resync: ${d["resync"]}")
        DevOutput.line("  corrections: ${d["corrections"]}")
        DevOutput.line("  unknown lines: ${d["unknown_lines"]} ${d["unknown_samples"]}")
    }

    /** `/soul dev foliage` — hide-foliage toggle + location gating state. */
    private fun devFoliage() {
        DevOutput.begin("/soul dev foliage")
        DevOutput.line("Hide foliage:")
        DevOutput.line("  enabled=${cfg.general.hideFoliage} active=${HideFoliage.isActive()}")
        DevOutput.line("  gate: inSkyblock=${LocationApi.inSkyblock} mode=${LocationApi.mode} (gated when mode is dynamic/garden)")
    }

    /**
     * `/soul dev profile` — what [SkyblockSelfProfile] currently holds for the local player, plus the
     * identity/soul-stats state around it. The snapshot is otherwise invisible: it has no consumers
     * until a later lane adds one, so without this there is no way to tell a working self-load from a
     * silently failing one.
     */
    private fun devSelfProfile() {
        DevOutput.begin("/soul dev profile")
        DevOutput.line("Self SkyBlock profile:")
        SkyblockSelfProfile.status().split('\n').forEach { DevOutput.line("  $it") }
        val snapshot = SkyblockSelfProfile.snapshot
        if (snapshot != null) {
            val member = snapshot.member
            DevOutput.line(
                "  cata=${member.catacombsLevel.describe()} classAvg=" +
                    String.format(Locale.ROOT, "%.2f", member.classAverage),
            )
            val average = member.skillAverage
            DevOutput.line(
                "  skillAvg=" + (average?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-") +
                    " over ${member.skillCount} · slayerXp=${member.totalSlayerXp.toLong()}",
            )
            DevOutput.line("  sbLevel=" + (member.skyblockLevel?.describe() ?: "-"))
            dumpDungeonShape(member)
        }
        DevOutput.line("  name cache: ${PlayerIdentityResolver.cacheSize()} entr(ies)")
        DevOutput.line("  soul-stats: ${SoulStatsClient.lastOutcome}")
        DevOutput.line("  skill-caps: ${SkillCapsClient.lastOutcome}")
    }

    /**
     * The actual key names and value shapes under `dungeons.dungeon_types.catacombs`, plus what the
     * per-floor accessors made of them.
     *
     * Exists because "the Dungeons tab says no completed runs for an account with 66k secrets" is not
     * diagnosable from the outside: a renamed key, a count Hypixel serialized as a decimal and a
     * player who genuinely never finished a floor all look identical. Printing the raw shape next to
     * the parsed result says which of the three it is in one command.
     */
    private fun dumpDungeonShape(member: SkyblockProfileMember) {
        val catacombs = member.raw.path("dungeons", "dungeon_types", "catacombs")
        if (catacombs == null) {
            DevOutput.line("  dungeons: no dungeon_types.catacombs object")
            return
        }
        DevOutput.line("  dungeon_types.catacombs keys: ${catacombs.keys.joinToString(", ")}")
        for (key in listOf("tier_completions", "best_score", "fastest_time")) {
            val node = catacombs[key]
            if (node == null) {
                DevOutput.line("    $key: ABSENT")
                continue
            }
            if (node !is JsonObject) {
                DevOutput.line("    $key: not an object (${node::class.simpleName}) = $node")
                continue
            }
            val sample = node.entries.take(4).joinToString(", ") { (k, v) -> "$k=$v" }
            DevOutput.line("    $key: ${node.size} key(s) → $sample")
        }
        val floors = member.catacombsFloors
        DevOutput.line(
            "  parsed floors: " + floors.joinToString(" ") { "${it.displayName}=${it.completions}" } +
                " · totalRuns=${member.totalDungeonRuns}",
        )
    }

    /**
     * `/soul dev soulstats [name]` — call `GET /player/<uuid>/soul-stats` for yourself or someone
     * else and print what came back.
     *
     * [com.soulreturns.data.hypixel.SoulStatsClient] hides every failure by design (an un-opted-in
     * player must not produce an error), which makes it the one client in the mod whose behaviour is
     * otherwise unobservable. This is how you tell "not shared" from "endpoint not deployed" — and
     * how the privacy rule itself gets verified: ask about a player who has not opted in and the
     * answer must be nothing.
     */
    private fun devSoulStats(name: String?) {
        DevOutput.begin("/soul dev soulstats")
        SoulScope.io.launch {
            val target = if (name == null) {
                val self = Minecraft.getInstance().user
                PlayerIdentity(MojangUuid.undashed(self.profileId), self.name)
            } else {
                PlayerIdentityResolver.resolveDetailed(name).identity
            }
            if (target == null) {
                DevOutput.line("§cCould not resolve §f$name§c.")
                return@launch
            }
            val own = target.uuidUndashed.equals(
                MojangUuid.undashed(Minecraft.getInstance().user.profileId),
                ignoreCase = true,
            )
            DevOutput.line("Soul stats for §f${target.name}§r §8(${target.uuidUndashed}${if (own) ", self" else ""})")
            val envelope = SoulStatsClient.soulStats(target.uuidUndashed, ownProfile = own)
            DevOutput.line("  outcome: ${SoulStatsClient.lastOutcome}")
            if (envelope == null) {
                DevOutput.line("  §8nothing to render — a consumer would hide the feature entirely")
                // Only for the genuinely-empty case: a 404 or a 403 means something else entirely,
                // and explaining it as "no namespaces" would send you looking in the wrong place.
                if (SoulStatsClient.lastOutcomeCode == SoulStatsClient.CODE_NO_NAMESPACES) {
                    DevOutput.line("  §8the endpoint is live and we may see this player — no provider had anything for them")
                } else if (SoulStatsClient.lastOutcomeCode == SoulStatsClient.CODE_UNAVAILABLE) {
                    DevOutput.line("  §8404 here usually just means that player has never authenticated with the backend")
                }
                return@launch
            }
            DevOutput.line("  schemaVersion=${envelope.schemaVersion} shared=${envelope.shared}")
            for ((key, payload) in envelope.namespaces) {
                DevOutput.line("  §b$key§7: ${payload.keys.size} key(s) — ${payload.keys.joinToString().take(120)}")
            }
        }
    }

    /**
     * `/soul dev caps [name]` — the skill caps for every profile, from **both** directions: what
     * `GET /player/{uuid}/skyblock-caps` answered, and what the raw `/skyblock/profiles` payload says
     * underneath it.
     *
     * Both halves are printed because the two failure modes look identical from the screen. A cap that
     * does not arrive degrades to the published curve **silently and legitimately** — Taming simply
     * reads 60 for everyone — so "the endpoint never answered", "the profile ids did not match" and
     * "this player really has all ten sacrifices" are indistinguishable without seeing the raw list
     * next to the derived number.
     */
    private fun devSkillCaps(arg: String?) {
        DevOutput.begin("/soul dev caps")
        val names = arg?.split(' ', ',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (names.size > 1) {
            devSkillCapScan(names)
            return
        }
        val name = names.firstOrNull()
        SoulScope.io.launch {
            val target = if (name == null) {
                val self = Minecraft.getInstance().user
                PlayerIdentity(MojangUuid.undashed(self.profileId), self.name)
            } else {
                PlayerIdentityResolver.resolveDetailed(name).identity
            }
            if (target == null) {
                DevOutput.line("§cCould not resolve §f$name§c.")
                return@launch
            }
            DevOutput.line("Skill caps for §f${target.name}§r §8(${target.uuidUndashed})")

            val caps = SkillCapsClient.caps(target.uuidUndashed)
            DevOutput.line("  endpoint: ${SkillCapsClient.lastOutcome}")
            DevOutput.line("  profiles it answered for: " + (caps?.profileIds?.joinToString() ?: "§8none"))

            val fetch = HypixelProfileClient.profiles(target.uuidUndashed, HypixelProfileClient.INTENT_LOOKUP)
            if (fetch !is BackendResult.Ok) {
                DevOutput.line("  §cprofiles fetch failed: ${(fetch as BackendResult.Failure).message}")
                return@launch
            }
            for (profile in fetch.value.profiles) {
                val member = profile.member(target.uuidUndashed) ?: continue
                val resolved = caps?.forProfile(profile.profileId, profile.selected)
                DevOutput.line("  §b${profile.cuteName}§8 (${profile.profileId})${if (profile.selected) " §e·playing" else ""}")
                DevOutput.line("    resolved caps: " + (resolved?.toString() ?: "§8none — levelling against the published curve"))
                // The raw list the Taming cap is derived from, verbatim: the count is what decides the
                // cap, and seeing WHICH pets are in it is the only way to check a disputed number.
                val sacrificed = member.raw.path("pets_data", "pet_care")
                    ?.get("pet_types_sacrificed") as? JsonArray
                if (sacrificed == null) {
                    DevOutput.line("    pet_types_sacrificed: §8absent → taming cap 50")
                } else {
                    val types = sacrificed.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    val unique = types.distinct()
                    DevOutput.line("    pet_types_sacrificed: ${types.size} entr(ies), ${unique.size} unique → taming cap ${(50 + unique.size).coerceAtMost(60)}")
                    DevOutput.line("      §7" + unique.joinToString(", ").ifEmpty { "§8(empty)" })
                }
                val farmingPerk = member.raw.path("jacobs_contest", "perks")?.get("farming_level_cap")
                DevOutput.line("    farming_level_cap perk: ${farmingPerk ?: "§8absent"} → farming cap ${50 + ((farmingPerk as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0)}")
            }
        }
    }

    /**
     * `/soul dev caps <a> <b> <c>` — one line per player, flagging who is actually usable to TEST the
     * cap logic.
     *
     * A player only proves anything when a cap both **applies** (below 60) and **bites** (their XP is
     * past it) — a cap of 57 on an account with level-52 XP shows the same number either way. That is
     * the whole reason this exists: finding such an account by hand means opening profiles one at a
     * time and doing the comparison in your head.
     *
     * Capped at [MAX_CAP_SCAN] names because each one costs a resolve plus a profile fetch, and
     * `/skyblock/profiles` allows 30 per minute per token.
     */
    private fun devSkillCapScan(names: List<String>) {
        val targets = names.distinct().take(MAX_CAP_SCAN)
        if (targets.size < names.distinct().size) {
            DevOutput.line("§8Scanning the first $MAX_CAP_SCAN of ${names.distinct().size} names (30 profile fetches/min).")
        }
        SoulScope.io.launch {
            DevOutput.line("Scanning ${targets.size} player(s) for a cap test subject…")
            var found = 0
            for (raw in targets) {
                val identity = PlayerIdentityResolver.resolveDetailed(raw).identity
                if (identity == null) {
                    DevOutput.line("  §8$raw: unknown player")
                    continue
                }
                val fetch = HypixelProfileClient.profiles(identity.uuidUndashed, HypixelProfileClient.INTENT_LOOKUP)
                if (fetch !is BackendResult.Ok) {
                    DevOutput.line("  §8${identity.name}: ${(fetch as BackendResult.Failure).message}")
                    continue
                }
                val profile = fetch.value.pick(null)
                val member = profile?.member(identity.uuidUndashed)
                if (profile == null || member == null) {
                    DevOutput.line("  §8${identity.name}: no usable profile")
                    continue
                }
                val caps = SkillCapsClient.caps(identity.uuidUndashed)?.forProfile(profile.profileId, profile.selected)
                val parts = ArrayList<String>(2)
                var usable = 0
                for (key in listOf(SkillCapsClient.FARMING, SkillCapsClient.TAMING)) {
                    val def = XpTables.skill(key) ?: continue
                    val xp = member.skills.firstOrNull { it.def.key == key }?.experience ?: 0.0
                    val uncapped = def.curve.level(xp).level
                    val cap = caps?.capFor(key)
                    // "Bites" = the cap is below both the curve and the level their XP alone would buy.
                    val bites = cap != null && cap < def.curve.cap && uncapped > cap
                    if (bites) usable++
                    parts += "$key ${cap ?: "?"}${if (bites) "§a!" else ""}§7 (xp lv $uncapped)"
                }
                val verdict = when (usable) {
                    2 -> "§a◀ BOTH — use this one"
                    1 -> "§e◀ one of the two"
                    else -> ""
                }
                if (usable > 0) found++
                DevOutput.line("  §f${identity.name}§7 ${profile.cuteName}: " + parts.joinToString(" §8· §7") + " $verdict")
            }
            if (found == 0) {
                DevOutput.line("§8None of these has a cap that bites. A subject needs <10 pet sacrifices AND")
                DevOutput.line("§8Taming XP past that cap — most maxed accounts have all ten, most others")
                DevOutput.line("§8lack the XP. A hub lobby's tab list is the cheapest place to keep looking.")
            }
        }
    }

    /** `/soul dungeon` — dumps dungeon-map state for in-game debugging (also loads the dataset). */
    private fun dungeonDebug() {
        DevOutput.begin("/soul dungeon")
        DungeonDataset.ensureLoaded()
        DevOutput.line("Dungeon debug:")
        DevOutput.line("  location: server=${LocationApi.serverId} mode=${LocationApi.mode} inDungeon=${LocationApi.inDungeon}")
        DevOutput.line("  run: floor=${DungeonRunState.floor.shortName.ifEmpty { "none" }} inRun=${DungeonRunState.inRun} mimic=${DungeonRunState.mimicKilled}")
        DevOutput.line("  dataset: loaded=${DungeonDataset.loaded}")
        val players = DungeonRunState.players.filterNotNull()
        DevOutput.line("  players (${players.size}): " + players.joinToString { "${it.name}(${it.dungeonClass.displayName}${if (it.isDead) ",dead" else ""})" })
        val rooms = DungeonGrid.uniqueRooms()
        DevOutput.line("  rooms (${rooms.size}):")
        for (room in rooms) {
            DevOutput.line(
                "    ${room.roomName ?: "?"}#${room.roomId ?: "-"} type=${room.type} shape=${room.shape.wire} " +
                    "check=${room.checkmark} secrets=${room.secretsFound}/${room.secretsTotal} comps=${room.comps.size}",
            )
        }
    }
}
