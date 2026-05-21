# Norgannon

Norgannon is a decentralized character registry for World of Warcraft private servers. It lets characters move between participating servers while keeping their progression, items, and identity intact. Every snapshot is cryptographically signed so the receiving server can verify exactly who created it and that it hasn't been tampered with.

---

## Overview

Four pieces work together to make this happen.

The **Registry Companion** is a Windows tray application that runs on the player's machine. It generates and stores the player's Ed25519 keypair and signs authentication challenges sent from the game server. Players interact with it once during setup and never again - it sits in the background and handles signing automatically.

The **Registry Hook** is a Lua script (or C++ module for servers without Eluna) that runs inside the game server. It intercepts addon messages from the WoW client and routes them to the agent's authentication API.

The **Registry Agent** runs alongside the game server. It handles the challenge-response authentication handshake, reads character data directly from the game database, assembles signed snapshots, and broadcasts them to the registry network. It also handles the import side - pulling a snapshot from the network and writing it back into the local game database. Server owners interact with the agent through a local HTTP API or the included admin GUI.

The **Registry Node** is a community-run peer that stores and replicates snapshots. Nodes verify every server signature before accepting a record, gossip new records to their peers, and serve read queries. Anyone can run a node - no database is required, records are stored as flat JSON files.

---

## How a Character is Tracked

When a player logs in, the game server hook intercepts their addon's handshake message and calls the agent's challenge endpoint. The agent generates a random nonce and returns it to the hook, which forwards it to the client. The addon sends the nonce to the companion for signing. The companion signs it with the player's private key and returns the signature to the addon, which sends it back to the game server. The hook calls the agent's verify endpoint with the signature. The agent checks it against the nonce using the player's public key and, if valid, opens a session.

From that point on, the agent watches for game events. On login, logout, item pickups, zone changes, hearthstone rebinds, pet tames, and every fifteen minutes as a heartbeat, it reads the character's current state from the game database, assembles a snapshot, signs it with the server's private key, and broadcasts it to every configured registry node.

---

## What a Snapshot Contains

Each snapshot carries the character's name, level, class, race, and gender. Stats include health, mana, and class-specific power values. All nineteen equipment slots are recorded along with the full contents of the backpack and bank, including bag container references and slot positions. Currency is stored in copper with gold, silver, and remainder broken out. Progression covers skill ranks, faction reputation standings, completed quests, talent selections, and the full spell book. Pets include hunter pets and warlock demons with their name, level, current health, and stable slot. The hearthstone bind location is recorded as a map ID, zone ID, and world coordinates.

Two signatures are attached. The server signature is an Ed25519 signature over the canonical JSON of the payload, created by the agent. Registry nodes verify this before storing a record. The player signature is produced during login and authorizes the server to publish snapshots on the player's behalf without requiring the player to be online for every push.

Snapshots use a monotonically increasing sequence number per character. When conflicting records arrive at a node, the one with the higher sequence number wins. This prevents a stale snapshot from overwriting a newer one.

---

## Getting Started

### Running a Node

```bash
java -jar registry-node.jar
```

This starts a node on port 8080 with data stored in the local `data` directory. To configure the port, storage path, public URL, and peer list, create a `config.yaml` next to the JAR before starting:

```yaml
port:      8080
dataDir:   "./data"
publicUrl: "https://your-node.example.com:8080"
peers:
  - "https://other-node.example.com:8080"
```

All fields are optional - the defaults work for a local-only node.

### Setting Up the Agent

On first run, generate a server keypair and a starter config file:

```bash
java -jar registry-agent.jar init
```

This writes `./keys/server.pem`, `./keys/server.pub`, and a skeleton `config.yaml` to the current directory. Open `config.yaml` and fill in the required fields:

```yaml
serverId: "your-uuid-here"

privateKeyPath: "./keys/server.pem"
publicKeyPath:  "./keys/server.pub"

dbUrl:      "jdbc:mysql://localhost:3306/characters"
dbUsername: "trinity"
dbPassword: "trinity"
dbAdapter:  "trinitycore_3.3.5a"

# worldDbUsername and worldDbPassword fall back to dbUsername/dbPassword if omitted
worldDbUrl: "jdbc:mysql://localhost:3306/world"

registryNodes:
  - "https://node1.example.com:8080"
  - "https://node2.example.com:8080"
```

`dbAdapter` must be exactly `trinitycore_3.3.5a` or `azerothcore_3.3.5a`. The world database connection is optional but strongly recommended - without it, pet model lookups and spawn position fallbacks are skipped.

Then start the agent:

```bash
java -jar registry-agent.jar start
```

The agent binds its HTTP API to `127.0.0.1:8100`. This port should never be exposed externally - only the game server hook and local admin calls reach it.

To open the admin import GUI instead:

```bash
java -jar registry-agent.jar admin-gui
```

This starts the agent in the same way as `start` and additionally opens a desktop window for performing imports, managing the trust list, and selecting import profiles. A display environment is required.

### Installing the Hook

Copy `RegistryHook.lua` from the `registry-hook` directory into the server's Eluna scripts folder and run `.reload eluna` in-game. No recompile is needed. The hook intercepts `REGISTRY_AUTH` addon messages and forwards them to the agent.

### Player Setup

Players download and run `RegistryCompanion.exe`. On first launch it generates an Ed25519 keypair, displays a twelve-word BIP39 recovery phrase, and minimizes to the system tray. The key is encrypted with AES-256 using the machine's hardware identifier. Players should save their recovery phrase - it is the only way to recover the key if the machine is lost, replaced, or (factory) reset.

---

## Importing a Character

To import a character's latest snapshot from the registry into the local game database, call the agent's import endpoint:

```
POST http://127.0.0.1:8100/admin/import
{
  "character_id":   "uuid",
  "player_pub_key": "hex...",
  "profile":        "vanilla_transfer"
}
```

The `profile` field is optional. It accepts either a profile name (filename without `.json`) or an inline profile object. If omitted, all data is imported with no restrictions.

The agent fetches the record from the registry, checks it against the trust list, applies the import profile, and writes the result to the game database. **If the character does not yet exist on this server**, the import will fail with a message prompting you to provide an Account ID - use the admin GUI for new-character imports (see below).

### Importing New Characters via Admin GUI

When a character does not yet exist on the local server, the admin GUI's Account ID field is required. Enter the numeric ID from the local `account` table that the character should be created under. The agent will create a fully populated character row in a single atomic transaction - the character is either completely ready or not visible at all.

Spawn position is preserved from the snapshot's hearthstone bind if the bind point is on an open-world map (Eastern Kingdoms, Kalimdor, Outland, or Northrend). If not, the agent falls back to the race's default starting position from `playercreateinfo`, and if that is also unavailable, to a hardcoded faction capital.

---

## Import Profiles

Profiles let server owners control exactly what a snapshot import is allowed to write. A profile is a JSON file placed in the `profiles` directory next to the agent JAR. The import request names a profile by filename (without `.json`), or embeds one inline as an object.

Six profiles ship with the agent.

`character_copy` - Full unrestricted transfer of everything, including custom item templates. Intended for migrations between servers run by the same owner.

`vanilla_transfer` - For transfers from a Vanilla (1–60) server to WotLK. Caps level at 60 and gold at 100g. Talents and hearthstone excluded (incompatible between expansions). Custom item templates excluded.

`wotlk_80` - For capping an already-WotLK character at level 80. Transfers all progression, skills, reputation, quests, talents, and items at the level cap.

`tbc_70` - For capping at level 70. Similar to `wotlk_80` but for TBC-era content.

`economy_reset` - Preserves level, skills, reputation, quests, talents, pets, and hearthstone. Gold and all items are wiped. Intended for economy resets without resetting progression.

`fresh_start` - Carries over only completed quests and reputation. Level is forced to 1, gold to zero, no items, no skills, no pets. Intended for seasonal servers where players keep story progress but start fresh otherwise.

### Spell Import

Spells are captured in every snapshot but **are not imported by default**. To enable spell import for a profile, add:

```json
"import_spells": true
```

This defaults to `false` as a security measure. Spell IDs are not guaranteed to be consistent across servers - an ID that maps to a low-power spell on the source server could map to a completely different, much more powerful spell on the destination. Only enable spell import when transferring between servers you control and whose databases share the same spell tables.

When spell import is disabled (the default), characters receive their class's starting spells from `playercreateinfo_spell` automatically on first import, so they are functional immediately.

### Custom Profile Fields

All boolean flags and caps are configurable:

| Field | Type | Default | Description |
|---|---|---|---|
| `max_level` | int | 0 (no cap) | Character level is clamped to this value |
| `max_gold_copper` | long | 0 (no cap) | Gold is clamped to this amount in copper |
| `import_equipment` | bool | true | Equipped items |
| `import_inventory` | bool | true | Backpack contents |
| `import_bank` | bool | true | Bank contents |
| `import_pets` | bool | true | Hunter pets and warlock demons |
| `import_hearthstone` | bool | true | Hearthstone bind location |
| `import_skills` | bool | true | Weapon and profession skill ranks |
| `import_reputation` | bool | true | Faction standings |
| `import_quests_completed` | bool | true | Completed quest history |
| `import_talents` | bool | true | Talent point allocations |
| `import_spells` | bool | **false** | Learned spells |
| `import_custom_item_templates` | bool | true | Items whose entry ID exceeds `customItemThreshold` |
| `blocked_skill_ids` | int[] | [] | Skill IDs to exclude even when skills are enabled |
| `blocked_faction_ids` | int[] | [] | Faction IDs to exclude even when reputation is enabled |

---

## Trust System

By default, when no `trust.json` exists the agent starts in **trust-all mode** and accepts imports from any server on the registry network. To restrict this, add a trusted server through the admin API:

```
POST http://127.0.0.1:8100/admin/trust
{
  "pub_key":        "hex...",
  "label":          "Friendly Server Name",
  "default_profile": "vanilla_transfer"
}
```

Adding the first entry enables **strict mode**. From that point on, import attempts from any server not in the trust list are blocked before any database write occurs. A warning is logged with the blocked server's ID and public key prefix so the admin knows exactly what to add if the block was unintentional.

Trust is keyed on the remote server's Ed25519 public key. This key is cryptographically bound to the server signature that registry nodes verify before storing any record, so it cannot be forged or claimed by another server.

`default_profile` is optional. When set, it names an import profile to apply automatically for all imports from that server without needing to specify it in each request. An explicit `profile` field in the import request always takes precedence.

The trust list is persisted to `trust.json` on every change and survives restarts.

To list current trust entries:

```
GET http://127.0.0.1:8100/admin/trust
```

To remove an entry:

```
DELETE http://127.0.0.1:8100/admin/trust
{ "pub_key": "hex..." }
```

---

## Agent API Reference

All endpoints bind to `127.0.0.1:8100`.

| Method | Path | Description |
|---|---|---|
| `POST` | `/auth/challenge` | Request a nonce for a player public key |
| `POST` | `/auth/verify` | Submit a signed nonce and open a session |
| `POST` | `/auth/login` | Internal: inject a session via the companion signer (used by the DB queue processor) |
| `POST` | `/snapshot/event` | Fire a game event for a session token (LOGIN, LOGOUT, HEARTBEAT, etc.) |
| `POST` | `/admin/import` | Import a character snapshot from the registry |
| `GET` | `/admin/trust` | List trusted servers |
| `POST` | `/admin/trust` | Add a trusted server (enables strict mode) |
| `DELETE` | `/admin/trust` | Remove a trusted server |
| `GET` | `/health` | Health check - returns `{"status":"ok"}` |
| `POST` | `/debug/session` | Inject a test session via the companion signer |
| `POST` | `/debug/event` | Fire an event against a test session |

The companion signer listens on `http://127.0.0.1:7742`. The agent calls its `/sign` endpoint internally for login and debug flows - it does not need to be called directly.

Valid event names for `/snapshot/event`: `LOGIN`, `LOGOUT`, `HEARTBEAT`, `LEVEL_UP`, `ITEM_ACQUIRED`, `QUEST_COMPLETED`, `DEATH`.

---

## Node API

Nodes expose a simple HTTP API.

`POST /snapshot` accepts a new snapshot from an agent or a peer node. The server signature is verified before the record is stored. If the incoming sequence number is not higher than the stored one, the record is silently dropped.

`GET /character/{id}` returns the latest stored record for a character ID.

`GET /characters` returns a list of all character IDs stored on this node.

`GET /status` returns node metadata including record count, known peers, and uptime.

`GET /ping` is a health check.

---

## Namespace Compatibility

All server-specific progression data in a snapshot payload is keyed by namespace. The namespace identifies the server core and patch version. TrinityCore 3.3.5a uses `wow_wotlk_3.3.5a` and AzerothCore 3.3.5a uses `wow_wotlk_azerothcore`.

A snapshot taken on one core can be imported on the other. When writing progression data - skills, reputation, quests, talents, spells - the agent only applies entries whose namespace matches its own. Foreign-namespace entries are preserved in the record but ignored during import rather than overwriting local data with potentially incompatible IDs.

Item template entry IDs are assumed to be consistent across all 3.3.5a servers regardless of core, so inventory and equipment imports are not namespace-filtered. The `customItemThreshold` config value (default 100000) marks the entry ID above which items are considered server-custom; these can be excluded per-profile via `import_custom_item_templates: false`.

---

## Building

Each module is a standard Maven project targeting Java 21.

```bash
mvn clean package -DskipTests
```

Run this inside each module directory. Output JARs land in `target`. The companion module additionally produces a Windows executable via Launch4j.

---

## Security Notes

All signatures use Ed25519 via BouncyCastle. The companion encrypts key storage with AES-256-GCM keyed to the Windows machine identifier. The agent's auth API and the companion both bind exclusively to localhost and should never be exposed to external networks. Registry nodes should be placed behind a reverse proxy with HTTPS for any public deployment. The server private key at `./keys/server.pem` should be protected with appropriate OS-level file permissions - possession of the key is what allows publishing records that nodes will accept.

---

## License

MIT
