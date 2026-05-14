
local ADDON_NAME = "RegistryCompanion"
local BASE_URL   = "http://127.0.0.1:7742"
local TIMEOUT    = 5  -- seconds


local transport = nil

local function detectTransport()
    if lua_http and lua_http.request then
        transport = "lua_http"
        return
    end

    local ok, socket = pcall(require, "socket.http")
    if ok and socket then
        transport = "socket"
        _G._socketHttp = socket  -- store reference
        return
    end

    transport = "none"
end

detectTransport()

local function request(method, path, body)
    local url = BASE_URL .. path

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

    return nil, nil, "No HTTP transport available (lua_http or LuaSocket required)"
end


local function jsonEncode(t)
    local parts = {}
    for k, v in pairs(t) do
        local val
        if type(v) == "string" then
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
    if not s or s == "" then return nil end

    local t = {}

    for k, v in s:gmatch('"([^"]+)":%s*(true|false)') do
        t[k] = (v == "true")
    end

    for k, v in s:gmatch('"([^"]+)":%s*"([^"]*)"') do
        t[k] = v
    end

    for k, v in s:gmatch('"([^"]+)":%s*(%d+)') do
        if not t[k] then  -- don't overwrite string match
            t[k] = tonumber(v)
        end
    end


    return t
end


RC_Http = RC_Http or {}

function RC_Http.isAvailable()
    return transport ~= "none"
end

function RC_Http.getTransport()
    return transport
end

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

function RC_Http.ping(callback)
    local body, code, err = request("GET", "/ping", nil)
    callback(err == nil and code == 200)
end
