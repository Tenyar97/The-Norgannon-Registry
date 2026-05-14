package org.registryagent.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;


class CharacterPayloadMirrorTest {

    private static final Set<String> EXPECTED_FIELDS = Set.of(
            "identity",
            "stats",
            "equipment",
            "inventory",
            "bank",
            "currency",
            "progression",
            "pets",
            "hearthstone",
            "extensions"
    );

    @Test
    void declaredFieldsMustMatchNodeMirror() {
        Set<String> actual = Arrays.stream(CharacterPayload.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertEquals(EXPECTED_FIELDS, actual,
                "CharacterPayload fields have changed. Mirror the same change in " +
                "registry-node/src/main/java/org/registrynode/model/CharacterPayload.java " +
                "and update EXPECTED_FIELDS in both CharacterPayloadMirrorTest classes.");
    }
}
