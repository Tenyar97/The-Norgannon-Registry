-- RegistryCompanion.lua
-- Core addon logic.
-- Sends INIT on login; the server hook handles signing via the companion.
-- No HTTP transport required in the game client.

local ADDON_NAME    = "RegistryCompanion"
local ADDON_VERSION = "1.0.0"

local _, _, _, tocVersion = GetBuildInfo()
local IS_VANILLA = (tocVersion <= 11200)
local IS_WOTLK   = (tocVersion >= 30000)

-- ============================================================
-- State
-- ============================================================

RC = RC or {}

RC.state = {
    pubkey          = nil,   -- player's hex public key (from SavedVariables or HTTP)
    characterId     = nil,   -- registry UUID for the current character
    characterKey    = nil,   -- "Realm:Character" SavedVariables key
    authInProgress  = false,
    lastError       = nil,
}

-- ============================================================
-- SavedVariables
-- ============================================================

local function initSavedVars()
    if not RegistryCompanionDB then
        RegistryCompanionDB = {}
    end
    if not RegistryCompanionDB.version then
        RegistryCompanionDB.version      = ADDON_VERSION
        RegistryCompanionDB.pubkey       = nil
        RegistryCompanionDB.knownServers = {}
    end
    if not RegistryCompanionDB.characters then
        RegistryCompanionDB.characters = {}
    end
end

local function getCharacterKey()
    local name = UnitName and UnitName("player") or nil
    local realm = GetRealmName and GetRealmName() or nil

    if not name or name == "" then return nil end
    if not realm or realm == "" then realm = "default" end

    return realm .. ":" .. name
end

local function getCharacterId()
    local key = getCharacterKey()
    RC.state.characterKey = key

    if RegistryCompanionDB and key and RegistryCompanionDB.characters then
        return RegistryCompanionDB.characters[key]
    end

    return nil
end

local function setCharacterId(id)
    local key = getCharacterKey()
    RC.state.characterKey = key

    if RegistryCompanionDB and key then
        RegistryCompanionDB.characters = RegistryCompanionDB.characters or {}
        RegistryCompanionDB.characters[key] = id
    end

    RC.state.characterId = id
end

local function migrateLegacyCharacterId()
    if not RegistryCompanionDB or not RegistryCompanionDB.characterId then return end

    local key = getCharacterKey()
    if key then
        RegistryCompanionDB.characters = RegistryCompanionDB.characters or {}
        if not RegistryCompanionDB.characters[key] then
            RegistryCompanionDB.characters[key] = RegistryCompanionDB.characterId
        end
        RegistryCompanionDB.characterId = nil
    end
end

local function getPubkey()
    return RegistryCompanionDB and RegistryCompanionDB.pubkey
end

local function setPubkey(key)
    if RegistryCompanionDB then RegistryCompanionDB.pubkey = key end
    RC.state.pubkey = key
end

-- ============================================================
-- Logging
-- ============================================================

local function log(msg)
    if DEFAULT_CHAT_FRAME then
        DEFAULT_CHAT_FRAME:AddMessage("|cffffcc00[Registry]|r " .. tostring(msg))
    end
end

local function logError(msg)
    if DEFAULT_CHAT_FRAME then
        DEFAULT_CHAT_FRAME:AddMessage("|cffff4444[Registry]|r " .. tostring(msg))
    end
end

-- ============================================================
-- Login — send INIT, server handles the rest
-- ============================================================

function RC.initiateLogin()
    local pubkey     = RC.state.pubkey
    local characterId = getCharacterId()
    RC.state.characterId = characterId

    if not pubkey then
        logError("No public key configured. Run /rc setkey <your-pubkey>.")
        return
    end

    if RC.state.authInProgress then
        log("Auth already in progress — waiting for server response.")
        return
    end

    RC.state.authInProgress = true
    RC.state.lastError      = nil

    local characterIdStr = characterId or "new"
    local message = "INIT;" .. pubkey .. ";" .. characterIdStr

    log("Sending INIT to server...")
    local ok, err = pcall(SendChatMessage, "REGISTRY_AUTH:" .. message, "SAY")
    if ok then
        log("INIT sent. Waiting for response...")
    else
        logError("SendChatMessage failed: " .. tostring(err))
        RC.state.authInProgress = false
    end

    -- Timeout if no response
    C_Timer_After(15, function()
        if RC.state.authInProgress then
            RC.state.authInProgress = false
            RC.state.lastError = "No response from server"
        end
    end)
end

function RC.handleAuthSuccess(characterId)
    RC.state.authInProgress = false
    if characterId and characterId ~= "" then
        setCharacterId(characterId)
    end
    log("Authenticated — characterId: " .. (RC.state.characterId or "?"))
end

function RC.handleAuthRejected(reason)
    RC.state.authInProgress = false
    logError("Auth rejected: " .. (reason or "unknown reason"))
end

-- ============================================================
-- Try to fetch pubkey from companion (optional — works if HTTP available)
-- ============================================================

local function tryFetchPubkey(callback)
    if not RC_Http or not RC_Http.isAvailable() then
        callback(nil)
        return
    end
    RC_Http.getPubkey(function(pk, err)
        callback(pk)
    end)
end

-- ============================================================
-- Incoming addon message handler
-- ============================================================

local function onAddonMessage(self, event, prefix, message, channel, sender)
    if prefix ~= "REGISTRY_AUTH" then return end

    local msgType, payload = message:match("^(%u+)|(.+)$")
    if not msgType then return end

    if msgType == "OK" then
        RC.handleAuthSuccess(payload)
    elseif msgType == "REJECT" then
        RC.handleAuthRejected(payload)
    end
end

-- ============================================================
-- C_Timer_After shim for Vanilla
-- ============================================================

if not C_Timer_After then
    C_Timer_After = function(delay, callback)
        local frame = CreateFrame("Frame")
        local elapsed = 0
        frame:SetScript("OnUpdate", function(self, delta)
            elapsed = elapsed + delta
            if elapsed >= delay then
                self:SetScript("OnUpdate", nil)
                callback()
            end
        end)
    end
end

-- ============================================================
-- Slash commands
-- ============================================================

SLASH_REGISTRYCOMPANION1 = "/registry"
SLASH_REGISTRYCOMPANION2 = "/rc"

SlashCmdList["REGISTRYCOMPANION"] = function(cmd)
    cmd = (cmd or ""):lower():trim()

    if cmd == "status" then
        local pk = RC.state.pubkey
        if pk then
            log("Key: " .. pk:sub(1, 8) .. "..." .. pk:sub(-4))
        else
            log("No key configured. Run /rc setkey <pubkey> or ensure companion is running.")
        end
        local key = getCharacterKey()
        local id = getCharacterId()
        log("Character: " .. (key or "unknown"))
        log("Character ID: " .. (id or "not yet registered"))

    elseif cmd:match("^setkey%s+") then
        local key = cmd:match("^setkey%s+(%x+)$")
        if key and #key == 64 then
            setPubkey(key)
            log("Public key saved: " .. key:sub(1, 8) .. "...")
        else
            logError("Usage: /rc setkey <64-character hex pubkey>")
        end

    elseif cmd == "id" then
        local id = getCharacterId()
        log(id and ("Character ID: " .. id) or "No character ID stored yet.")

    elseif cmd == "reset" then
        setCharacterId(nil)
        RC.state.authInProgress = false
        RC.state.lastError      = nil
        log("Current character state cleared. Will register as new on next auth.")

    elseif cmd == "resetall" then
        if RegistryCompanionDB then
            RegistryCompanionDB.characters = {}
        end
        RC.state.characterId    = nil
        RC.state.characterKey   = getCharacterKey()
        RC.state.authInProgress = false
        RC.state.lastError      = nil
        log("All character registry IDs cleared.")

    elseif cmd == "resetauth" then
        RC.state.authInProgress = false
        RC.state.lastError      = nil
        log("Auth state reset. Run /rc auth to retry.")

    elseif cmd == "auth" then
        RC.initiateLogin()

    else
        log("Registry Companion v" .. ADDON_VERSION)
        log("Commands: /rc status | /rc setkey <pubkey> | /rc id | /rc reset | /rc resetall | /rc auth")
    end
end

-- ============================================================
-- Event registration
-- ============================================================

local frame = CreateFrame("Frame", "RegistryCompanionFrame")

frame:RegisterEvent("ADDON_LOADED")
frame:RegisterEvent("PLAYER_LOGIN")

if IS_WOTLK then
    frame:RegisterEvent("CHAT_MSG_ADDON")
else
    frame:RegisterEvent("CHAT_MSG_SAY")
end

frame:SetScript("OnEvent", function(self, event, ...)
    if event == "ADDON_LOADED" then
        local addonName = ...
        if addonName == ADDON_NAME then
            initSavedVars()
            RC.state.characterId = getCharacterId()
            RC.state.pubkey      = getPubkey()

            -- Try to refresh pubkey from companion if HTTP is available
            if not RC.state.pubkey then
                tryFetchPubkey(function(pk)
                    if pk then
                        setPubkey(pk)
                        log("Public key loaded from companion.")
                    end
                end)
            end
        end

    elseif event == "PLAYER_LOGIN" then
        migrateLegacyCharacterId()
        RC.state.characterKey    = getCharacterKey()
        RC.state.characterId     = getCharacterId()
        RC.state.authInProgress = false
        RC.state.lastError      = nil
        C_Timer_After(1, function()
            RC.initiateLogin()
        end)

    elseif event == "CHAT_MSG_ADDON" then
        onAddonMessage(self, event, ...)

    elseif event == "CHAT_MSG_SAY" then
        local msg, sender = ...
        if msg and msg:sub(1, 13) == "REGISTRY_AUTH" then
            local prefix  = "REGISTRY_AUTH"
            local payload = msg:sub(15)
            onAddonMessage(self, event, prefix, payload, "SAY", sender)
        end
    end
end)
