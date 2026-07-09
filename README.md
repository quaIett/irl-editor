<div align="center">

# ✦ IRL-editor

**Standalone real-time light editor for Minecraft + Iris**

*Place dynamic point lights & spotlights anywhere — with shadows, volumetrics, and per-light specular — through an in-game ImGui editor. Same lighting engine as [IRLights](https://github.com/quaIett/bbs-irlights-addon), but **no BBS required**.*

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1%20%7C%201.20.4%20%7C%201.21.1%20%7C%201.21.4%20%7C%201.21.11-62b47a?style=flat-square&logo=minecraft&logoColor=white)](https://minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric-Loom-dbb967?style=flat-square)](https://fabricmc.net)
[![ImGui](https://img.shields.io/badge/UI-Dear%20ImGui-ff69b4?style=flat-square)](https://github.com/SpaiR/imgui-java)
[![License](https://img.shields.io/badge/License-MIT-3da639?style=flat-square)](LICENSE)

</div>

---

## What is IRL-editor?

IRL-editor is a client-side Fabric mod that brings the [IRLights](https://github.com/quaIett/bbs-irlights-addon)
lighting system to **vanilla worlds and Replay Mod** — no BBS Mod Studio needed.

Press **`L`** in-game to open a [Dear ImGui](https://github.com/SpaiR/imgui-java) editor where you
place lights, drag them with a 3D gizmo, tune every parameter live, and save scenes to disk. Under
the hood it shares the exact same GPU pipeline and shaderpack patcher as IRLights, via the common
[irl-core](https://github.com/quaIett/irl-core) engine.

---

## Features

| Feature | Details |
|---|---|
| **ImGui editor** | In-game light panel — press `L`, no Minecraft screen needed |
| **3D gizmo** | Drag, rotate & position lights directly in the world |
| **Point & spotlights** | Radius, cone angle, color, intensity, falloff |
| **Shadows** | Per-light cube-map (point) & atlas (spot) shadow baking |
| **Volumetrics** | Per-light ray-marched light shafts |
| **Auto block-lights** | Emissive blocks → point lights via a rolling section scan |
| **Patcher UI** | One-click `.irlights` injection into supported shaderpacks |
| **Persistence** | Save & restore light scenes |
| **Replay Mod** | Editor opens & works inside standalone Replay Mod |

---

## Minecraft versions

Each Minecraft version lives on its own branch — pick the one that matches your instance:

| Branch | Minecraft | Notes |
|---|---|---|
| [`main`](https://github.com/quaIett/irl-editor/tree/main) | **1.20.4** | primary line |
| [`port/1.20.1`](https://github.com/quaIett/irl-editor/tree/port/1.20.1) | **1.20.1** | |
| [`port/1.21.1`](https://github.com/quaIett/irl-editor/tree/port/1.21.1) | **1.21.1** | |
| [`port/1.21.4`](https://github.com/quaIett/irl-editor/tree/port/1.21.4) | **1.21.4** | |
| [`port/1.21.11`](https://github.com/quaIett/irl-editor/tree/port/1.21.11) | **1.21.11** | raw-GL shadow path |

---

## Supported Shaderpacks

Ready-to-use `.irlights` patches ship with the mod — apply them once from the patcher panel,
no manual GLSL edits required.

| Shaderpack | Author |
|---|---|
| [Photon](https://modrinth.com/shader/photon-shader) | SixthSurge |
| [Complementary Reimagined](https://modrinth.com/shader/complementary-reimagined) | EminGT |
| [BSL Shaders](https://modrinth.com/shader/bsl-shaders) | CaptTatsu |
| [Solas](https://modrinth.com/shader/solas-shader) | Septonious |
| [Bliss](https://modrinth.com/shader/bliss-shader) | X0nk |

---

## Installation

> **Requirements:** Fabric Loader · Fabric API · Iris · a Minecraft version matching your branch.

1. Drop the `irl-redactor-*.jar` into your `mods/` folder alongside Fabric API and Iris.
2. Launch the game and load a world.
3. Press **`L`** to open the light editor.
4. In the **Patcher** panel, pick your shaderpack and click **Apply** — the GLSL is injected automatically.
5. Enable the patched pack in Iris and reload shaders (`F3 + R`).

---

## Usage

1. Press **`L`** to toggle the editor.
2. Add a **Point Light** or **Spotlight**; drag it with the gizmo.
3. Tune color, intensity, radius, cone, and shadow quality live.
4. Save the scene — it persists across sessions.

---

## Building from Source

The editor consumes the shared [irl-core](https://github.com/quaIett/irl-core) engine as a
**per-version Maven dependency**, so publish the matching core to mavenLocal first:

```bash
# 1) publish the matching core (from the irl-core repo, on your MC version's branch)
../irl-core/gradlew publishToMavenLocal

# 2) build the editor — the checked-out branch determines the target Minecraft version
./gradlew build --refresh-dependencies
# output: build/libs/irl-redactor-1.1+mc<version>.jar
```

`irl-core` is remapped to the branch's mappings and JiJ-bundled via Loom's `include`, alongside
`imgui-java` (binding + lwjgl3 + natives), so the prod jar runs standalone.

---

## The trilogy

| Repo | Role |
|---|---|
| **irl-editor** *(this repo)* | IRL-editor — standalone ImGui editor |
| [bbs-irlights-addon](https://github.com/quaIett/bbs-irlights-addon) | IRLights — BBS Mod Studio add-on |
| [irl-core](https://github.com/quaIett/irl-core) | Shared engine: light SSBO + `.irlights` patcher |

---

## License

Released under the [MIT License](LICENSE) — © 2026 qualet.

Third-party shaderpacks referenced by the patcher remain under the licenses of their respective authors.
