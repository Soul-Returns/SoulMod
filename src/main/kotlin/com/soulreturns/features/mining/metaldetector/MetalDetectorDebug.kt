package com.soulreturns.features.mining.metaldetector

import com.soulreturns.config.cfg
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.features.dev.DevOutput
import java.time.Instant

/** `/soul dev metaldetector`: why the solver shows what it shows. */
object MetalDetectorDebug {
    fun dump() {
        DevOutput.begin("/soul dev metaldetector")
        DevOutput.line("§bGates")
        DevOutput.line("  enabled=§f${cfg.mining.metalDetector.enabled}§r sublocation=§f${SkyblockLocation.subLocation ?: "-"}§r active=§f${MetalDetector.isActive()}")
        val c = MetalDetector.center
        DevOutput.line("  center=§f${c?.joinToString(", ") ?: "- (no keepers matched yet)"}§r from=§f${MetalDetector.centerSource ?: "-"}")
        DevOutput.line("  candidates=§f${MetalDetector.candidates().size}")
        DevOutput.line("")
        val samples = MetalDetector.samples
        val n = samples.distinct().size
        DevOutput.line("§bRound")
        DevOutput.line("  pairs=§f${samples.size}§r distinct=§f$n§r allowance=§f${MetalDetectorSolver.allowedMisfits(n)} misfits")
        samples.takeLast(5).forEach { DevOutput.line("    harp (${it.x}, ${it.y}, ${it.z}) -> ${it.shown}m") }
        val sol = MetalDetector.solution
        DevOutput.line("  solvedBy=§f${MetalDetector.solvedBy ?: "-"}§r solved=§f${sol?.solved ?: "-"}§r possible=§f${sol?.possible ?: "-"}")
        DevOutput.line("")
        val list = MetalDetector.listSolution
        DevOutput.line("§bList (built-in + learned)")
        if (c == null) DevOutput.line("  needs the center") else top(list, c, n)
        DevOutput.line("")
        val free = MetalDetector.freeSolution
        DevOutput.line("§bNo-center search (absolute positions; only its solved chest is drawn)")
        if (free == null) {
            DevOutput.line("  not run")
        } else {
            DevOutput.line("  on ${free.pairs}/$n distinct pairs, ${MetalDetector.freeTookMs} ms, spots with fits=§f${free.ranked.size}")
            top(free, intArrayOf(0, 0, 0), free.pairs)
        }
        DevOutput.line("")
        val spots = MetalDetectorSpots.all()
        DevOutput.line("§bLearned spots (${spots.size}, not in list: ${MetalDetectorSpots.extras().size})")
        spots.entries.sortedByDescending { it.value.lastSeen }.forEach { (o, s) ->
            val tag = if (o in MetalDetectorSolver.OFFSETS) "" else " §e(extra)"
            DevOutput.line("  (${o.x}, ${o.y}, ${o.z}) x${s.count} first=${Instant.ofEpochMilli(s.firstSeen)} last=${Instant.ofEpochMilli(s.lastSeen)}$tag")
        }
    }

    /** The same diagnostics as a structure, for the dev bridge's `soul` provider. */
    fun state(): Map<String, Any?> {
        val samples = MetalDetector.samples
        val n = samples.distinct().size
        val sol = MetalDetector.solution
        val base = MetalDetector.solutionBase()
        fun world(o: ChestOffset) = listOf(base[0] + o.x, base[1] + o.y, base[2] + o.z)
        return linkedMapOf(
            "enabled" to cfg.mining.metalDetector.enabled,
            "sub_location" to SkyblockLocation.subLocation,
            "active" to MetalDetector.isActive(),
            "center" to MetalDetector.center?.toList(),
            "center_from" to MetalDetector.centerSource,
            "candidates" to MetalDetector.candidates().size,
            "pairs" to samples.size,
            "distinct" to n,
            "allowance" to MetalDetectorSolver.allowedMisfits(n),
            "last_samples" to samples.takeLast(5).map { linkedMapOf("x" to it.x, "y" to it.y, "z" to it.z, "shown" to it.shown, "tol" to it.tol) },
            "solved_by" to MetalDetector.solvedBy,
            "solved" to sol?.solved?.let { linkedMapOf("offset" to listOf(it.x, it.y, it.z), "world" to world(it)) },
            "possible" to sol?.possible?.map { world(it) },
            "top" to MetalDetector.listSolution?.ranked?.take(5)?.map { linkedMapOf("offset" to listOf(it.offset.x, it.offset.y, it.offset.z), "fits" to it.fits) },
        )
    }

    private fun top(sol: DetectorSolution?, c: IntArray?, n: Int) {
        val ranked = sol?.ranked ?: return DevOutput.line("  -")
        if (ranked.isEmpty()) return DevOutput.line("  -")
        ranked.take(5).forEach { s ->
            val world = c?.let { " (${it[0] + s.offset.x}, ${it[1] + s.offset.y}, ${it[2] + s.offset.z})" } ?: ""
            DevOutput.line("  (${s.offset.x}, ${s.offset.y}, ${s.offset.z})$world fits=§f${s.fits}/$n")
        }
    }
}
