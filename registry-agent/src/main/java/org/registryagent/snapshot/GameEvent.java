package org.registryagent.snapshot;


public enum GameEvent {

    LEVEL_UP,
    TALENT_CHANGE,

    ITEM_EQUIPPED,
    ITEM_UNEQUIPPED,
    ITEM_LOOTED,

    QUEST_COMPLETED,
    SKILL_GAINED,
    REPUTATION_CHANGED,

    
    CURRENCY_CHANGED,

    PET_TAMED,

    HEARTHSTONE_CHANGED,

    DEATH,

    LOGIN,
    LOGOUT,

    HEARTBEAT;

    public boolean isImmediate() {
        return this == LOGIN || this == LOGOUT;
    }
}
