# Modrinth listing — TerraFill

Copy-paste values for creating the project at <https://modrinth.com/> → **Create a project**.
Keep this file for future version uploads.

## Project settings

| Field | Value |
|---|---|
| **Name** | TerraFill |
| **Slug / URL** | `terrafill` (free as of 2026-10-11) → modrinth.com/mod/terrafill |
| **Summary** | Client-side Fabric mod that fills a terrain region bottom-up with a block from your inventory as fast as possible, restocking from shulker boxes. One command marks the area; it handles height, water, mobs, food and resupply. |
| **Categories** | Utility, Management |
| **Environments → Client** | Required |
| **Environments → Server** | Unsupported |
| **License** | MIT |
| **Links (optional)** | Source / Issues: a GitHub repo if you make one — otherwise leave blank |

## Version

| Field | Value |
|---|---|
| **Version number** | 1.0.0 |
| **Version title** | TerraFill 1.0.0 |
| **Release channel** | Release |
| **Loaders** | Fabric |
| **Game versions** | 26.2 |
| **Dependencies** | Fabric API — **Required** (search "Fabric API", slug `fabric-api`) |
| **File** | `build/libs/terrafill-1.0.0.jar` |

Note for users: needs **Java 25** (same as Minecraft 26.2 itself) and **Fabric API**.

### Changelog (paste into the version's changelog box)

First public release.

- Fills a terrain region bottom-up with a block from your inventory, several per tick.
- Restocks from shulker boxes; optional depot area to refill empty boxes from chests.
- Auto height from the surrounding terrain, 32×32 tiles for areas past render distance.
- Water gets a lid by default; `water fill` fills it to the ground.
- Fights, eats, heals and swims up for air while it works. When low on health it walls itself in.
- Server-aware: double-checks placements and throttles when the server rejects fast placements.

---

## Description (paste into the Markdown description box)

TerraFill is a client-side Fabric mod that fills a terrain region with a build block from your
inventory — bottom-up, several blocks per tick, restocking from shulker boxes as it goes. You mark
the area with two corners; it works out the height from the surrounding terrain itself.

It is the sister mod of DeepMine and shares its combat, eating and shulker handling.

### What it does

- **Fills a region fast.** Layer by layer from the bottom, multiple blocks per tick. You only give
  the X/Z footprint — the top edge is read from the terrain around the area, and each column is
  filled down to the ground.
- **Restocks itself.** When the hotbar slot runs out it swaps in a loose stack, and when those are
  gone it places a shulker box next to itself, empties it, breaks it and picks it back up.
- **Depot refill.** Mark a storage area full of chests; when every shulker is empty it walks there
  and refills them from the chests.
- **Water gets a lid**, not a full flood — only the surface is capped, so nothing spawns underneath.
  `/terrafill water fill` fills water to the ground instead.
- **Survives while it works.** Fights hostile mobs with a sword, eats when hungry, heals when low,
  swims up for air, and shoves animals out of the way with the block in hand.
- **Big areas.** The footprint is cut into 32×32 tiles worked in snake order, so regions far larger
  than your render distance are fine.
- **Server-aware.** On a server it double-checks placements and throttles itself when the server
  rejects blocks placed too fast, then speeds back up.

### Commands

```
/terrafill pos1 [x z]          corner 1 of the footprint (default: where you stand)
/terrafill pos2 [x z]          corner 2 — height doesn't matter, it's read from the terrain
/terrafill y <top> [bottom]    fix the height range; default is automatic (y auto)
/terrafill start               go
/terrafill stop / resume       stop, continue — resume also works after a restart
/terrafill status              layer, open cells, progress
/terrafill slot <1-9>          hotbar slot with the build block (default 5)
/terrafill sword <slot> | off  sword (default 1) — fights when attacked, heals when low
/terrafill food <slot> | off   food (default 8) — eats at hunger 12
/terrafill water lid | fill    cap water on top (default) or fill it to the ground
/terrafill depot pos1 / pos2   storage area with chests to refill from; depot list / clear
/terrafill speed <1-64>        blocks per tick (default 8)
/terrafill reach <4.0-5.4>     placing reach (default 4.5; the server accepts up to 5.4)
/terrafill config [reset]      show settings / reset to defaults
```

All settings are saved automatically to `config/terrafill.json`.

### Quick start

Put the build block in hotbar slot 5, a sword in 1, food in 8, and shulker boxes full of the same
block into your inventory (leave two slots free). Stand at one corner, `/terrafill pos1`, walk to the
opposite corner, `/terrafill pos2`, then `/terrafill start`.

### Requirements

- Minecraft 26.2 · Fabric Loader · **Fabric API** · **Java 25**
- Client-side only — it does not need to be on the server.

### Note

TerraFill automates building and movement for your own client. Only use it where it's allowed —
some multiplayer servers forbid automation/macro mods, and that's between you and the server.
