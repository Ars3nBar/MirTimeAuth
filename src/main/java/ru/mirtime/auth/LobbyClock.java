package ru.mirtime.auth;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.List;

public class LobbyClock implements Listener {
    private final MirTimeAuth plugin;

    public LobbyClock(MirTimeAuth plugin) {
        this.plugin = plugin;
    }

    public void giveClock(Player p) {
        if (!plugin.getConfig().getBoolean("lobby-clock.enabled", true)) return;
        int slot = plugin.getConfig().getInt("lobby-clock.slot", 8);
        String name = plugin.getConfig().getString("lobby-clock.name", "&#0055ff&l⚡ &#00E5FF&lМеню сервера &7(ПКМ)");
        p.getInventory().setItem(slot, plugin.makeItem(Material.CLOCK, name, List.of("&7Нажмите &fПКМ&7, чтобы открыть меню")));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!plugin.isLoggedIn(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (!plugin.getConfig().getBoolean("lobby-clock.enabled", true)) return;
        if (e.getAction().name().contains("RIGHT_CLICK") && e.getItem() != null && e.getItem().getType() == Material.CLOCK) {
            Location sp = plugin.getTargetSpawn();
            if (plugin.getConfig().getBoolean("lobby-clock.only-spawn-world", true) && sp != null && !p.getWorld().equals(sp.getWorld())) {
                return;
            }
            e.setCancelled(true);
            String cmd = plugin.getConfig().getString("lobby-clock.command", "menu").replaceFirst("^/", "");
            p.performCommand(cmd);
        }
    }
}
