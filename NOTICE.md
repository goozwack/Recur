# Notice

Recur includes a modified copy of **Motion Capture (Mocap)** by **mt1006**
(https://github.com/mt1006/mc-mocap-mod), licensed under the **GNU LGPL v3.0** (see `LICENSE`, and `LICENSE-GPL-3.0`
which it builds on). The Mocap code lives in `src/main/java/com/mt1006/mocap` and keeps its original package names.

Everything in `src/main/java/dev/recur` is original to Recur. Because it's combined with LGPL code, the whole mod is
distributed under LGPL-3.0-only, and this repository is its source.

## Changes made to Mocap

Based on upstream branch `FABRIC-26.2`, v1.3.10. Changes inside Mocap's files are marked with `Recur:` comments.
Files added to Mocap's packages by Recur: `actions/SetMobFlags.java`, `actions/SetDragonPhase.java`,
`playing/Controlled.java`.

- **Ported to Minecraft 26.3** (entity invulnerability and swing APIs, profile lookup, resource arguments, the removal
  of `BedItem`).
- **Multiple recordings at once.** `Recording` is one session per player instead of a global singleton, and every hook
  looks up the recording of the player involved.
- **Merged into Recur.** A single jar with no separate Mocap dependency; it declares `breaks: mocap`.
- **Clones as part of the world.** Clones can't be pushed, hit, targeted by mobs or pushed by entity cramming. They
  show being hurt without taking damage, and never hold menus open.
- **Shared entities.** Every entity belongs to one recording. Clones take over entities that already exist (by UUID)
  instead of making copies, let go of them when their recording no longer moves them, and hand vehicles over between
  players and clones.
- **Replaying against a changed world.** Blocks are only broken if they're still the same, placed blocks connect to
  their current neighbours, movement is applied relative to the recording, and runs can fast-forward to a checkpoint.
- **More recorded actions.** Mob attack stances and animations, the ender dragon's phases, bed explosions, Wither
  summoning, firework sounds, item pickups, vehicle seats and dimension changes.
- **Performance.** Recordings are parsed once and reused every run.
