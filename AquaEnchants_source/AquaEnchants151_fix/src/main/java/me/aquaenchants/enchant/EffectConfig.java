package me.aquaenchants.enchant;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class EffectConfig {

    private final String id;
    private final Map<String, Integer> particles;

    public EffectConfig(String id, Map<String, Integer> particles) {
        this.id = id;
        if (particles == null) {
            this.particles = new HashMap<>();
        } else {
            this.particles = new HashMap<>(particles);
        }
    }

    public String getId() {
        return id;
    }

    public Map<String, Integer> getParticles() {
        return Collections.unmodifiableMap(particles);
    }

    public boolean hasParticle(String name) {
        return getParticleCount(name) > 0;
    }

    public int getParticleCount(String name) {
        if (name == null) return 0;
        String key = name.toUpperCase();
        for (Map.Entry<String, Integer> e : particles.entrySet()) {
            if (e.getKey() != null && e.getKey().toUpperCase().equals(key)) {
                return e.getValue() == null ? 0 : e.getValue();
            }
        }
        return 0;
    }
}
