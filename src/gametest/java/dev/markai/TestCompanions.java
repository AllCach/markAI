package dev.markai;

import dev.markai.companion.CompanionRegistry;

import java.lang.reflect.Field;
import java.util.UUID;

/** Access the live mod registry without duplicating its ownership and selection rules in tests. */
final class TestCompanions {
    private TestCompanions() {}

    @SuppressWarnings("unchecked")
    static CompanionRegistry<MarkAIMod.Companion> registry(MarkAIMod mod) throws ReflectiveOperationException {
        Field field = MarkAIMod.class.getDeclaredField("companions");
        field.setAccessible(true);
        return (CompanionRegistry<MarkAIMod.Companion>) field.get(mod);
    }

    static MarkAIMod.Companion selected(MarkAIMod mod, UUID owner) throws ReflectiveOperationException {
        return registry(mod).selected(owner);
    }
}
