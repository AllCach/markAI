package dev.markai.companion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Server-thread registry. Persistent companion identity is independent of owner and entity identity. */
public final class CompanionRegistry<T> {
    public static final int MAX_COMPANIONS_PER_OWNER = 8;

    private record Entry<T>(T value, UUID owner, UUID entity) {}

    private final Function<T, UUID> idOf;
    private final Function<T, UUID> ownerOf;
    private final Function<T, UUID> entityOf;
    private final Map<UUID, Entry<T>> records = new LinkedHashMap<>();
    private final Map<UUID, UUID> entities = new LinkedHashMap<>();
    private final Map<UUID, LinkedHashSet<UUID>> owners = new LinkedHashMap<>();
    private final Map<UUID, UUID> selected = new LinkedHashMap<>();

    public CompanionRegistry(Function<T, UUID> idOf, Function<T, UUID> ownerOf, Function<T, UUID> entityOf) {
        this.idOf = java.util.Objects.requireNonNull(idOf, "id extractor");
        this.ownerOf = java.util.Objects.requireNonNull(ownerOf, "owner extractor");
        this.entityOf = java.util.Objects.requireNonNull(entityOf, "entity extractor");
    }

    /** Adds or updates the same companion; entity changes are allowed, ownership changes are not. */
    public T add(T value) {
        if (value == null) throw new IllegalArgumentException("Companion is required");
        UUID id = required(idOf.apply(value));
        UUID owner = required(ownerOf.apply(value));
        UUID entity = required(entityOf.apply(value));
        Entry<T> previous = records.get(id);
        if (previous != null && !previous.owner.equals(owner))
            throw new IllegalArgumentException("Companion ownership cannot change");
        UUID entityOwner = entities.get(entity);
        if (entityOwner != null && !entityOwner.equals(id))
            throw new IllegalArgumentException("Entity already belongs to another companion");
        LinkedHashSet<UUID> owned = owners.get(owner);
        if (previous == null && owned != null && owned.size() >= MAX_COMPANIONS_PER_OWNER)
            throw new IllegalArgumentException("Companion limit reached for owner");

        if (previous != null) entities.remove(previous.entity);
        records.put(id, new Entry<>(value, owner, entity));
        entities.put(entity, id);
        owners.computeIfAbsent(owner, unused -> new LinkedHashSet<>()).add(id);
        selected.putIfAbsent(owner, id);
        return value;
    }

    public T get(UUID id) {
        Entry<T> entry = records.get(required(id));
        return entry == null ? null : entry.value;
    }

    public T byEntity(UUID entity) {
        UUID id = entities.get(required(entity));
        return id == null ? null : records.get(id).value;
    }

    public T selected(UUID owner) {
        UUID id = selected.get(required(owner));
        return id == null ? null : records.get(id).value;
    }

    /** Returns a stable insertion-ordered snapshot, including companions that are currently unloaded. */
    public List<T> owned(UUID owner) {
        LinkedHashSet<UUID> ids = owners.get(required(owner));
        if (ids == null) return List.of();
        List<T> result = new ArrayList<>(ids.size());
        for (UUID id : ids) result.add(records.get(id).value);
        return List.copyOf(result);
    }

    public boolean select(UUID owner, UUID id) {
        required(owner); required(id);
        Entry<T> entry = records.get(id);
        if (entry == null || !entry.owner.equals(owner)) return false;
        selected.put(owner, id);
        return true;
    }

    /** Removes only an owner's own companion and selects their next remaining record. */
    public T remove(UUID owner, UUID id) {
        required(owner); required(id);
        Entry<T> entry = records.get(id);
        if (entry == null || !entry.owner.equals(owner)) return null;
        records.remove(id);
        entities.remove(entry.entity);
        LinkedHashSet<UUID> owned = owners.get(owner);
        owned.remove(id);
        if (owned.isEmpty()) {
            owners.remove(owner);
            selected.remove(owner);
        } else if (id.equals(selected.get(owner))) {
            selected.put(owner, owned.iterator().next());
        }
        return entry.value;
    }

    public Collection<T> values() {
        List<T> result = new ArrayList<>(records.size());
        for (Entry<T> entry : records.values()) result.add(entry.value);
        return List.copyOf(result);
    }

    public boolean contains(UUID id) { return records.containsKey(required(id)); }

    public Map<UUID, UUID> selections() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(selected));
    }

    /** Validates every entry before changing any selection. Missing owners retain their first companion. */
    public void restoreSelections(Map<UUID, UUID> saved) {
        if (saved == null) throw new IllegalArgumentException("Selections are required");
        for (var selection : saved.entrySet()) {
            UUID owner = required(selection.getKey());
            UUID id = required(selection.getValue());
            Entry<T> entry = records.get(id);
            if (entry == null || !entry.owner.equals(owner))
                throw new IllegalArgumentException("Selection must belong to its owner");
        }
        selected.clear();
        for (var owner : owners.entrySet()) selected.put(owner.getKey(), owner.getValue().iterator().next());
        selected.putAll(saved);
    }

    public void clear() {
        records.clear(); entities.clear(); owners.clear(); selected.clear();
    }

    private static UUID required(UUID value) {
        if (value == null) throw new IllegalArgumentException("UUID is required");
        return value;
    }
}
