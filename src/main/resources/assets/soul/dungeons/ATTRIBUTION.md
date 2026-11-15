# Dungeon dataset attribution

`devonian_rooms.json` (room core-hashes → room id/name/type/secret metadata; byte-identical to
devonian's `assets/devonian/dungeons/rooms.json`) and the block→legacy-id table reproduced in
`com.soulreturns.features.dungeons.map.DungeonLegacyBlocks` (devonian's `LegacyRegistry`) are taken
from the **devonian** mod by Synnerz and contributors:

- Source: https://github.com/Synnerz/devonian
- License: **GNU GPL-3.0** (the `LICENSE` file and `fabric.mod.json` of upstream say `GPL-3.0`, with no
  "or later" grant; the files above stay under exactly that license, the rest of Soul is
  GPL-3.0-or-later, and the combined jar is distributed under GPL-3.0)
- Upstream credits Skytils' `state2legacy.json` and me4502 as the check for the block-id table; devonian
  states that most ids were not taken from it.

Because this data is GPL-3.0, the Soul mod as a whole is distributed under the GPL
(see the top-level `LICENSE`). Thanks to the devonian authors. The notices for everything else are in
`assets/soul/licenses/THIRD-PARTY-NOTICES.md`.
