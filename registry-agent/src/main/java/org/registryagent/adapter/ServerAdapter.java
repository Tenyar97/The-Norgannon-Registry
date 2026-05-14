package org.registryagent.adapter;

import org.registryagent.model.CharacterPayload;

public interface ServerAdapter {

	CharacterPayload readCharacter(String characterId);

	boolean writeCharacter(String characterId, CharacterPayload payload);

	boolean importCharacter(String registryUuid, int accountId, CharacterPayload payload);

	boolean characterExistsLocally(String characterId);

	String getNamespace();
}
