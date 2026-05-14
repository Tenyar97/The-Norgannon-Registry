# Registry Hook — Server-Side Addon Message Handler

Intercepts `REGISTRY_AUTH` addon messages from the WoW client and
routes them to the registry-agent's authentication API.

---

## Choose your hook

| Your server | Use |
|---|---|
| TrinityCore / AzerothCore **with Eluna** | `eluna/RegistryHook.lua` |
| TrinityCore / AzerothCore **without Eluna** | `cpp/RegistryHook.cpp` |

The Lua hook is recommended — no recompile needed.

---

## Eluna installation

1. Copy `eluna/RegistryHook.lua` to your server's `lua_scripts/` directory
2. Make sure `registry-agent` is running on the same machine
3. Reload Eluna in-game: `.reload eluna`

The hook is live immediately. No restart needed.

**Verify it's working:**
```
[Registry] RegistryHook.lua loaded — listening on prefix: REGISTRY_AUTH
```
Should appear in your server console on reload.

---

## C++ module installation

1. Copy `cpp/RegistryHook.cpp` to `src/server/scripts/Custom/`
2. Add to `CMakeLists.txt` in that directory:
   ```cmake
   set(scripts_Custom
       RegistryHook.cpp)
   ```
3. Add to `src/server/scripts/Custom/custom_script_loader.cpp`:
   ```cpp
   void AddSC_RegistryHook();
   // In the loader function:
   AddSC_RegistryHook();
   ```
4. Recompile TrinityCore
5. Restart your server

---

## Agent configuration

The hook calls the registry-agent on **port 8100** (localhost only).
This is separate from the registry node port (8080).

Add `AgentAuthServer` to your agent's `Main.java` startup:

```java
AgentAuthServer agentAuthServer = new AgentAuthServer(authServer);
agentAuthServer.start();
```

And add it to the shutdown hook:
```java
agentAuthServer.stop();
```

---

## Message flow

```
Player client (addon)          Game server (hook)         registry-agent
      |                              |                          |
      |-- INIT|pubkey|charId ------> |                          |
      |                              |-- POST /auth/challenge -> |
      |                              |<-- { nonce } ----------- |
      |<-- CHALLENGE|nonce --------- |                          |
      |                              |                          |
      |-- SIG|signature|pubkey ----> |                          |
      |                              |-- POST /auth/verify ----> |
      |                              |<-- { success, charId } - |
      |<-- OK|characterId ---------- |                          |
      |  [enters world]              |                          |
```

---

## Ports summary

| Port | Service | Bound to |
|------|---------|----------|
| 8080 | Registry node (peer network) | 0.0.0.0 (public) |
| 8100 | Agent auth API (hook calls) | 127.0.0.1 (localhost only) |
| 7742 | Companion HTTP (addon calls) | 127.0.0.1 (localhost only) |
