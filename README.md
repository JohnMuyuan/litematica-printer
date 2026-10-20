# Litematica Printer

> This mod is free and open source on GitHub. If you paid for it, request a refund.

A Fabric automation tool for Litematica, offering schematic placement, mining, filling, fluid draining, area clearing, bedrock breaking, item attraction, and shulker-box storage.

Maintained by **JohnMuyuan**.

## Current release requirements

| Component | Requirement |
| --- | --- |
| Minecraft Java Edition | 26.1.2 or 26.3 |
| Fabric Loader | 0.19.1+; 26.3 requires 0.19.5+ |
| Java | JDK 25 |
| Fabric API | Required |
| MaLiLib | Required on the client |
| Litematica | Required on the client |
| QuickShulker | Optional, for shulker-box features |

The repository retains the upstream multi-version build structure, but current releases and testing target **Minecraft 26.1.2 and 26.3** only. Other versions are outside the current compatibility commitment.

## Features

- Batch placement from schematics, including common block states and orientations.
- Batch mining, filling, replacement, and fluid draining within a selection.
- Clearing proceeds through **fluid draining → normal mining → bedrock breaking**.
- Concurrent bedrock-breaking controller with server confirmation, bounded retries, and configurable throughput.
- Automatic attraction of nearby dropped items while the main work toggle is enabled.
- Attraction filters can follow the mining allowlist/blocklist, collect everything, collect only bedrock-breaking materials, or use a separate list.
- Automatic storage in inventory shulker boxes, falling back to the normal inventory when boxes are unavailable or full.
- A server companion provides server-validated batching, block operations, and item collection in multiplayer.

## Installation

### Single player

Put these mods in the client's `mods` folder:

1. Fabric API
2. MaLiLib
3. Litematica
4. `litematica-printer-0.9.2+mc26.1.2.jar`, or `litematica-printer-0.9.2+mc26.3.jar` for 26.3
5. QuickShulker, if shulker-box features are needed

Single-player worlds do not require the server companion JAR.

### Multiplayer

Install the client as above. Also put these in the server's `mods` folder:

1. Fabric API
2. `litematica-printer-server-0.9.2.jar`, or `litematica-printer-server-0.9.2+mc26.3.jar` for 26.3

The server does not need the client `litematica-printer` JAR. The client and companion must use the same major version.

| Environment | Basic client operations | Server batching/sync | Multiplayer area attraction |
| --- | --- | --- | --- |
| Single player | Supported | Executed locally | Supported |
| Multiplayer, client only | May be limited by latency or anticheat | Unsupported | Unsupported |
| Multiplayer, both installed correctly | Supported | Supported | Supported |

Options requiring the companion show a yellow notice when it is not detected. Some ordinary client interactions may still work, but single-player speed and accuracy are not guaranteed.

## Usage notes

![Set the Litematica selection mode to Simple](预设位置.png)

Area clearing, batch mining, and filling use Litematica selections. Choose **Simple** selection mode; Corners and Expand currently have partial support only.

- Bedrock breaking requires survival or adventure mode, pistons, redstone torches, slime blocks, and tools and effects capable of instantly mining pistons.
- Higher tasks per tick require better server TPS, latency, and inventory resupply. Lower throughput first if operations fail or machines overlap.
- Server anticheat, interaction rate limits, and region protection may reject automatic operations.
- Automatic shulker-box extraction and storage require a compatible QuickShulker environment. Items fall back to the normal inventory when no box or space is available; attraction stops when that inventory is full too.

## Build from source

Use JDK 25. Windows PowerShell example; replace the JDK path with your installation:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:TARGET_MC_VERSIONS = '26.1,26.3'
.\gradlew.bat :26.1:build :26.3:build :serverCompanion:build --stacktrace --console=plain
.\gradlew.bat :serverCompanion:build -Pserver_mc='26.3' --stacktrace --console=plain
```

Build outputs:

```text
versions/26.1/build/libs/litematica-printer-0.9.2-local+mc26.1.2.jar
versions/26.3/build/libs/litematica-printer-0.9.2-local+mc26.3.jar
serverCompanion/build/libs/litematica-printer-server-0.9.2-local.jar
serverCompanion/build/libs/litematica-printer-server-0.9.2-local+mc26.3.jar
```

The GitHub Release workflow produces official files without `-local` and includes `SHA256SUMS.txt`.

## Reporting issues

Include Minecraft, Fabric Loader, Fabric API, Litematica, and MaLiLib versions; whether the matching client and server JARs are installed; reproduction steps, relevant configuration, and full logs. For bedrock issues, also include game mode, tool state, material quantities, server TPS, and latency.

Do not publish access tokens or authentication-bearing `profile.json` files in logs.

## Credits

- [aleksilassila/litematica-printer](https://github.com/aleksilassila/litematica-printer): original project.
- [zhaixianyu/litematica-printer](https://github.com/zhaixianyu/litematica-printer): early feature extensions and fixes.
- [Yur1Ca/litematica-printer](https://github.com/Yur1Ca/litematica-printer): Hana multi-version branch.
- [MoRanpcy/quickshulker](https://github.com/MoRanpcy/quickshulker) and later versions: quick shulker compatibility.
- [bunnyi116/fabric-bedrock-miner](https://github.com/bunnyi116/fabric-bedrock-miner): bedrock-breaking implementation reference.

## License

Released under the [GNU Affero General Public License v3.0](LICENSE.md). When distributing modified mods or offering a network service based on this program, comply with AGPL-3.0 source availability requirements and retain the original licenses and attribution.
