package com.subjex.platform.app.idp;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** IdpAdapterRegistry — named adapters (ID-4). */
public final class IdpAdapterRegistry {

    private final Map<String, IdpAdapter> byId;

    public IdpAdapterRegistry(List<IdpAdapter> adapters) {
        Map<String, IdpAdapter> map = new LinkedHashMap<>();
        List<IdpAdapter> list = adapters == null ? List.of() : adapters;
        for (IdpAdapter adapter : list) {
            map.put(adapter.id(), adapter);
        }
        this.byId = Map.copyOf(map);
    }

    public Optional<IdpAdapter> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id.trim()));
    }

    public Collection<IdpAdapter> all() {
        return byId.values();
    }
}
