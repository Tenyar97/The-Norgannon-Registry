# Norgannon

Norgannon is a decentralized character registry for World of Warcraft private servers. It lets characters move between participating servers while keeping their progression, items, and identity intact. Every snapshot is cryptographically signed so the receiving server can verify exactly who created it and that it hasn't been tampered with.

---

## Overview

Four pieces work together to make this happen.

The **Registry Companion** is a Windows tray application that runs on the player's machine. It generates and stores the player's Ed25519 keypair and signs authentication challenges sent from the game server. Players interact with it once during setup and never again — it sits in the background and handles signing automatically.

The **Registry Hook** is a Lua script (or C++ module for servers without Eluna) that runs inside the game server. It intercepts addon messages from the WoW client and routes them to the agent's authentication API.

The **Registry Agent** runs alongside the game server. It handles the challenge-response authentication handshake, reads character data directly from the game database, assembles signed snapshots, and broadcasts them to the registry network. It also handles the import side — pulling a snapshot from the network and writing it back into the local game database. Server owners interact with the agent through a local HTTP API.

The **Registry Node** is a community-run peer that stores and replicates snapshots. Nodes verify every server signature before accepting a record, gossip new records to their peers, and serve read queries. Anyone can run a node — no database is required, records are stored as flat JSON files.

---

## How a Character is Tracked

When a player logs in, the game server hook intercepts their addon's handshake message and calls the agent's challenge endpoint. The agent generates a random nonce and returns it to the hook, which forwards it to the client. The addon sends the nonce to the companion for signing. The companion signs it with the player's private key and returns the signature to the addon, which sends it back to the game server. The hook calls the agent's verify endpoint with the signature. The agent checks it against the nonce using the player's public key and, if valid, opens a session.

From that point on, the agent watches for game events. On login, logout, item pickups, zone changes, hearthstone rebinds, pet tames, and every fifteen minutes as a heartbeat, it reads the character's current state from the game database, assembles a snapshot, signs it with the server's private key, and broadcasts it to every configured registry node.

---

## What a Snapshot Contains

Each snapshot carries the character's name, level, class, race, and gender. Stats include health, mana, and class-specific power values. All nineteen equipment slots are recorded along with the full contents of the backpack and bank, including bag container references and slot positions. Currency is stored in copper with gold, silver, and remainder broken out. Progression covers skill ranks, faction reputation standings, completed quests, and talent selections. Pets include hunter pets and warlock demons with their name, level, current health, and stable slot. The hearthstone bind location is recorded as a map ID, zone ID, and world coordinates.

Two signatures are attached. The server signature is an Ed25519 signature over the canonical JSON of the payload, created by the agent. Registry nodes verify this before storing a record. The player signature is produced during login and authorizes the server to publish snapshots on the player's behalf without requiring the player to be online for every push.

Snapshots use a monotonically increasing sequence number per character. When conflicting records arrive at a node, the one with the higher sequence number wins. This prevents a stale snapshot from overwriting a newer one.

---

## Getting Started

### Running a Node

```bash
java -jar registry-node.jar
```

This starts a node on port 8080 with data stored in the local `data` directory. To configure the port, storage path, public URL, and peer list, create a `config.yaml` next to the JAR before starting.

### Setting Up the Agent

On first run, generate a server keypair and a starter config file:

```bash
java -jar registry-agent.jar init
```

This writes `config.yaml` and `server_key.pem` to the current directory. Open `config.yaml` and fill in the game database credentials, the server ID, and at least one registry node URL. The `type` field under `database` should be set to `trinitycore` or `azerothcore` depending on the server core.

Then start the agent:

```bash
java -jar registry-agent.jar start
```

The agent binds its auth API to `127.0.0.1:8100`. This port should never be exposed externally — only the game server hook and local admin calls reach it.

### Installing the Hook

Copy `RegistryHook.lua` from the `registry-hook` directory into the server's Eluna scripts folder and run `.reload eluna` in-game. No recompile is needed. The hook intercepts `REGISTRY_AUTH` addon messages and forwards them to the agent.

### Player Setup

Players download and run `RegistryCompanion.exe`. On first launch it generates an Ed25519 keypair, displays a twelve-word BIP39 recovery phrase, and minimizes to the system tray. The key is encrypted with AES-256 using the machine's hardware identifier. Players should save their recovery phrase — it is the only way to recover the key if the machine is lost or replaced.

---

## Importing a Character

To import a character's latest snapshot from the registry into the local game database, call the agent's import endpoint:

```
POST http://127.0.0.1:8100/admin/import
{
  "character_id":  "uuid",
  "player_pub_key": "hex..."
}
```

The agent fetches the record from the registry, checks it against the trust list, applies any configured import profile, and writes the result to the game database. The character must already exist locally with a mapping registered in the `registry_character_map` table.

---

## Import Profiles

Profiles let server owners control exactly what a snapshot import is allowed to write. A profile is a JSON file placed in the `profiles` directory next to the agent JAR. The import request can name a profile by filename, or embed one inline as an object.

Four profiles ship with the agent.

`character_copy` performs a full unrestricted transfer of everything. It is intended for migrations between servers run by the same owner.

`vanilla_transfer` caps the imported level at 60 and limits transferred gold to 100 gold. Items, skills, reputation, and completed quests carry over. Talents are excluded because Vanilla talent trees do not map to Wrath specs.

`economy_reset` preserves level, skills, reputation, quests, talents, pets, and hearthstone location. Gold and all items are zeroed out. It is intended for servers doing an economy wipe without resetting character progression.

`fresh_start` carries over only completed quests and reputation. Level is forced to 1, gold to zero, and everything else is excluded. It is intended for seasonal servers where players start fresh but keep their story progress.

Profiles support fine-grained configuration beyond the section toggles — specific skill IDs or faction IDs can be blocked individually even when their sections are otherwise enabled.

---

## Trust System

By default, the agent accepts imports from any server on the registry network. To restrict this, add a trusted server through the admin API:

```
POST http://127.0.0.1:8100/admin/trust
{
  "pub_key": "hex...",
  "label":   "Friendly Server Name"
}
```

Adding the first entry enables strict mode. From that point on, import attempts from any server not in the trust list are blocked before any database write occurs. A warning is logged with the blocked server's ID and public key prefix so the admin knows exactly what to add if the block was unintentional.

Trust is keyed on the remote server's Ed25519 public key. This key is cryptographically bound to the server signature that registry nodes verify before storing any record, so it cannot be forged or claimed by another server.

An optional `default_profile` field on a trust entry names an import profile to apply automatically for all imports from that server, without needing to specify it in each request. An explicit profile in the import request always takes precedence.

The trust list is persisted to `trust.json` on every change and survives restarts. The admin API also supports listing and removing entries.

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

All server-specific data in a snapshot payload is keyed by namespace. The namespace identifies the server core and patch version. TrinityCore 3.3.5a uses `wow_wotlk_3.3.5a` and AzerothCore 3.3.5a uses `wow_wotlk_azerothcore`.

A snapshot taken on one core can be imported on the other. When writing progression data — skills, reputation, quests, talents — the agent only applies entries whose namespace matches its own. Foreign-namespace entries are preserved in the record but ignored during import rather than overwriting local data with potentially incompatible IDs.

Item template entry IDs are assumed to be consistent across all 3.3.5a servers regardless of core, so inventory and equipment imports are not namespace-filtered.

---

## Building

Each module is a standard Maven project targeting Java 21.

```bash
mvn clean package -DskipTests
```

Run this inside each module directory. Output JARs land in `target`. The companion module additionally produces a Windows executable via Launch4j.

---

## Security Notes

All signatures use Ed25519 via BouncyCastle. The companion encrypts key storage with AES-256-GCM keyed to the Windows machine identifier. The agent's auth API and the companion both bind exclusively to localhost and should never be exposed to external networks. Registry nodes should be placed behind a reverse proxy with HTTPS for any public deployment. The server private key file should be protected with appropriate OS-level file permissions — possession of the key is what allows publishing records that nodes will accept.

---

## License

MIT
