package me.aquaenchants.separation;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Безопасная сериализация ItemStack в Base64.
 * Сохраняет все данные предмета, включая ванильные энчанты, кастомные PDC и прочий NBT.
 */
public final class ItemStackBase64 {

    private ItemStackBase64() {}

    public static String toBase64(ItemStack item) throws IOException {
        if (item == null) return "";

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos)) {
            oos.writeObject(item);
            oos.flush();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }

    public static ItemStack fromBase64(String data) throws IOException, ClassNotFoundException {
        if (data == null || data.isEmpty()) return null;
        byte[] bytes = Base64.getDecoder().decode(data);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bais)) {
            Object obj = ois.readObject();
            return (obj instanceof ItemStack) ? (ItemStack) obj : null;
        }
    }
}
