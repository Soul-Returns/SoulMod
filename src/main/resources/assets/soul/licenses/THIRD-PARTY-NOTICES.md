# Third-party notices

Soul is distributed under the GNU GPL, version 3 or later (`LICENSE_soul` in the root of this jar).
This file carries the notices that the licenses of the things Soul bundles or adapts ask for. The
list shown in the game (`/soul` → About → Used software) is the short form of it; both are kept up to
date together (`UsedSoftware.kt`, checked by `UsedSoftwareTest`).

Upstream license texts: `LGPL-2.1.txt`, `LGPL-3.0.txt` and the SIL OFL texts in `assets/soul/fonts/`
are in this jar; the GPL-3.0 is `LICENSE_soul`. The Hypixel Mod API jar nested in `META-INF/jars/`
carries its own MIT license file.

## Bundled or adapted

### devonian — GPL-3.0
https://github.com/Synnerz/devonian, by Synnerz and contributors. `assets/soul/dungeons/devonian_rooms.json`
(identical to upstream's `rooms.json`) and the block-id table in `DungeonLegacyBlocks.kt`
(upstream's `LegacyRegistry`); the secret-sync relay protocol is devonian's. Upstream is GPL-3.0
(its `LICENSE` and `fabric.mod.json`), which is why Soul as a whole is GPL. Details:
`assets/soul/dungeons/ATTRIBUTION.md`.

### SkyHanni — LGPL-2.1
https://github.com/hannibal002/SkyHanni, by hannibal002 and contributors. The scoreboard-title
list (`SkyblockState`), the rarity tints (`SkyblockRarity`), the SkyBlock level colors
(`InventoryInfoEntries`), the Precursor part list (`NucleusChat`) and the price-source and
sea-creature matching semantics were taken from or modeled on SkyHanni. Soul uses these copies under
the GNU GPL (LGPL-2.1 §3). License text: `LGPL-2.1.txt`.

### Skyblocker — LGPL-3.0
https://github.com/SkyblockerMod/Skyblocker, by the Skyblocker contributors. The nine Arrow Align
layouts (`ArrowAlignModel.LAYOUTS`, from `ArrowAlign.SOLUTIONS`), the 42 Metal Detector chest
offsets (`MetalDetectorSolver.OFFSETS`) and the `DUNGEON_SECRETS` message schema of the secret sync
are Skyblocker's. License text: `LGPL-3.0.txt`.
Its storage-screen interception and inventory API fields were consulted for compatibility and previews; no storage overlay code or assets were copied.

### Odin — BSD-3-Clause
https://github.com/odtheking/Odin. `assets/soul/shaders/core/round_rect.fsh` and `round_rect.vsh`
are adapted from Odin's rounded-rectangle shader (through the legacy Soul mod). The leap menu's class
table (home corner and priority per class) and its sorting (`features/dungeons/leap/LeapSort.kt`) are
ported from Odin's `LeapMenu.odinSorting` and `DungeonClass`. Terminal, device and boss-timer behavior
was checked against Odin; no other code is taken.

```
BSD 3-Clause License

Copyright (c) 2025, odtheking

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### NoFrills — GPL-3.0
https://github.com/WhatYouThing/NoFrills. Behavior reference for the device solvers and the inventory
buttons idea. No code or data is taken.

### SBCombo — no license published
https://github.com/Eval427/SBCombo, a ChatTriggers module by Eval, Debug, MPG1 and Altpapier. The Kill
Combo bonus table (`ComboData`) and the three chat patterns (`ComboTracker`) follow the module. The
numbers are Hypixel game mechanics. The repository has no license file, so no permission is on record;
the table is kept here with credit to the authors above by the maintainer's decision (2026-11-13).

### SkyCrypt, NotEnoughUpdates REPO, SkyHelper-Networth — MIT
- SkyCrypt, https://github.com/SkyCryptWebsite/SkyCrypt, Copyright (c) 2019-present Lea Seibert and
  other contributors: the slayer XP table (`XpTables`) and the cross-checks of the skill and
  Catacombs curves. Its storage API indexing was consulted for account/profile-scoped previews;
  no storage implementation was copied.
- NotEnoughUpdates REPO, https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO, Copyright (c)
  2020 Moulberry: the pet level XP table (`PetLevels`, `constants/pets.json`), and the forge and
  crafting recipes of the Search everywhere Forge tab (`items/*.json`), normalized and served by the
  Soul backend as `/recipes` and cached on disk by the mod (`recipe_catalog.json`).
- SkyHelper-Networth, https://github.com/Altpapier/SkyHelper-Networth, Copyright (c) 2022 Altpapier:
  the same pet table as published there, and the Catacombs class XP deltas. Its inventory API
  field layout was consulted for storage previews; no parser code was copied.
- msdf-atlas-gen, https://github.com/Chlumsky/msdf-atlas-gen, Copyright (c) 2020 - 2026 Viktor
  Chlumsky: generated the font atlases; the text shader (`msdf_text.fsh`) uses its median-of-three
  reconstruction.
- Hypixel Mod API, https://github.com/HypixelDev/ModAPI, bundled as a nested jar; its own license file
  is inside that jar.

```
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Fonts — SIL OFL 1.1
Inter (Copyright (c) 2016 The Inter Project Authors), Nunito (Copyright 2014 The Nunito Project
Authors) and Baloo 2 (Copyright 2019 The Baloo 2 Project Authors). The MSDF atlases under
`assets/soul/msdf/` and `assets/soul/textures/font/` are generated from them; the OFL texts are
`assets/soul/fonts/OFL-*.txt`. The Nunito and Baloo 2 TTFs are included as well.

### JLayer — LGPL-2.0-or-later
https://central.sonatype.com/artifact/javazoom/jlayer/1.0.1, by JavaZoom. Bundled unmodified as the
nested jar `META-INF/jars/jlayer-1.0.1.jar` (a normal replaceable library jar); decodes imported MP3
files. Soul uses it under the GNU GPL / LGPL-2.1 (`LGPL-2.1.txt`).

## Required at runtime, not bundled

Fabric Loader, Fabric API and Fabric Language Kotlin (with kotlinx.serialization and
kotlinx.coroutines): Apache-2.0, installed by the player next to Soul. Hypixel's public API and the
Elite SkyBlock lowest-BIN mirror are read by the Soul backend, not by the jar.

## Optional integrations, not bundled

Roughly Enough Items, https://github.com/shedaniel/RoughlyEnoughItems, Copyright (c) 2018-2023
shedaniel, MIT: Soul compiles against its API (`RoughlyEnoughItems-api-fabric`) and, only when the
player installs REI, registers exclusion zones so REI's item list stays off the storage overlay
(`StorageReiPlugin`). Cloth basic-math, https://github.com/shedaniel/cloth-basic-math, by
shedaniel, The Unlicense: the `Rectangle` type of that API. Neither is included in this jar; REI
brings both at runtime.

## Strategy references, not bundled

Ascent M7 Guides (Ascent [ADG]), maintained by kix0n on Discord (guide updated October 6, 2026):
reference for Archer routing, independently checked against the player's recorded runs. No guide
text, images or other assets are included.
