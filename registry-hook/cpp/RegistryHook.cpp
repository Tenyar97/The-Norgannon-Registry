/*
 * RegistryHook.cpp
 * TrinityCore server script module.
 *
 * For private servers that do NOT run Eluna.
 * Intercepts REGISTRY_AUTH addon messages and routes them to the
 * registry-agent's server-side HTTP API (localhost:8100).
 *
 * Installation:
 *   1. Copy this file to: src/server/scripts/Custom/RegistryHook.cpp
 *   2. Add to CMakeLists.txt in that directory:
 *        set(scripts_Custom
 *            RegistryHook.cpp)
 *   3. Recompile TrinityCore
 *
 * Dependencies:
 *   - libcurl (already a TrinityCore dependency)
 *   - nlohmann/json or similar (or use the minimal parser below)
 *
 * This hook is functionally identical to RegistryHook.lua —
 * same message protocol, same agent API calls, same state model.
 * Choose Lua if your server runs Eluna (no recompile needed).
 * Choose C++ if it doesn't.
 */

#include "ScriptMgr.h"
#include "Player.h"
#include "Chat.h"
#include "World.h"
#include "WorldSession.h"

#include <curl/curl.h>
#include <string>
#include <unordered_map>
#include <sstream>
#include <chrono>
#include <mutex>

// ============================================================
// Configuration
// ============================================================

static constexpr const char* AGENT_HOST    = "127.0.0.1";
static constexpr int         AGENT_PORT    = 8100;
static constexpr const char* ADDON_PREFIX  = "REGISTRY_AUTH";
static constexpr int         TIMEOUT_SEC   = 10;

// ============================================================
// HTTP helpers using libcurl
// ============================================================

static size_t curlWriteCallback(void* contents, size_t size, size_t nmemb, std::string* output) {
    size_t totalSize = size * nmemb;
    output->append(static_cast<char*>(contents), totalSize);
    return totalSize;
}

static std::pair<std::string, long> httpPost(const std::string& path, const std::string& body) {
    std::string url = std::string("http://") + AGENT_HOST + ":" +
                      std::to_string(AGENT_PORT) + path;
    std::string response;
    long httpCode = 0;

    CURL* curl = curl_easy_init();
    if (!curl) return {"", 0};

    struct curl_slist* headers = nullptr;
    headers = curl_slist_append(headers, "Content-Type: application/json");

    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_POST, 1L);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body.c_str());
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, body.size());
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, curlWriteCallback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, TIMEOUT_SEC);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 5L);

    curl_easy_perform(curl);
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &httpCode);
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);

    return {response, httpCode};
}

// ============================================================
// Minimal JSON helpers
// ============================================================

static std::string jsonEscape(const std::string& s) {
    std::string out;
    for (char c : s) {
        if (c == '"')  out += "\\\"";
        else if (c == '\\') out += "\\\\";
        else out += c;
    }
    return out;
}

static std::string buildJson(std::initializer_list<std::pair<std::string, std::string>> fields) {
    std::ostringstream ss;
    ss << "{";
    bool first = true;
    for (const auto& [k, v] : fields) {
        if (!first) ss << ",";
        ss << "\"" << k << "\":\"" << jsonEscape(v) << "\"";
        first = false;
    }
    ss << "}";
    return ss.str();
}

static std::string jsonGetString(const std::string& json, const std::string& key) {
    std::string search = "\"" + key + "\":\"";
    size_t pos = json.find(search);
    if (pos == std::string::npos) return "";
    pos += search.size();
    size_t end = json.find('"', pos);
    if (end == std::string::npos) return "";
    return json.substr(pos, end - pos);
}

static bool jsonGetBool(const std::string& json, const std::string& key) {
    std::string search = "\"" + key + "\":true";
    return json.find(search) != std::string::npos;
}

// ============================================================
// Pending session state
// ============================================================

struct PendingSession {
    std::string pubkey;
    std::string characterId;
    std::chrono::steady_clock::time_point timestamp;
};

static std::unordered_map<uint64, PendingSession> pendingSessions;
static std::mutex sessionMutex;

static void storePending(uint64 guid, const std::string& pubkey, const std::string& charId) {
    std::lock_guard<std::mutex> lock(sessionMutex);
    pendingSessions[guid] = {
        pubkey,
        charId,
        std::chrono::steady_clock::now()
    };
}

static PendingSession* getPending(uint64 guid) {
    std::lock_guard<std::mutex> lock(sessionMutex);
    auto it = pendingSessions.find(guid);
    if (it == pendingSessions.end()) return nullptr;

    // Check expiry
    auto elapsed = std::chrono::steady_clock::now() - it->second.timestamp;
    if (std::chrono::duration_cast<std::chrono::seconds>(elapsed).count() > TIMEOUT_SEC) {
        pendingSessions.erase(it);
        return nullptr;
    }
    return &it->second;
}

static void clearPending(uint64 guid) {
    std::lock_guard<std::mutex> lock(sessionMutex);
    pendingSessions.erase(guid);
}

// ============================================================
// Agent API
// ============================================================

static std::string requestChallenge(const std::string& pubkey, const std::string& charId) {
    std::string body = buildJson({
        {"player_pub_key", pubkey},
        {"character_id",   charId.empty() ? "new" : charId}
    });

    auto [respBody, code] = httpPost("/auth/challenge", body);
    if (code != 200 || respBody.empty()) return "";

    return jsonGetString(respBody, "nonce");
}

static std::tuple<bool, std::string, std::string> verifyResponse(
        const std::string& pubkey,
        const std::string& charId,
        const std::string& signature) {

    std::string body = buildJson({
        {"player_pub_key", pubkey},
        {"character_id",   charId.empty() ? "new" : charId},
        {"signature",      signature}
    });

    auto [respBody, code] = httpPost("/auth/verify", body);
    if (respBody.empty()) return {false, "", "Agent unreachable"};

    bool        success  = jsonGetBool(respBody, "success");
    std::string charIdOut= jsonGetString(respBody, "character_id");
    std::string reason   = jsonGetString(respBody, "reason");

    return {success, charIdOut, reason};
}

// ============================================================
// Script hook
// ============================================================

class RegistryHookPlayerScript : public PlayerScript {
public:
    RegistryHookPlayerScript() : PlayerScript("RegistryHookPlayerScript") {}

    // OnChat fires for all chat types including CHAT_MSG_ADDON
    bool OnChat(Player* player, uint32 type, uint32 /*lang*/,
                std::string& msg, std::string& addon) override
    {
        // CHAT_MSG_ADDON type = 32
        if (type != 32) return false;
        if (addon != ADDON_PREFIX) return false;

        handleAddonMessage(player, msg);
        return false;  // don't suppress the message
    }

private:
    void handleAddonMessage(Player* player, const std::string& message) {
        // Parse: "MSGTYPE|payload"
        size_t sep = message.find('|');
        if (sep == std::string::npos) return;

        std::string msgType = message.substr(0, sep);
        std::string payload = message.substr(sep + 1);
        uint64 guid = player->GetGUID().GetRawValue();

        // ---- INIT ----
        if (msgType == "INIT") {
            // payload: "pubkey|characterId"
            size_t pipe = payload.find('|');
            if (pipe == std::string::npos) {
                sendToAddon(player, "REJECT", "Malformed INIT message");
                return;
            }

            std::string pubkey  = payload.substr(0, pipe);
            std::string charId  = payload.substr(pipe + 1);

            if (pubkey.size() != 64) {
                sendToAddon(player, "REJECT", "Invalid public key format");
                return;
            }

            if (charId == "new") charId = "";

            TC_LOG_INFO("registry", "INIT from %s pubkey=%s...",
                        player->GetName().c_str(), pubkey.substr(0, 8).c_str());

            std::string nonce = requestChallenge(pubkey, charId);
            if (nonce.empty()) {
                sendToAddon(player, "REJECT", "Server error — could not generate challenge");
                return;
            }

            storePending(guid, pubkey, charId);
            sendToAddon(player, "CHALLENGE", nonce);

            TC_LOG_INFO("registry", "Challenge issued to %s", player->GetName().c_str());
        }

        // ---- SIG ----
        else if (msgType == "SIG") {
            // payload: "signature|pubkey"
            size_t pipe = payload.find('|');
            if (pipe == std::string::npos) {
                sendToAddon(player, "REJECT", "Malformed SIG message");
                return;
            }

            std::string signature = payload.substr(0, pipe);
            std::string pubkey    = payload.substr(pipe + 1);

            PendingSession* session = getPending(guid);
            if (!session) {
                sendToAddon(player, "REJECT", "No pending challenge — start again");
                return;
            }

            if (pubkey != session->pubkey) {
                clearPending(guid);
                sendToAddon(player, "REJECT", "Public key mismatch");
                return;
            }

            TC_LOG_INFO("registry", "Verifying signature for %s", player->GetName().c_str());

            auto [success, charId, reason] = verifyResponse(
                session->pubkey, session->characterId, signature);

            clearPending(guid);

            if (success) {
                TC_LOG_INFO("registry", "Auth SUCCESS for %s characterId=%s",
                            player->GetName().c_str(), charId.c_str());
                sendToAddon(player, "OK", charId);
            } else {
                TC_LOG_INFO("registry", "Auth REJECTED for %s reason=%s",
                            player->GetName().c_str(), reason.c_str());
                sendToAddon(player, "REJECT", reason.empty() ? "Verification failed" : reason);
            }
        }
    }

    void sendToAddon(Player* player, const std::string& msgType, const std::string& payload) {
        std::string message = msgType + "|" + payload;
        player->GetSession()->SendAddonMessage(
            ADDON_PREFIX, message, CHAT_MSG_ADDON, player);
    }
};

// ============================================================
// Registration
// ============================================================

void AddSC_RegistryHook() {
    new RegistryHookPlayerScript();
}
