# Dependency Resolver

Client-side mod for Minecraft By Astryxion. All Rights Reserved.

Tired of dependency issues? Dependency Resolver finds, downloads, and installs missing mods from CurseForge or Modrinth before Forge finishes starting, so you can keep playing without sorting jars by hand.

## Install

Put `dependencyresolver-1.0.0.jar` in the instance `mods` folder. That is the only file this mod needs. Do not add a JVM argument.

On CurseForge, mark the file as **Client**.

## What happens on launch

If every required mod is already a compatible version, the game starts normally.

If something is missing, or the installed version is too old or otherwise outside the required range, a window opens before Forge's error screen. It names each mod that needs the file, downloads one compatible jar from CurseForge, and uses Modrinth only when CurseForge has no match. Forge then continues the same launch with the new files in `mods`.

An incompatible jar is moved to `mods/dependencyresolver-disabled` with a short note beside it. It is not deleted. If no compatible file exists, the installed jar stays where it is.

A CurseForge API key is optional. The first launch searches CurseForge without one. A key from [console.curseforge.com](https://console.curseforge.com/) can be set as `curseforgeApiKey` in `config/dependencyresolver.properties`, or as the `CURSEFORGE_API_KEY` environment variable.

## Build

```
gradlew jar
```

The jar is written to `build/libs/dependencyresolver-1.0.0.jar`.
