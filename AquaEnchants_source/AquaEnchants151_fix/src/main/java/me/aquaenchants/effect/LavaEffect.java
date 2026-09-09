package me.aquaenchants.effect;

/**
 * Лавовые партиклы отключены: старый эффект оставлен как no-op,
 * чтобы существующие конфиги не ломали загрузку плагина.
 */
public class LavaEffect {

    public void handle(EffectContext context) {
        // No-op by request: Лавовые частицы создавали лишнюю нагрузку при массовой добыче.
    }
}
