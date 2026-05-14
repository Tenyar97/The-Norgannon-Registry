package org.registrynode.model;

public class Pet {

    private String name;
    private String namespace;
    private String creatureRef;
    private int    level;
    private String petType;
    private String slot;
    private int    currentHealth;
    private int    currentMana;
    private int    happiness;

    public Pet() {}

    public String getName()                      { return name; }
    public void   setName(String name)           { this.name = name; }

    public String getNamespace()                       { return namespace; }
    public void   setNamespace(String namespace)       { this.namespace = namespace; }

    public String getCreatureRef()                     { return creatureRef; }
    public void   setCreatureRef(String creatureRef)   { this.creatureRef = creatureRef; }

    public int  getLevel()             { return level; }
    public void setLevel(int level)    { this.level = level; }

    public String getPetType()                   { return petType; }
    public void   setPetType(String petType)     { this.petType = petType; }

    public String getSlot()                  { return slot; }
    public void   setSlot(String slot)       { this.slot = slot; }

    public int  getCurrentHealth()                    { return currentHealth; }
    public void setCurrentHealth(int currentHealth)   { this.currentHealth = currentHealth; }

    public int  getCurrentMana()                  { return currentMana; }
    public void setCurrentMana(int currentMana)   { this.currentMana = currentMana; }

    public int  getHappiness()                { return happiness; }
    public void setHappiness(int happiness)   { this.happiness = happiness; }
}
