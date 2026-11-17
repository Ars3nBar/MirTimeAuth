package ru.mirtime.auth;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CaptchaManager {
    private final Map<UUID, String> pending = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack> savedHand = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> savedSlot = new ConcurrentHashMap<>();

    public boolean isPending(UUID id) { return pending.containsKey(id); }
    public String getCode(UUID id) { return pending.get(id); }

    public void clear(UUID id) {
        pending.remove(id);
        savedHand.remove(id);
        savedSlot.remove(id);
    }

    public void giveMap(Player p, String prefix) {
        String code = String.valueOf(1000 + new SecureRandom().nextInt(9000));
        pending.put(p.getUniqueId(), code);

        int slot = 4;
        p.getInventory().setHeldItemSlot(slot);
        savedSlot.put(p.getUniqueId(), slot);
        savedHand.put(p.getUniqueId(), p.getInventory().getItem(slot));

        MapView view = p.getServer().createMap(p.getWorld());
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.getRenderers().clear();
        view.addRenderer(new MapRenderer(false) {
            boolean rendered = false;
            @Override
            public void render(MapView map, MapCanvas canvas, Player player) {
                if (rendered) return;
                rendered = true;
                BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                g.setColor(new Color(10, 18, 36));
                g.fillRect(0, 0, 128, 128);
                g.setColor(new Color(0, 120, 255));
                g.drawRect(3, 3, 121, 121);
                g.drawRect(5, 5, 117, 117);

                SecureRandom r = new SecureRandom();
                for (int i = 0; i < 8; i++) {
                    g.setColor(new Color(0, 180 + r.nextInt(75), 255));
                    g.drawLine(r.nextInt(120), r.nextInt(120), r.nextInt(120), r.nextInt(120));
                }
                g.setColor(new Color(0, 229, 255));
                g.setFont(new Font("SansSerif", Font.BOLD, 14));
                g.drawString("MIR-TIME", 28, 28);

                g.setColor(new Color(0, 255, 102));
                g.setFont(new Font("Monospaced", Font.BOLD, 38));
                g.drawString(code, 18, 78);

                g.setColor(Color.LIGHT_GRAY);
                g.setFont(new Font("SansSerif", Font.PLAIN, 11));
                g.drawString("ВВЕДИТЕ В ЧАТ", 24, 108);
                g.dispose();
                canvas.drawImage(0, 0, img);
            }
        });

        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        if (meta != null) {
            meta.setMapView(view);
            meta.setDisplayName(MirTimeAuth.color("&#00E5FF&lКапча MIR-TIME &7(Напишите цифры в чат)"));
            mapItem.setItemMeta(meta);
        }
        p.getInventory().setItem(slot, mapItem);
        p.updateInventory();
        p.sendMessage(prefix + MirTimeAuth.color("&#00E5FFПроверка на бота! &fПосмотрите на карту в руке и напишите &e4 цифры &fпросто в чат."));
    }

    public void restoreHand(Player p) {
        Integer slot = savedSlot.remove(p.getUniqueId());
        ItemStack prev = savedHand.remove(p.getUniqueId());
        if (slot != null) {
            p.getInventory().setItem(slot, prev);
            p.updateInventory();
        }
    }
}
