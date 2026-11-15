// Seeded fake SkyBlock data for mocks: same seed → same data on every reload. Text is § formatted the
// way Hypixel sends it (formats copied from real dev-bridge captures, 2026-11); names and words are real
// SkyBlock ones. Stat values are plausible, not authoritative: never port them as game data.
//   const sb = sidebar(7); const inv = playerInventory(3); const menu = skyblockMenuItems(1);
// Exports: sidebar(seed, opts?), tabWidgets(seed), tabList(seed), chatFeed(seed, n?), gearItem(seed, kind?),
//   items(seed, n?), playerInventory(seed), skyblockMenuItems(seed), sacks(seed), pets(seed, n?), pet(name, opts?),
//   PETS, PET_ITEMS, PET_LEVELS, PET_RARITY_OFFSET, petMaxLevel(name), petLevelXp(level, rarity, name),
//   petLevelNeed(level, rarity, name), petLevelOf(totalXp, rarity, name), petTab(p), petChat,
//   profiles(seed, n?), profile(seed), skillAverage(skills), SKILLS, NON_COSMETIC, PROFILE_NAMES, PLAYER_NAMES.

import { rng, num, short } from '../util.js';
import { item, rarityLine } from '../mc/items.js';

/** Generic player names; captures and mocks never show real ones. */
export const PLAYER_NAMES = ['Steve', 'Alex', 'Builder42', 'Fisher_Joe', 'MinerMax', 'LavaFisher', 'CropKing', 'Ender_Ella', 'Crystal_Kim', 'Slayer_Sam', 'Taco_Tom', 'Diana_Duck'];
/** Profile cute names Hypixel assigns (fruits). */
export const PROFILE_NAMES = ['Apple', 'Banana', 'Blueberry', 'Coconut', 'Cucumber', 'Grapes', 'Kiwi', 'Lemon', 'Lime', 'Mango', 'Orange', 'Papaya',
  'Peach', 'Pear', 'Pineapple', 'Pomegranate', 'Raspberry', 'Strawberry', 'Tomato', 'Watermelon', 'Zucchini'];
/** Skill → level cap (Farming 60 with Anita's perk; Taming 60 with its pet milestones). */
export const SKILLS = { Farming: 60, Mining: 60, Combat: 60, Foraging: 54, Fishing: 50, Enchanting: 60, Alchemy: 50, Taming: 60, Carpentry: 50, Runecrafting: 25, Social: 25 };
/** Skills in SkyBlock's "Skill Avg. (non-cosmetic)": everything except Carpentry, Runecrafting and Social. */
export const NON_COSMETIC = ['Farming', 'Mining', 'Combat', 'Foraging', 'Fishing', 'Enchanting', 'Alchemy', 'Taming'];
export const skillAverage = (skills) => NON_COSMETIC.reduce((a, k) => a + (skills[k] ?? 0), 0) / NON_COSMETIC.length;

const RANKS = [['§a[VIP]', 'a'], ['§a[VIP§6+§a]', 'a'], ['§b[MVP]', 'b'], ['§b[MVP§c+§b]', 'b'], ['§6[MVP§c++§6]', '6'], ['§7', '7']];
const rankName = (R, name = R.pick(PLAYER_NAMES)) => { const [tag, c] = R.pick(RANKS); return `${tag === '§7' ? '§7' : tag + ' '}§${c}${name}`; };
const roman = (n) => ['', 'I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X'][n] ?? String(n);
const ordinal = (n) => n + (n % 10 === 1 && n !== 11 ? 'st' : n % 10 === 2 && n !== 12 ? 'nd' : n % 10 === 3 && n !== 13 ? 'rd' : 'th');

// ------------------------------------------------------------------ sidebar
const SEASONS = ['Early Spring', 'Spring', 'Late Spring', 'Early Summer', 'Summer', 'Late Summer', 'Early Autumn', 'Autumn', 'Late Autumn', 'Early Winter', 'Winter', 'Late Winter'];
// Island → sublocations, as the scoreboard's "⏣" line shows them.
const AREAS = {
  Hub: ['Village', 'Bazaar Alley', 'Auction House', 'Community Center', 'Mountain', 'Wilderness', 'Coal Mine', 'Colosseum'],
  'Dwarven Mines': ['Royal Mines', 'Cliffside Veins', 'Rampart\'s Quarry', 'Upper Mines', 'Forge Basin', 'Dwarven Village', 'Great Ice Wall'],
  'Crystal Hollows': ['Magma Fields', 'Jungle', 'Mithril Deposits', 'Goblin Holdout', 'Precursor Remnants', 'Crystal Nucleus', 'Khazad-dûm'],
  'Crimson Isle': ['Scarleton', 'Dragontail', 'Burning Desert', 'Mystic Marsh', 'Blazing Volcano', 'Stronghold'],
  'The End': ['The End', 'Dragon\'s Nest', 'Void Sepulture', 'Zealot Bruiser Hideout'],
  'The Park': ['Birch Park', 'Spruce Woods', 'Dark Thicket', 'Savanna Woodland', 'Jungle Island'],
  'Dungeon Hub': ['Dungeon Hub'],
  'Glacite Mineshafts': ['Glacite Mineshafts'],
};
const OBJECTIVES = ['Talk to the Elder', 'Explore the Hub', 'Reach Mining Skill IV', 'Kill a Revenant Horror', 'Find the Jungle Temple', 'Complete a Commission',
  'Craft a Minion', 'Place a Crystal in the Nucleus', 'Visit the Crimson Isle'];

/**
 * Scoreboard sidebar `{ title, lines }` (top to bottom), like the real one: date + server, season day,
 * time, ⏣ sublocation, purse, bits, optional extras (heat, slayer quest, objective), and the url.
 * opts: `{ area? }` pins the island.
 */
export function sidebar(seed = 1, { area } = {}) {
  const R = rng(seed);
  const isl = area ?? R.pick(Object.keys(AREAS));
  const sub = R.pick(AREAS[isl] ?? [isl]);
  const hour = R.int(0, 23), min = R.int(0, 5) * 10;
  const h12 = ((hour + 11) % 12) + 1;
  const lines = [
    `§7${String(R.int(1, 12)).padStart(2, '0')}/${String(R.int(1, 28)).padStart(2, '0')}/26 §8m${R.int(10, 250)}${R.pick(['AC', 'AM', 'T', 'BD', 'CF'])}`,
    '',
    ` ${R.pick(SEASONS)} ${ordinal(R.int(1, 31))}`,
    ` §7${h12}:${String(min).padStart(2, '0')}${hour < 12 ? 'am' : 'pm'} ${hour >= 6 && hour < 18 ? '§e☀' : '§b☽'}`,
    ` §7⏣ §b${sub}`,
    '',
    `Purse: §6${num(R.int(1e3, 9e8))}`,
  ];
  if (R() < 0.8) lines.push(`Bits: §b${num(R.int(0, 40000))}`);
  if (isl === 'Crystal Hollows') lines.push('', `Heat: §c${R.int(0, 100)}♨`);
  if (R() < 0.4) lines.push('', 'Slayer Quest', `§${R.pick(['c', '5', 'e'])}${R.pick(['Revenant Horror', 'Tarantula Broodfather', 'Sven Packmaster', 'Voidgloom Seraph', 'Inferno Demonlord'])} ${roman(R.int(1, 5))}`, ` §7(§e${num(R.int(0, 3000))}§7/§c${num(3000)}§7) Combat XP`);
  else if (R() < 0.5) lines.push('', 'Objective', `§e${R.pick(OBJECTIVES)}`);
  lines.push('', '§ewww.hypixel.net');
  return { title: '§e§lSKYBLOCK', lines };
}

// ------------------------------------------------------------------ tab list
/**
 * Tab-list widgets `[{ id, title, lines }]` (§ lines, indented by one space like Hypixel's): Area,
 * Profile, Skills, Stats, Pet, Crystals, Commissions, Powders, Slayer, Bestiary-free basics.
 */
export function tabWidgets(seed = 1) {
  const R = rng(seed);
  const p = profile(seed);
  const pet = pets(seed, 1)[0];
  const skills = Object.keys(SKILLS).slice(0, 8);
  const crystals = ['Jade', 'Amber', 'Amethyst', 'Sapphire', 'Topaz'];
  const comms = ['Mithril Miner', 'Titanium Miner', 'Goblin Slayer', 'Glacite Walker Slayer', 'Amber Gemstone Collector', 'Sapphire Crystal Hunter', 'Treasure Hoarder Puncher'];
  return [
    { id: 'area', title: '§b§lArea: §7' + R.pick(Object.keys(AREAS)), lines: [` Server: §8mini${R.int(10, 250)}${R.pick(['AC', 'AM', 'T'])}`, ` Gems: §a${R.int(0, 900)}`] },
    { id: 'profile', title: `§e§lProfile: §a${p.cuteName}`, lines: [` SB Level: §8[§${R.pick(['4', 'c', '6', '5'])}${p.sbLevel}§8] §b${R.int(0, 99)}§3/§b100 XP`, ` Bank: §6${short(p.bank)}`, ` Interest: §e${R.int(1, 31)} Hours §7(§6${short(p.bank * 0.001)}§7)`] },
    { id: 'skills', title: '§e§lSkills:', lines: skills.filter(() => R() < 0.5).concat(skills).slice(0, 3).map((s) => { const lv = p.skills[s]; return ` ${s} ${lv}: ${lv >= SKILLS[s] ? '§c§lMAX' : `§3${R.int(0, 99)}.${R.int(0, 9)}%`}`; }) },
    { id: 'stats', title: '§e§lStats:', lines: [` Speed: §f✦${R.int(100, 500)}`, ` Strength: §c❁${R.int(100, 1500)}`, ` Crit Chance: §9☣${R.int(30, 150)}`, ` Crit Damage: §9☠${R.int(100, 1500)}`, ` Attack Speed: §e⚔${R.int(0, 100)}`] },
    { id: 'pet', title: '§e§lPet:', lines: petTab(pet) },
    { id: 'crystals', title: '§d§lCrystals:', lines: crystals.map((c) => ` ${c}: ${R.pick(['§a✔ Found', '§e✔ Placed', '§c✖ Not Found'])}`) },
    { id: 'commissions', title: '§9§lCommissions:', lines: [0, 1, 2, 3].map(() => ` ${R.pick(comms)}: ${R() < 0.3 ? '§aDONE' : `§e${R.int(0, 99)}%`}`) },
    { id: 'powders', title: '§9§lPowders:', lines: [` Mithril: §2${num(R.int(0, 3e6))}`, ` Gemstone: §d${num(R.int(0, 6e5))}`, ` Glacite: §b${num(R.int(0, 5e7))}`] },
  ];
}

/**
 * The whole tab list as Hypixel lays it out: 4 columns of 20 lines, column 1 "Players (n)" with
 * `[level] Name` lines, the others "Info" with widgets separated by blank lines (§ codes kept).
 */
export function tabList(seed = 1) {
  const R = rng(seed);
  const players = PLAYER_NAMES.slice(0, R.int(4, 12)).map((n) => `§8[§${R.pick(['4', 'c', '6', '5', '9', 'a'])}${R.int(40, 500)}§8] ${rankName(R, n)}`);
  const col = (head, body) => [head, ...body].slice(0, 20).concat(Array(20).fill('')).slice(0, 20);
  const widgets = tabWidgets(seed).flatMap((w) => [w.title, ...w.lines, '']);
  return [
    ...col(`         §a§lPlayers §f(${players.length})`, players),
    ...col('               §e§lInfo', widgets.slice(0, 19)),
    ...col('               §e§lInfo', widgets.slice(19, 38)),
    ...col('               §e§lInfo', widgets.slice(38, 57)),
  ];
}

// ------------------------------------------------------------------ chat
const DROPS = [['§6§lRARE DROP!', '§9Summoning Eye'], ['§6§lRARE DROP!', '§5Judgement Core'], ['§d§lCRAZY RARE DROP!', '§6Warden Heart'], ['§6§lRARE DROP!', '§9Enchanted Book'],
  ['§9§lVERY RARE DROP!', '§5Shard of the Shredded'], ['§6§lRARE DROP!', '§aEnchanted Ender Pearl'], ['§d§lPET DROP!', '§6Baby Yeti'], ['§6§lRARE DROP!', '§9Dragon Claw']];
const BAZAAR = ['Enchanted Diamond', 'Enchanted Mithril', 'Fine Ruby Gemstone', 'Enchanted Gold Block', 'Booster Cookie', 'Recombobulator 3000', 'Hot Potato Book', 'Enchanted Lava Bucket'];
const NPCS = [['Jacob', 'Your contest results are in!'], ['Kat', 'Your pet is ready to be picked up!'], ['Elle', 'Welcome to the Crimson Isle, adventurer.'],
  ['Kalhuiki Door Guardian', 'A Jungle Key! I will open the door for you.'], ['Bubu', 'You found some loot!'], ['Rhys', 'Bring me Mithril and I\'ll make it worth your while.']];
const PARTY_MSG = ['gg', 'warp pls', 'one sec', 'ready', 'dt need to restock', 'nice drop!', 'can someone party me again to warp', 'f7 next?', 'thanks for carry'];

/** `n` chat lines (§): party, guild, NPC dialogue, rare drops, Bazaar, Sacks, skill and Slayer messages. */
export function chatFeed(seed = 1, n = 20) {
  const R = rng(seed);
  const make = [
    () => `§9Party §8> ${rankName(R)}§f: ${R.pick(PARTY_MSG)}`,
    () => `§2Guild > ${rankName(R)} §e[${R.pick(['Member', 'Officer', 'Veteran'])}]§f: ${R.pick(PARTY_MSG)}`,
    () => { const [npc, msg] = R.pick(NPCS); return `§e[NPC] ${npc}§f: ${msg}`; },
    () => { const [tag, it] = R.pick(DROPS); return `${tag} ${it} §b(+${R.int(50, 400)}% §b✯ Magic Find§b)`; },
    () => `§6[Bazaar] §eBuy Order Setup! §a${R.int(1, 2240)}§7x §f${R.pick(BAZAAR)} §7for §6${num(R.int(1e4, 5e7))} coins§7.`,
    () => `§6[Bazaar] §eYour §6Sell Offer §efor §a${R.int(1, 2240)}§7x §f${R.pick(BAZAAR)} §ewas filled!`,
    () => `§6[Sacks] §a+${num(R.int(1, 9000))}§e items.§8 (Last ${R.int(5, 30)}s.)`,
    () => `§aYou earned §2${R.int(5, 40)} GEXP §afrom playing SkyBlock!`,
    () => `§a§lSLAYER QUEST COMPLETE!`,
    () => `§eNew day! §bYour §2${R.pick(['Sky Mall', 'Lottery'])} §ebuff changed!`,
    () => `§bFriend > §a${R.pick(PLAYER_NAMES)} §ejoined.`,
  ];
  return Array.from({ length: n }, () => R.pick(make)());
}

// ------------------------------------------------------------------ items
// Stat colors as SkyBlock shows them in lore (captured 2026-11).
const STAT_COLOR = { Damage: 'c', Strength: 'c', 'Crit Chance': '9', 'Crit Damage': '9', 'Attack Speed': 'e', Ferocity: 'c', Intelligence: 'b', Health: 'c',
  Defense: 'a', Speed: 'f', 'True Defense': 'f', 'Magic Find': 'b', 'Mining Speed': '6', 'Mining Fortune': '6', Pristine: '5', 'Fishing Speed': 'b',
  'Sea Creature Chance': '3', 'Foraging Fortune': '6' };
const PCT = new Set(['Crit Chance', 'Crit Damage', 'Attack Speed', 'Sea Creature Chance']);
// [name, sbId, mcId, rarity, type, stats, ability?] — base stats roughly as in game.
const GEAR = {
  sword: [
    ['Hyperion', 'HYPERION', 'iron_sword', 'LEGENDARY', 'DUNGEON SWORD', { Damage: 260, Strength: 150, Intelligence: 350, Ferocity: 30 }, ['Wither Impact', 'Teleports §a10 blocks §7ahead of you. Then implode dealing damage to nearby enemies.', 300]],
    ['Aspect of the End', 'ASPECT_OF_THE_END', 'diamond_sword', 'RARE', 'SWORD', { Damage: 100, Strength: 100 }, ['Instant Transmission', 'Teleport §a8 blocks §7ahead of you and gain §a+50 §f✦ Speed §7for §a3 seconds§7.', 50]],
    ['Aspect of the Void', 'ASPECT_OF_THE_VOID', 'diamond_shovel', 'EPIC', 'SWORD', { Damage: 120, Strength: 100 }, ['Instant Transmission', 'Teleport §a8 blocks §7ahead of you and gain §a+50 §f✦ Speed §7for §a3 seconds§7.', 45]],
    ['Livid Dagger', 'LIVID_DAGGER', 'iron_sword', 'LEGENDARY', 'DUNGEON SWORD', { Damage: 210, Strength: 60, 'Crit Chance': 100, 'Crit Damage': 50, 'Attack Speed': 50 }, ['Throw', 'Throw your dagger at your enemies!', 150]],
    ['Giant\'s Sword', 'GIANTS_SWORD', 'iron_sword', 'LEGENDARY', 'DUNGEON SWORD', { Damage: 500 }, ['Giant\'s Slam', 'Slam your sword into the ground dealing §c100,000 §7damage to nearby enemies.', 100]],
    ['Flower of Truth', 'FLOWER_OF_TRUTH', 'poppy', 'LEGENDARY', 'DUNGEON SWORD', { Damage: 160, Strength: 300 }, ['Heat-Seeking Rose', 'Shoots a rose that ricochets between enemies.', 0]],
  ],
  bow: [
    ['Terminator', 'TERMINATOR', 'bow', 'LEGENDARY', 'DUNGEON BOW', { Damage: 310, Strength: 50, 'Crit Damage': 250, 'Attack Speed': 40 }, ['Salvation', 'Can be cast after landing §63 §7hits. Shoot a beam, penetrating up to §e5 §7enemies.', 0]],
    ['Juju Shortbow', 'JUJU_SHORTBOW', 'bow', 'EPIC', 'BOW', { Damage: 310, Strength: 40, 'Crit Chance': 10, 'Crit Damage': 110 }, null],
    ['Last Breath', 'LAST_BREATH', 'bow', 'LEGENDARY', 'DUNGEON BOW', { Damage: 225, Strength: 25, 'Crit Damage': 60 }, null],
  ],
  pickaxe: [
    ['Pickonimbus 2000', 'PICKONIMBUS', 'diamond_pickaxe', 'EPIC', 'PICKAXE', { Damage: 30, 'Mining Speed': 750 }, ['Tunnel Vision', 'Increases the chances of triggering rare occurrences by §a40% §7for §a30s§7.', 0]],
    ['Titanium Drill DR-X355', 'TITANIUM_DRILL_1', 'prismarine_shard', 'EPIC', 'DRILL', { Damage: 75, 'Mining Speed': 1150, 'Mining Fortune': 75 }, null],
    ['Divan\'s Drill', 'DIVAN_DRILL', 'prismarine_shard', 'MYTHIC', 'DRILL', { Damage: 90, 'Mining Speed': 1850, 'Mining Fortune': 175 }, null],
  ],
  axe: [['Treecapitator', 'TREECAPITATOR_AXE', 'golden_axe', 'EPIC', 'AXE', { Damage: 85 }, null]],
  rod: [
    ['Rod of the Sea', 'ROD_OF_THE_SEA', 'fishing_rod', 'LEGENDARY', 'FISHING ROD', { Damage: 150, Strength: 50, 'Fishing Speed': 50, 'Sea Creature Chance': 6 }, null],
    ['Hellfire Rod', 'HELLFIRE_ROD', 'fishing_rod', 'LEGENDARY', 'FISHING ROD', { Damage: 220, Strength: 85, 'Fishing Speed': 100, 'Sea Creature Chance': 4 }, null],
  ],
  helmet: [['Necron\'s Helmet', 'POWER_WITHER_HELMET', 'player_head', 'LEGENDARY', 'DUNGEON HELMET', { Health: 180, Defense: 100, Strength: 40, 'Crit Damage': 30, Intelligence: 10 }, null],
    ['Divan\'s Helmet', 'DIVAN_HELMET', 'player_head', 'LEGENDARY', 'HELMET', { Health: 200, Defense: 130, 'Mining Fortune': 50, 'Mining Speed': 80 }, null]],
  chestplate: [['Necron\'s Chestplate', 'POWER_WITHER_CHESTPLATE', 'leather_chestplate', 'LEGENDARY', 'DUNGEON CHESTPLATE', { Health: 260, Defense: 140, Strength: 40, 'Crit Damage': 30, Intelligence: 10 }, null],
    ['Superior Dragon Chestplate', 'SUPERIOR_DRAGON_CHESTPLATE', 'leather_chestplate', 'LEGENDARY', 'CHESTPLATE', { Health: 150, Defense: 190, Strength: 10, 'Crit Chance': 2, 'Crit Damage': 10, Intelligence: 25, Speed: 3 }, null]],
  leggings: [['Necron\'s Leggings', 'POWER_WITHER_LEGGINGS', 'leather_leggings', 'LEGENDARY', 'DUNGEON LEGGINGS', { Health: 230, Defense: 125, Strength: 40, 'Crit Damage': 30, Intelligence: 10 }, null]],
  boots: [['Necron\'s Boots', 'POWER_WITHER_BOOTS', 'leather_boots', 'LEGENDARY', 'DUNGEON BOOTS', { Health: 145, Defense: 85, Strength: 40, 'Crit Damage': 30, Intelligence: 10 }, null]],
};
// Armor dye colors of leather pieces (Necron red, Superior Dragon yellow).
const DYED = { POWER_WITHER: '#e7413c', SUPERIOR_DRAGON: '#f2df11' };
const REFORGES = { sword: ['Fabled', 'Withered', 'Heroic', 'Spicy', 'Sharp', 'Suspicious'], bow: ['Spiritual', 'Precise', 'Rapid', 'Hasty'], pickaxe: ['Fleet', 'Auspicious', 'Lustrous', 'Glacial'],
  axe: ['Earthy'], rod: ['Lucky', 'Salty', 'Treacherous', 'Pitchin\''], helmet: ['Ancient', 'Necrotic', 'Giant', 'Jaded'], chestplate: ['Ancient', 'Necrotic', 'Giant', 'Renowned'],
  leggings: ['Ancient', 'Necrotic', 'Giant', 'Renowned'], boots: ['Ancient', 'Necrotic', 'Giant', 'Fierce'] };
const ENCHANTS = { sword: ['Sharpness', 'Critical', 'Ender Slayer', 'Giant Killer', 'Execute', 'First Strike', 'Looting', 'Luck', 'Syphon', 'Vampirism', 'Venomous', 'Thunderlord'],
  bow: ['Power', 'Overload', 'Chance', 'Cubism', 'Dragon Tracer', 'Snipe', 'Infinite Quiver', 'Piercing'], pickaxe: ['Efficiency', 'Fortune', 'Compact', 'Pristine', 'Experience', 'Smelting Touch'],
  axe: ['Efficiency', 'Experience'], rod: ['Angler', 'Caster', 'Frail', 'Luck of the Sea', 'Lure', 'Magnet', 'Spiked Hook'],
  armor: ['Protection', 'Growth', 'Rejuvenate', 'Thorns', 'Feather Falling', 'Depth Strider', 'True Protection'] };
const ULTIMATES = { sword: 'Ultimate Wise', bow: 'Duplex', pickaxe: 'Flowstate', rod: 'Flash', armor: 'Legion' };

/**
 * A SkyBlock gear Item with reforge, stars, stats, enchants, ability and rarity line in Hypixel's
 * lore order. `kind`: sword, bow, pickaxe, axe, rod, helmet, chestplate, leggings, boots (default: random).
 */
export function gearItem(seed = 1, kind) {
  const R = rng(seed);
  kind ??= R.pick(Object.keys(GEAR));
  const [base, sbId, mcId, rarity0, type, stats, ability] = R.pick(GEAR[kind]);
  const recomb = R() < 0.4;
  const rarity = recomb ? Object.keys(RC)[Math.min(5, Object.keys(RC).indexOf(rarity0) + 1)] : rarity0;
  const reforge = R.pick(REFORGES[kind]);
  const stars = R() < 0.6 ? R.int(1, 5) : 0, master = stars === 5 && R() < 0.5 ? R.int(1, 5) : 0;
  const name = `§${RC[rarity]}${reforge} ${base}${stars ? ' §6' + '✪'.repeat(stars) : ''}${master ? '§c' + '➊➋➌➍➎'[master - 1] : ''}`;
  const lore = [];
  if (kind === 'pickaxe') lore.push(`§8Breaking Power ${R.int(8, 10)}`, '');
  for (const [k, v] of Object.entries(stats)) {
    if (!v) continue;
    const bonus = R() < 0.5 ? ` §9(+${Math.round(v * 0.1)}${PCT.has(k) ? '%' : ''})` : '';
    lore.push(`§7${k}: §${STAT_COLOR[k] ?? 'a'}+${num(v)}${PCT.has(k) ? '%' : ''}${bonus}`);
  }
  lore.push('');
  const pool = ENCHANTS[kind] ?? ENCHANTS.armor;
  const ench = pool.filter(() => R() < 0.6).map((e) => `§9${e} ${roman(R.int(4, 7))}`);
  const ult = ULTIMATES[kind] ?? (['helmet', 'chestplate', 'leggings', 'boots'].includes(kind) ? ULTIMATES.armor : null);
  if (ult && R() < 0.7) ench.unshift(`§d§l${ult} ${roman(R.int(1, 5))}`);
  for (let i = 0; i < ench.length; i += 3) lore.push(ench.slice(i, i + 3).join(', '));
  if (ench.length) lore.push('');
  if (ability) {
    const [an, desc, mana] = ability;
    lore.push(`§6Ability: ${an}  §e§lRIGHT CLICK`, `§7${desc}`);
    if (mana) lore.push(`§8Mana Cost: §3${mana}`);
    lore.push('');
  }
  if (type.startsWith('DUNGEON')) lore.push('§7§4☠ §cRequires §5The Catacombs Floor VII Completion§c.');
  lore.push(rarityLine(rarity, type, { recomb }));
  const dyed = Object.entries(DYED).find(([p]) => sbId.startsWith(p))?.[1];
  const shape = { helmet: 'helmet', chestplate: 'chestplate', leggings: 'leggings', boots: 'boots' }[kind];
  return item({ id: sbId, mcId: `minecraft:${mcId}`, name, lore, glint: ench.length > 0, icon: dyed && shape ? { kind: shape, color: dyed } : undefined });
}

/** `n` random gear Items. */
export const items = (seed = 1, n = 9) => Array.from({ length: n }, (_, i) => gearItem(seed * 101 + i));

const stack = (mcId, name, n, lore = []) => item({ mcId: `minecraft:${mcId}`, name, lore, count: n });
/** Player inventory `{ hotbar, main, armor: [head, chest, legs, feet], offhand }` for playerScreen/chestScreen. */
export function playerInventory(seed = 1) {
  const R = rng(seed);
  const menu = item({ id: 'SKYBLOCK_MENU', mcId: 'minecraft:nether_star', name: '§aSkyBlock Menu §7(Click)', lore: ['§7View all of your SkyBlock progress,', '§7including your Skills, Collections,', '§7Recipes, and more!', '', '§eClick to open!'] });
  const hotbar = [gearItem(seed, 'sword'), gearItem(seed + 1, 'bow'), gearItem(seed + 2, 'pickaxe'), gearItem(seed + 3, 'rod'),
    stack('ender_pearl', '§fEnder Pearl', R.int(1, 16)), null, R() < 0.5 ? stack('cookie', '§6Booster Cookie', 1, ['', rarityLine('LEGENDARY')]) : null, null, menu];
  const fillers = [() => stack('diamond', '§aEnchanted Diamond', R.int(1, 64), ['', rarityLine('UNCOMMON')]), () => stack('gold_nugget', '§aEnchanted Gold', R.int(1, 64), ['', rarityLine('UNCOMMON')]),
    () => stack('ender_pearl', '§fEnder Pearl', R.int(1, 16)), () => gearItem(R.int(1, 1e6)), () => stack('paper', '§9Hot Potato Book', 1, ['', rarityLine('EPIC')]),
    () => item({ id: 'ROUGH_RUBY_GEM', mcId: 'minecraft:player_head', name: '§fRough Ruby Gemstone', count: R.int(1, 64), lore: ['', rarityLine('COMMON')], icon: { kind: 'gem', color: '#ff5555' } })];
  const main = Array.from({ length: 27 }, () => (R() < 0.45 ? R.pick(fillers)() : null));
  return { hotbar, main, armor: [gearItem(seed + 4, 'helmet'), gearItem(seed + 5, 'chestplate'), gearItem(seed + 6, 'leggings'), gearItem(seed + 7, 'boots')], offhand: null };
}

/**
 * The SkyBlock Menu's own items at their real slots (captured 2026-11), lore § formatted, values
 * seeded; feed to skyblockMenu({ rows: 6, items }) for the panes and Close.
 */
export function skyblockMenuItems(seed = 1) {
  const R = rng(seed), p = profile(seed);
  const view = ['', '§eClick to view!'];
  const it = (mcId, name, lore, icon) => item({ mcId: `minecraft:${mcId}`, name: `§a${name}`, lore, icon });
  return {
    10: it('player_head', 'Heart of the Mountain', ['§7Your Heart of the Mountain contains', '§7your progression for the deeper', '§7layers of the Deep Caverns!', '', `§7Mithril Powder: §2${num(R.int(0, 3e6))}`, `§7Gemstone Powder: §d${num(R.int(0, 6e5))}`, ...view], { kind: 'head', color: '#7a3db8' }),
    13: it('player_head', 'Stats & Equipment', ['§7View your equipment, stats,', '§7achievements, and more!', '', ` §c❁ Strength §f${num(R.int(100, 1500))}`, ` §a❈ Defense §f${num(R.int(100, 1500))}`, ` §9☠ Crit Damage §f${num(R.int(100, 1500))}%`, ` §c❤ Health §f${num(R.int(1000, 7000))}`, ...view]),
    19: it('diamond_sword', 'Your Skills', ['§7View your Skill progression and', '§7rewards.', '', `§6${skillAverage(p.skills).toFixed(1)} Skill Avg. §8(non-cosmetic)`, ...view]),
    20: it('painting', 'Collections', ['§7View all of the items available in', '§7SkyBlock. Collect more of an item to', '§7unlock rewards on your way to', '§7becoming a master of SkyBlock!', ...view]),
    21: it('book', 'Recipe Book', ['§7Through your adventure, you will', '§7unlock recipes for all kinds of', '§7special items!', ...view]),
    22: it('player_head', 'SkyBlock Leveling', [`§7Your SkyBlock Level: §8[§c${p.sbLevel}§8]`, '', `§7Progress to Level ${p.sbLevel + 1}:`, `§b${R.int(0, 99)}§3/§b100 §bXP`, ...view], { kind: 'head', color: '#3a9a3a' }),
    23: it('writable_book', 'Quests & Chapters', ['§7Each island has its own series of', '§7Chapters for you to complete!', ...view]),
    24: it('clock', 'Calendar and Events', ['§7View the SkyBlock Calendar, upcoming', '§7events, and event rewards!', '', `§7Date: §a${ordinal(R.int(1, 31))} ${R.pick(SEASONS)} ${R.int(380, 520)}`, ...view]),
    25: it('chest', 'Storage', ['§7Store global items that you want to', '§7access at any time from anywhere', '§7here.', ...view]),
    29: it('player_head', 'Your Bags', ['§7Different bags allow you to store', '§7many different items inside!', '', '§eClick to open!'], { kind: 'head', color: '#a0743c' }),
    30: it('bone', 'Pets', ['§7View and manage all of your Pets.', '', `§7Selected pet: §6${pets(seed, 1)[0].name}`, ...view]),
    31: it('crafting_table', 'Crafting Table', ['§7Opens the crafting grid.', '', '§eClick to open!'], { kind: 'block', color: '#9c7a48' }),
    32: it('barrel', 'Loadouts', ['§7View and edit preset armor and', '§7equipment sets with other settings to', '§7make switching activities easy.', ...view]),
    33: it('player_head', 'Personal Bank', ['§7Contact your Banker from anywhere.', '', `§7Last Interest: §6${num(p.bank * 0.001)} coins`, '', '§eClick to open!'], { kind: 'head', color: '#d8b02a' }),
    47: it('player_head', 'Fast Travel', ['§7Teleport to islands you\'ve already', '§7visited.', '', '§bRight-click for hub warp!', '§eClick to pick location!'], { kind: 'head', color: '#3a7ad8' }),
    48: it('name_tag', 'Profile Management', ['§7You can have multiple SkyBlock', '§7profiles at the same time.', '', `§7Playing on: §a${p.cuteName}`, ...view]),
    50: it('redstone_torch', 'Settings', ['§7View and edit your SkyBlock settings.', ...view]),
    51: it('cookie', 'Booster Cookie', ['§7Obtain the §dCookie Buff §7from Booster', '§7Cookies in the hub\'s Community Shop.', '', `§7Duration: §a${R.int(0, 4)}d ${R.int(0, 23)}h ${R.int(0, 59)}m`, ...view]),
  };
}

// ------------------------------------------------------------------ sacks, pets, profiles
const SACK_ITEMS = {
  'Mining Sack': ['Cobblestone', 'Coal', 'Iron Ingot', 'Gold Ingot', 'Diamond', 'Lapis Lazuli', 'Emerald', 'Redstone', 'Mithril', 'Titanium', 'Hard Stone', 'Glacite'],
  'Fishing Sack': ['Raw Fish', 'Raw Salmon', 'Pufferfish', 'Clownfish', 'Prismarine Shard', 'Prismarine Crystals', 'Sponge', 'Lily Pad', 'Ink Sac'],
  'Combat Sack': ['Rotten Flesh', 'Bone', 'String', 'Spider Eye', 'Gunpowder', 'Ender Pearl', 'Ghast Tear', 'Slimeball', 'Blaze Rod', 'Magma Cream'],
  'Gemstone Sack': ['Rough Ruby Gemstone', 'Rough Amber Gemstone', 'Rough Sapphire Gemstone', 'Rough Jade Gemstone', 'Rough Amethyst Gemstone', 'Rough Topaz Gemstone', 'Flawed Ruby Gemstone'],
  'Agronomy Sack': ['Wheat', 'Carrot', 'Potato', 'Pumpkin', 'Melon', 'Sugar Cane', 'Cactus', 'Cocoa Beans', 'Nether Wart', 'Red Mushroom'],
};
const sbIdOf = (name) => name.toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^ROUGH_(\w+)_GEMSTONE$/, 'ROUGH_$1_GEM');

/** Sack name → `[{ id, name, count }]`; counts 0..20,160 (a Large sack's per-item capacity). */
export function sacks(seed = 1) {
  const R = rng(seed);
  return Object.fromEntries(Object.entries(SACK_ITEMS).map(([sack, names]) => [sack, names.map((name) => ({ id: sbIdOf(name), name, count: R() < 0.25 ? 0 : R.int(1, 20160) }))]));
}

// ------------------------------------------------------------------ pets
// XP table: NEU constants/pets.json via SkyHelper-Networth constants/pets.js (identical in SkyHanni and
// Skyblocker). PET_LEVELS[offset + L - 1] is the XP from level L to L + 1.
export const PET_LEVELS = [
  100, 110, 120, 130, 145, 160, 175, 190, 210, 230, 250, 275, 300, 330, 360, 400, 440, 490, 540, 600, 660, 730, 800, 880, 960, 1050, 1150, 1260, 1380, 1510, 1650, 1800,
  1960, 2130, 2310, 2500, 2700, 2920, 3160, 3420, 3700, 4000, 4350, 4750, 5200, 5700, 6300, 7000, 7800, 8700, 9700, 10800, 12000, 13300, 14700, 16200, 17800, 19500,
  21300, 23200, 25200, 27400, 29800, 32400, 35200, 38200, 41400, 44800, 48400, 52200, 56200, 60400, 64800, 69400, 74200, 79200, 84700, 90700, 97200, 104200, 111700,
  119700, 128200, 137200, 146700, 156700, 167700, 179700, 192700, 206700, 221700, 237700, 254700, 272700, 291700, 311700, 333700, 357700, 383700, 411700, 441700,
  476700, 516700, 561700, 611700, 666700, 726700, 791700, 861700, 936700, 1016700, 1101700, 1191700, 1286700, 1386700, 1496700, 1616700, 1746700, 1886700,
];
export const PET_RARITY_OFFSET = { COMMON: 0, UNCOMMON: 6, RARE: 11, EPIC: 16, LEGENDARY: 20, MYTHIC: 20 };
// Golden Dragon (Jade / Rose Dragon too) runs to 200: an egg until 100, level 101 is free (the hatch),
// 101 → 102 costs 5,555, every later level 1,886,700; total at 200 = 210,255,385 (Skyblocker LevelFinder,
// SkyHanni LEVEL_200_LEGENDARY; captures bound it between 203.5M and 215.4M).
const PET_200 = new Set(['Golden Dragon', 'Jade Dragon', 'Rose Dragon']);
const GD_HATCH = 5555, GD_STEP = 1886700;
export const petMaxLevel = (name) => (PET_200.has(name) ? 200 : 100);
/** XP needed to go from [level] to level + 1 (0 at max). */
export function petLevelNeed(level, rarity, name = '') {
  if (level >= petMaxLevel(name)) return 0;
  if (level >= 100) return level === 100 ? 0 : level === 101 ? GD_HATCH : GD_STEP;
  return PET_LEVELS[(PET_RARITY_OFFSET[rarity] ?? 20) + level - 1];
}
/** Total XP at the start of [level]. Legendary 100 = 25,353,230; Golden Dragon 200 = 210,255,385. */
export function petLevelXp(level, rarity, name = '') {
  let t = 0;
  for (let l = 1; l < level; l++) t += petLevelNeed(l, rarity, name);
  return t;
}
/** Level from total XP: `{ level, into, need, maxed, overflow }` (into/need within the level; overflow past max). */
export function petLevelOf(total, rarity, name = '') {
  const max = petMaxLevel(name);
  let level = 1, rest = total;
  while (level < max) {
    const need = petLevelNeed(level, rarity, name);
    if (rest < need) return { level, into: rest, need, maxed: false, overflow: 0 };
    rest -= need;
    level++;
  }
  return { level: max, into: 0, need: 0, maxed: true, overflow: rest };
}

// [name, rarity, pet type (its skill), head color for the icon stand-in, held item, skin]. Rarities, items
// and skins marked * come from the owner's captures (2026-11); the rest are common real setups.
export const PETS = [
  ['Golden Dragon', 'LEGENDARY', 'Combat', '#e8b33a', 'Hephaestus Relic', 'Anubis'], // *
  ['Ender Dragon', 'LEGENDARY', 'Combat', '#4b2a6e', 'Tier Boost', null], // *
  ['Bal', 'LEGENDARY', 'Combat', '#c2462a', 'Tier Boost', null], // *
  ['Scatha', 'LEGENDARY', 'Mining', '#6b4a2f', 'Quick Claw', null],
  ['Griffin', 'LEGENDARY', 'Combat', '#c9a46b', 'Antique Remedies', null],
  ['Parrot', 'LEGENDARY', 'Alchemy', '#3fae49', 'Exp Share', null],
  ['Mole', 'LEGENDARY', 'Mining', '#5d4037', 'Bejeweled Collar', null], // *
  ['Flying Fish', 'MYTHIC', 'Fishing', '#6aa9d6', 'Burnt Texts', null], // *
  ['Black Cat', 'MYTHIC', 'Combat', '#2b2b33', 'Lucky Clover', 'Purranormal'], // *
  ['Armadillo', 'MYTHIC', 'Mining', '#a07a5a', null, null], // *
  ['Jellyfish', 'LEGENDARY', 'Alchemy', '#e07ad0', null, null], // *
  ['Phoenix', 'LEGENDARY', 'Combat', '#ff7a1a', null, null], // *
  ['Orchid Mantis', 'LEGENDARY', 'Foraging', '#e48bd0', null, null], // *
  ['Rock', 'LEGENDARY', 'Mining', '#8f8f8f', 'Mining Exp Boost', null],
  ['Rabbit', 'EPIC', 'Farming', '#c9b29b', 'Exp Share', null],
  ['Silverfish', 'RARE', 'Mining', '#9a9a9a', null, null],
  ['Blue Whale', 'LEGENDARY', 'Fishing', '#3c6fd0', 'Exp Share', null],
  ['Elephant', 'LEGENDARY', 'Farming', '#8d8f99', 'Exp Share', null],
].map(([name, rarity, type, color, held, skin]) => ({ name, rarity, type, color, held, skin }));
// Pet item name → § color. * captured (name color in chat / lore); others from item rarity.
export const PET_ITEMS = {
  'Hephaestus Relic': '6', 'Burnt Texts': '6', 'Bejeweled Collar': '9', // *
  'Tier Boost': '6', 'Exp Share': '5', 'Quick Claw': '9', 'Antique Remedies': '5', 'Lucky Clover': '5', 'Mining Exp Boost': '9',
  'Minos Relic': '5', 'Dwarf Turtle Shelmet': '9', 'Textbook': '6',
};

/**
 * One pet by name at a level (or exact total XP): `{ name, rarity, type, color, held, skin, level, maxLevel,
 * xp (total), levelXp, nextXp, into, need, maxed, overflow, active }`. opts: `{ level, frac (0..1 into the
 * level), xp (total, wins), rarity, held, skin }`.
 */
export function pet(name, opts = {}) {
  const base = PETS.find((p) => p.name === name) ?? { name, rarity: 'LEGENDARY', type: 'Combat', color: '#888888', held: null, skin: null };
  const rarity = opts.rarity ?? base.rarity, maxLevel = petMaxLevel(name);
  let xp = opts.xp;
  if (xp == null) {
    const level = Math.max(1, Math.min(maxLevel, opts.level ?? maxLevel));
    xp = petLevelXp(level, rarity, name) + Math.floor((opts.frac ?? 0) * petLevelNeed(level, rarity, name));
  }
  const L = petLevelOf(xp, rarity, name);
  const levelXp = petLevelXp(L.level, rarity, name);
  return { ...base, rarity, held: opts.held !== undefined ? opts.held : base.held, skin: opts.skin !== undefined ? opts.skin : base.skin,
    level: L.level, maxLevel, xp, levelXp, nextXp: levelXp + L.need, into: L.into, need: L.need, maxed: L.maxed, overflow: L.overflow, active: !!opts.active };
}

/** `n` pets (same shape as pet()), the first active; a third are max level, the rest somewhere in a level. */
export function pets(seed = 1, n = 6) {
  const R = rng(seed);
  return Array.from({ length: n }, (_, i) => {
    const b = R.pick(PETS), max = petMaxLevel(b.name);
    const p = R() < 0.35 ? { xp: petLevelXp(max, b.rarity, b.name) + R.int(0, 2e7) } : { level: R.int(1, max - 1), frac: R() };
    return pet(b.name, { ...p, active: i === 0 });
  });
}

// tab and summon put §r before the skin star; autopet chat does not (both captured)
const petName = (p, r = '§r') => `§${RC[p.rarity] ?? 'f'}${p.name}${p.skin ? `${r}§d ✦` : ''}`;
/**
 * The tab list's Pet widget body for a pet (null = ` No pet selected`), as captured 2026-11: the name
 * line, then overflow XP at max (the "Show Overflow Pet XP" widget option) or `cur/need XP (pct%)`.
 * The non-max line's colors are a guess: only SkyHanni's plain-text test strings show that form.
 */
export function petTab(p) {
  if (!p) return [' §7No pet selected'];
  const xp = p.maxed ? `§r §r§6+§r§e${num(p.overflow)} XP`
    : ` §e${num(p.into)}§6/§e${short(p.need)} XP §6(${(Math.floor((p.into / p.need) * 1000) / 10).toFixed(1)}%)`;
  return [`§r §r§7[Lvl ${p.level}] §r${petName(p)}`, xp];
}
const RC = { COMMON: 'f', UNCOMMON: 'a', RARE: '9', EPIC: '5', LEGENDARY: '6', MYTHIC: 'd' };

/**
 * Pet chat lines exactly as captured (2026-11), except despawn (no capture; the wording is the wiki's).
 * autopet also returns the hover text of its VIEW RULE button.
 */
export const petChat = {
  autopet: (p, when = 'You throw a fishing hook.') => ({
    text: `§r§cAutopet §eequipped your §7[Lvl ${p.level}] ${petName(p, '')}§e! §a§lVIEW RULE`,
    hover: `§r§cAutopet Rule\n\n§r§aWhen:\n§r§7${when}\n\n§r§aEquip: §r§e⭐ §r§7[Lvl ${p.level}] ${petName(p)}${p.held ? `\n§r§aHeld Item: §r§${PET_ITEMS[p.held] ?? 'f'}${p.held}` : ''}`,
  }),
  summon: (p) => `§r§aYou summoned your §r${petName(p)}§r§a!`,
  despawn: (p) => `§r§aYou despawned your §r${petName(p)}§r§a!`,
  levelUp: (p, level) => `§r§aYour §r§${RC[p.rarity] ?? 'f'}${p.name} §r§aleveled up to level §r§9${level}§r§a!`,
  holding: (item) => `§r§aYour pet is now holding §r§${PET_ITEMS[item] ?? 'f'}${item}§r§a.`,
};

/** One profile: `{ cuteName, gameMode, members, sbLevel, purse, bank, skills, skillAverage, catacombs }`. */
export function profile(seed = 1) {
  const R = rng(seed * 7 + 3);
  const skills = Object.fromEntries(Object.entries(SKILLS).map(([k, cap]) => [k, R.int(Math.floor(cap * 0.4), cap)]));
  const members = [R.pick(PLAYER_NAMES), ...(R() < 0.5 ? [R.pick(PLAYER_NAMES)] : [])];
  return {
    cuteName: R.pick(PROFILE_NAMES), gameMode: R() < 0.8 ? 'normal' : R.pick(['ironman', 'stranded', 'bingo']), members: [...new Set(members)],
    sbLevel: R.int(20, 520), purse: R.int(1e4, 2e9), bank: R.int(0, 2e9), skills, skillAverage: skillAverage(skills),
    catacombs: R.int(0, 50) + R.int(0, 99) / 100,
  };
}
/** `n` profiles of one player with distinct cute names. */
export function profiles(seed = 1, n = 3) {
  const out = [], seen = new Set();
  for (let i = 0; out.length < n && i < 50; i++) {
    const p = profile(seed * 31 + i);
    if (!seen.has(p.cuteName)) { seen.add(p.cuteName); out.push(p); }
  }
  return out;
}
