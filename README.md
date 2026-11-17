![Recur title image](https://cdn.modrinth.com/data/cached_images/1e5624caa804b3e9f4f663863c814813316d21b3.png)
# Recur
Recur is a multiplayer time loop mod, built on a fork of Motion Capture. When a player dies or time runs out, the world resets and everyone's past selves replay exactly what they did. Almost every part of the game is covered: inventories, mobs, bows, boats, the dragon fight and everything in between.

## What makes Recur unique?
Recur is built for multiplayer from the ground up, with a smooth, easy to use experience. It adds checkpoints, the ability to kill your clones (and stop a clone before it kills someone else), mobs that remember who they were fighting or following, smart resets that only put back what changed, fully customizable voice playback with crowd quieting, and much more.

## Checkpoints
At any time, a player can create a checkpoint, which saves the world as it is right then. If everyone agrees, the next cycle starts from that checkpoint.

## Recur is a fork of Motion Capture
Forking [Motion Capture](https://modrinth.com/mod/motion-capture) by mt1006 let me add the many changes a full time loop needs. Recur includes its own modified copy, so it can't be installed alongside Motion Capture.

## Requirements
- **[Fabric API](https://modrinth.com/mod/fabric-api)** (required)
- **[Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat)** (optional, for recorded voices)
- Recur must be installed on the server and on every player's client.

## Compatibility
- **[C2ME](https://modrinth.com/mod/c2me-fabric)**
- **[Flashback](https://modrinth.com/mod/flashback)**: Recur switches itself off while watching replays
- **[e4mc](https://modrinth.com/mod/e4mc)** works, and **[e4mc Voice Bridge](https://modrinth.com/mod/voice-bridge-for-e4mc)** or **[e4all](https://modrinth.com/mod/e4all)** lets voice chat work through it

## Incompatible
- **[Motion Capture](https://modrinth.com/mod/motion-capture)** (Recur already includes it)
- **[Axiom](https://modrinth.com/mod/axiom)** / **[WorldEdit](https://modrinth.com/plugin/worldedit)**: won't crash, but their edits aren't replayed by clones and may not be undone by resets
- Hosting through Essential: Simple Voice Chat can't connect through it
- Mods with custom mobs and bosses usually only partly work. If you're unsure whether something works, assume it doesn't.

## Building
Requires JDK 25. Run `./gradlew build` and the mod jar ends up in `build/libs`. The optional mods Recur works with are compile-time dependencies only, downloaded from their Maven repositories.

## Project layout
- `src/main/java/dev/recur`: Recur itself (`client` is client-only, `mixin` holds the mixins)
- `src/main/java/com/mt1006/mocap`: the modified Motion Capture (see `NOTICE.md`)
- `src/main/resources/assets/recur/textures/gui/sprites/icon`: the button icons
- `art`: source files for the icons and logo

## License
Recur is a fork of Motion Capture and is released under the LGPL-3.0 (see `LICENSE`, and `LICENSE-GPL-3.0` which it builds on). `NOTICE.md` lists what was changed in Motion Capture.
