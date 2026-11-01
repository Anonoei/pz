# WorkshopSyncAgent

Java agent for a Project Zomboid dedicated server that stops workshop updates from locking clients out.

## Problem

The server records each workshop item's install timestamp once, at boot, and sends it to every joining
client. A client first syncs to Steam's latest, then compares its own timestamp with the server's and
shows a "version mismatch" page if they differ. After any workshop update, clients are ahead of the
server until it restarts.

## What it does

Every `poll` seconds it asks Steam for each item's `timeUpdated` and writes that into
`GameServer.workshopTimeStamps`, so the timestamp sent to clients matches Steam.

It does **not** update the files the server has loaded. Scripts and Lua stay at the boot version until
the next restart, so keep a regular restart. Log lines are prefixed `[WorkshopSyncAgent]`; on startup it
logs `checked N items: X matched Steam, Y adjusted`.

## Build

    ./build.sh /path/to/projectzomboid.jar

## Install (AMP)

1. Copy `WorkshopSyncAgent.jar` to `project-zomboid/` in the AMP instance (the server's working
   directory is `project-zomboid/380870/`).
2. Set the instance's `CustomJavaArgs` to `-javaagent:../WorkshopSyncAgent.jar=poll=300`
   (stop the instance first; AMP rewrites its config while running).
3. Start the instance.

`poll` is in seconds, minimum 30, default 300. Without AMP, add the same `-javaagent:` argument to the
server's JVM arguments.

## Notes

- Compiled against Build 42 server classes (`zombie.network.GameServer`, `zombie.core.znet.SteamWorkshop`).
  A game update that changes them may break it. A failure only disables the agent; the server still boots.
- The first query at boot times out because Steam callbacks aren't pumped yet. This is harmless.
