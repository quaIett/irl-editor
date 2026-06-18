# IRL-redactor → MC 1.21.4 — port notes

**Branch:** `port/1.21.4` (forked from `port/1.21.1`) · **Scope:** redactor-only · **Date:** 2026-06-18

## Morning review

✅ **Build is GREEN.** `./gradlew build -x test` → `BUILD SUCCESSFUL`, `:remapJar` + `:build`
executed, jar produced (`build/libs/irl-redactor-1.0.0.jar`, ~7.5 MB). The 1.21.1→1.21.4
delta was small and fully resolved: 2 dep/config files + 4 client source files, **+25 / −32
lines total**. No functionality was stubbed — every removed/renamed API was mapped to its
1.21.4 equivalent. The one thing left to confirm by eye is the **render path at runtime** (see
Follow-ups) — it compiles, but a boot-test was outside tonight's acceptance bar.

> ⚠️ **Recovery note:** the autonomous overnight workflow died mid-run (fix agent applied the
> edits and compiled green, then was terminated before committing — likely the background run
> was torn down by main-session interrupts). The work was recovered from the working tree,
> re-verified with a forced clean recompile + full build, and committed. Nothing lost.

## Dependency matrix (applied)

| Component | port/1.21.1 (base) | → port/1.21.4 |
|---|---|---|
| minecraft | 1.21.1 | **1.21.4** |
| yarn | 1.21.1+build.3 | **1.21.4+build.8** |
| loader | 0.16.14 | 0.16.14 (unchanged) |
| fabric API | 0.116.12+1.21.1 | **0.119.4+1.21.4** |
| iris | 1.8.8+1.21.1-fabric | **1.8.8+1.21.4-fabric** (same 1.8.8 line → 2-arg `addDynamicSampler`, sampler mixins untouched) |
| sodium | mc1.21.1-0.6.13-fabric | **mc1.21.4-0.6.13-fabric** |
| replaymod | 1.21-2.6.19 (`modRuntimeOnly`) | **removed** from dev build (runtime-only dep; ReplayCompat is reflection-gated, so compile is unaffected) |
| loom | 1.15.5 | 1.15.5 (unchanged) |

Files: `gradle.properties`, `build.gradle` — commit `bd1f5b1`.

## Source changes — client render-API (1.21.2 → 1.21.4), commit `137db43`

All in `src/client/java/org/qualet/irlredactor/light/`:

- **`shadow/ShadowRenderer.java`** (heaviest, 10 breaks — raw-GL depth path):
  - `RenderSystem.setProjectionMatrix(proj, VertexSorter.BY_DISTANCE)` → `(proj, ProjectionType.PERSPECTIVE)`; field `savedSorter:VertexSorter` → `savedProjectionType:ProjectionType`.
  - `RenderSystem.applyModelViewMatrix()` calls **removed** — the modelview stack (`getModelViewStack()`) is live now; mutating it *is* the upload.
  - `RenderSystem.getVertexSorting()` → `getProjectionType()`.
  - `GameRenderer::getPositionProgram` → `ShaderProgramKeys.POSITION`.
  - `new VertexBuffer(VertexBuffer.Usage.STATIC)` → `new VertexBuffer(GlUsage.STATIC_WRITE)` (`net.minecraft.client.gl.GlUsage`).
- **`LightGuideRenderer.java`**: `GameRenderer::getPositionColorProgram` → `ShaderProgramKeys.POSITION_COLOR`.
- **`shadow/BlockShadowCollector.java`**: `state.getCullingShape(world, mut)` → `state.getCullingShape()` (1.21.2 dropped the world/pos params).
- **`shadow/RedactorEntityCasterSource.java`**: `dispatcher.render(entity, cx, cy, cz, yaw, tickDelta, …)` → `render(entity, cx, cy, cz, tickDelta, …)` — the extra yaw float is gone (1.21.2 `EntityRenderState` refactor derives body/head yaw internally).

`:compileJava` (main), `:irl-core:jar`, `:processResources` were green from the start — the dep
bump alone carried them. Only `:compileClientJava` needed work.

## Follow-ups / not done

1. **runClient boot-test NOT run** — tonight's acceptance was build-green only. Recommended next:
   `./gradlew runClient`, open a world + the light editor (L), place a light, confirm shadows
   bake and render. **Eyeball the render-path changes specifically** — the `ProjectionType` /
   removed-`applyModelViewMatrix()` rework in `ShadowRenderer` compiles but is the kind of change
   that should be confirmed visually (shadows in the right place, light guides drawn). This is the
   single most likely spot for a silent runtime regression.
2. **replaymod** removed from the dev build per request — replaymod-in-editor won't be testable in
   dev until re-added (`modRuntimeOnly "maven.modrinth:replaymod:1.21.4-2.6.23"` if ever needed).
3. **iris** kept on 1.8.8 → shadow-sampler mixins (`ProgramSamplersBuilderMixin`,
   `SamplerBindingCubeArrayMixin`) were deliberately untouched; 2-arg `addDynamicSampler` form holds.
4. **irl-core** untouched (composite includeBuild) — compiled clean as-is for 1.21.4.
5. **26.x ports deferred** — 26.4 does not exist yet (newest release is 26.2); per your call,
   26.x is parked. When picked up: base is `port/1.21.11` (post-rewrite), iris jumps 1.8→1.11
   (sampler API changes), sodium 0.6→0.9 — a *large* port, not a small delta like this one.

## Verify

```
git checkout port/1.21.4
./gradlew build -x test     # green
git log --oneline port/1.21.1..port/1.21.4
```
