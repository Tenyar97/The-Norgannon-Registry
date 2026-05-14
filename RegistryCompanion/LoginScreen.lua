
RC.LoginScreen = RC.LoginScreen or {}

local FRAMES = {
    loginScreen = IS_VANILLA and "LoginScreen" or "GlueParent",
}

local statusLabel = nil

local loginFrame = CreateFrame("Frame", "RCLoginFrame")
loginFrame:RegisterEvent("ADDON_LOADED")
if IS_WOTLK then
    loginFrame:RegisterEvent("LOGIN_SCREEN_OPENED")
end

loginFrame:SetScript("OnEvent", function(self, event, ...)
    if event == "ADDON_LOADED" then
        local name = ...
        if name == "Blizzard_GlueXML" or name == "RegistryCompanion" then
            C_Timer_After(0.5, RC.LoginScreen.init)
        end
    elseif event == "LOGIN_SCREEN_OPENED" then
        C_Timer_After(0.3, RC.LoginScreen.init)
    end
end)

function RC.LoginScreen.init()
    local parent = _G[FRAMES.loginScreen] or UIParent

    if not statusLabel then
        statusLabel = parent:CreateFontString(nil, "OVERLAY", "GameFontNormalSmall")
        statusLabel:SetPoint("BOTTOMRIGHT", parent, "BOTTOMRIGHT", -10, 10)
        statusLabel:SetJustifyH("RIGHT")
    end

    local pk = RC.state and RC.state.pubkey
    if pk then
        local short = pk:sub(1, 6) .. "..." .. pk:sub(-4)
        statusLabel:SetText("|cffffcc00Registry Companion|r |cff88ffff" .. short .. "|r")
    else
        statusLabel:SetText("|cff888888Registry Companion — no key (run /rc setkey)|r")
    end
    statusLabel:Show()
end

function RC.LoginScreen.showFallback(message) end
