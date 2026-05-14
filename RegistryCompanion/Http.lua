-- Http.lua
-- Lightweight HTTP client for the Registry Companion addon.
--
-- Tries two transport layers in order:
--   1. lua_http  — common patch on TrinityCore/AzerothCore private servers
--   2. LuaSocket — socket.http, also common on patched clients
--
-- If neither is available, all calls silently return nil and the addon
-- falls back to the traditional login screen.
--
-- All calls are synchronous from the addon's perspective.
-- Callbacks receive (responseBody, statusCode, errorMessage).
-- On failure: responseBody=nil, statusCode=nil, errorMessage=string.

local ADDON_NAME = "RegistryCompanion"
local BASE_URL   = "http://127.0.0.1:7742"
local TIMEOUT    = 5  -- seconds

-- ============================================================
-- Transport detection
-- ============================================================

local transport = nil

local function detectTransport()
    -- Try lua_http first
    if lua_http and lua_http.request then
        transport = "lua_http"
        return
    end

    -- Try LuaSocket
    local ok, socket = pcall(require, "socket.http")
    if ok and socket then
        transport = "socket"
        _G._socketHttp = socket  -- store reference
        return
    end

    -- Neither available
    transport = "none"
end

detectTransport()

-- ============================================================
-- Core request function
-- ============================================================

-- Internal: perform an HTTP request.
-- method  = "GET" or "POST"
-- path    = e.g. "/status", "/sign"
-- body    = string or nil (for POST)
-- returns responseBody, statusCode, error
local function request(method, path, body)
    local url = BASE_URL .. path

    -- --------------------------------------------------------
    -- Transport: lua_http
    -- --------------------------------------------------------
    if transport == "lua_http" then
        local ok, result = pcall(function()
            local headers = {
                ["Content-Type"]   = "application/json",
                ["Content-Length"] = body and tostring(#body) or "0",
            }
            return lua_http.request({
                url     = url,
                method  = method,
                headers = headers,
                body    = body or "",
                timeout = TIMEOUT,
            })
        end)

        if not ok then
            return nil, nil, "lua_http error: " .. tostring(result)
        end

        -- lua_http returns: body, code, headers, status
        local respBody, code = result[1], result[2]
        return respBody, code, nil
    end

    -- --------------------------------------------------------
    -- Transport: LuaSocket (socket.http)
    -- --------------------------------------------------------
    if transport == "socket" then
        local http  = _G._socketHttp
        local ltn12 = require("ltn12")

        local respTable = {}
        local reqBody   = body or ""

        local ok, code = pcall(function()
            return http.request({
                url     = url,
                method  = method,
                headers = {
                    ["Content-Type"]   = "application/json",
                    ["Content-Length"] = tostring(#reqBody),
                },
                source  = ltn12.source.string(reqBody),
                sink    = ltn12.sink.table(respTable),
                timeout = TIMEOUT,
            })
        end)

        if not ok then
            return nil, nil, "socket error: " .. tostring(code)
        end

        local respBody = table.concat(respTable)
        return respBody, code, nil
    end

    -- --------------------------------------------------------
    -- No transport available
    -- --------------------------------------------------------
    return nil, nil, "No HTTP transport available (lua_http or LuaSocket required)"
end

-- ============================================================
-- JSON helpers
-- ============================================================
-- Minimal JSON encoder/decoder for simple flat objects.
-- We only ever send/receive shallow {key: value} objects
-- so a full parser isn't needed.

local function jsonEncode(t)
    local parts = {}
    for k, v in pairs(t) do
        local val
        if type(v) == "string" then
            -- Escape quotes and backslashes
            val = '"' .. v:gsub('\\', '\\\\'):gsub('"', '\\"') .. '"'
        elseif type(v) == "boolean" then
            val = v and "true" or "false"
        elseif type(v) == "number" then
            val = tostring(v)
        else
            val = '"' .. tostring(v) .. '"'
        end
        table.insert(parts, '"' .. k .. '":' .. val)
    end
    return "{" .. table.concat(parts, ",") .. "}"
end

local function jsonDecode(s)
    -- Minimal: extract string values by pattern matching
    -- Handles flat objects with string and boolean values only
    if not s or s == "" then return nil end

    local t = {}

    -- Booleans
    for k, v in s:gmatch('"([^"]+)":%s*(true|false)') do
        t[k] = (v == "true")
    end

    -- Strings
    for k, v in s:gmatch('"([^"]+)":%s*"([^"]*)"') do
        t[k] = v
    end

    -- Numbers
    for k, v in s:gmatch('"([^"]+)":%s*(%d+)') do
        if not t[k] then  -- don't overwrite string match
            t[k] = tonumber(v)
        end
    end

    -- null → nil (just leave absent)

    return t
end

-- ============================================================
-- Public API
-- ============================================================

RC_Http = RC_Http or {}

-- Returns true if HTTP is available at all
function RC_Http.isAvailable()
    return transport ~= "none"
end

function RC_Http.getTransport()
    return transport
end

-- GET /status
-- Callback: function(ready, pubkey, error)
function RC_Http.getStatus(callback)
    local body, code, err = request("GET", "/status", nil)

    if err or not body then
        callback(false, nil, err or "No response")
        return
    end

    local data = jsonDecode(body)
    if not data then
        callback(false, nil, "Invalid response from companion")
        return
    end

    callback(data.ready == true, data.pubkey, nil)
end

-- GET /pubkey
-- Callback: function(pubkey, error)
function RC_Http.getPubkey(callback)
    local body, code, err = request("GET", "/pubkey", nil)

    if err or not body then
        callback(nil, err or "No response")
        return
    end

    local data = jsonDecode(body)
    if not data or not data.pubkey then
        callback(nil, "No pubkey in response")
        return
    end

    callback(data.pubkey, nil)
end

-- POST /sign
-- message  = the challenge nonce string from the server
-- Callback: function(signature, pubkey, error)
function RC_Http.sign(message, callback)
    local reqBody = jsonEncode({ message = message })
    local body, code, err = request("POST", "/sign", reqBody)

    if err or not body then
        callback(nil, nil, err or "No response")
        return
    end

    local data = jsonDecode(body)
    if not data or not data.signature then
        callback(nil, nil, "No signature in response")
        return
    end

    callback(data.signature, data.pubkey, nil)
end

-- POST /sign (auth token mode)
-- serverId = UUID of the server
-- Callback: function(signature, authToken, pubkey, error)
function RC_Http.signAuthToken(serverId, callback)
    local reqBody = jsonEncode({ message = "SERVER_AUTH", server_id = serverId })
    local body, code, err = request("POST", "/sign", reqBody)

    if err or not body then
        callback(nil, nil, nil, err or "No response")
        return
    end

    local data = jsonDecode(body)
    if not data or not data.signature then
        callback(nil, nil, nil, "No signature in response")
        return
    end

    callback(data.signature, data.auth_token, data.pubkey, nil)
end

-- GET /ping — lightweight reachability check
-- Callback: function(alive)
function RC_Http.ping(callback)
    local body, code, err = request("GET", "/ping", nil)
    callback(err == nil and code == 200)
end
