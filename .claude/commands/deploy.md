---
description: Build the mod via buildAndCollect and install the JAR into the PrismLauncher mods folder
---

Build SoulMod and copy the produced JAR into the user's PrismLauncher Minecraft instance.

## Target

WSL path: `/mnt/c/Users/soul/AppData/Roaming/PrismLauncher/instances/26/minecraft/mods`

## Steps

1. **Build.** Run the WSL→Windows recipe from `CLAUDE.md` (no spaces around `&&`):

   ```bash
   cmd.exe /c "set JAVA_HOME=C:\Users\soul\.jdks\jbr-21.0.11&&gradlew.bat buildAndCollect"
   ```

   If the build fails, surface the error and stop — do not attempt to copy.

2. **Locate the JAR.** `buildAndCollect` writes one `soul-<modVersion>+<mcVersion>.jar` per MC version into `build/libs/<modVersion>/`. Read `mod.version` from `gradle.properties` and list `build/libs/<modVersion>/*.jar`. Currently this should be a single `soul-<modVersion>+1.21.11.jar`.

3. **Copy (overwriting if present).** Use `cp` — let it overwrite any existing file with the same name. Do NOT delete any other `soul-*.jar` in the target dir; the user deletes old versions manually.

   If `cp` fails (most likely because the game is running and Windows has the target file locked), report the error verbatim and stop. A typical lock failure shows up as `Permission denied` or `Text file busy`; surface whichever message `cp` actually returned.

4. **Verify the copy actually replaced the file.** `cp` over WSL's DrvFs to a Windows-locked NTFS file can occasionally exit `0` without having overwritten the target — the failure mode the user has seen when Minecraft is running with the mod loaded. Always compare modification timestamps:

   ```bash
   stat -c '%Y %n' <source-jar> <target-jar>
   ```

   The target's mtime must be `>=` the source's mtime. If the target is older than the source (or older than the build's start time), the copy did NOT take effect — surface the mismatch explicitly with both timestamps and tell the user the game is likely still holding the file open. Do not claim success in this case.

5. **Report what's there.** After a verified copy, run `ls -1 <target>/soul-*.jar 2>/dev/null` and show the user the current set of installed `soul-*.jar` files so they can see which old versions still need to be cleaned up after they close the game.

## Constraints

- Never delete any file in the target mods dir.
- Never run any post-copy actions (no game launch, no notifications).
- Don't run any other gradle tasks — only `buildAndCollect`.
- If `mod.version` in `gradle.properties` looks unexpected (empty, malformed), surface that and stop before building.
