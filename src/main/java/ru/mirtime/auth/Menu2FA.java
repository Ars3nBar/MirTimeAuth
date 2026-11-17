package ru.mirtime.auth;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Menu2FA implements Listener {
    private final MirTimeAuth plugin;

    public Menu2FA(MirTimeAuth plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        String key = p.getName().toLowerCase();
        boolean totp = plugin.getDb().getBoolean("users." + key + ".totp-enabled", false);
        String email = plugin.getDb().getString("users." + key + ".email", "Не привязана");

        Inventory inv = Bukkit.createInventory(null, 27, MirTimeAuth.color("&#0055ff&l⚡ &#00E5FF&lЗАЩИТА АККАУНТА (2FA)"));
        ItemStack blue = plugin.makeItem(Material.BLUE_STAINED_GLASS_PANE, "&#0055ff▪", null);
        ItemStack cyan = plugin.makeItem(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "&#00E5FF▫", null);
        for (int s : new int[]{0,1,7,8,9,17,18,19,25,26}) inv.setItem(s, blue);
        for (int s : new int[]{2,3,5,6,20,21,23,24}) inv.setItem(s, cyan);

        inv.setItem(4, plugin.makeItem(Material.SHIELD, "&#00E5FF&lБЕЗОПАСНОСТЬ АККАУНТА", List.of(
                " &#0055ff● &fИгрок: &#00FF66" + p.getName(),
                " &#0055ff● &fGoogle Authenticator: " + (totp ? "&#00FF66ВКЛЮЧЁН ✔" : "&cВЫКЛЮЧЕН"),
                " &#0055ff● &fПочта: &#00E5FF" + email
        )));

        inv.setItem(11, plugin.makeItem(Material.COMPASS, "&#00FF66&l[📱] Google Authenticator", List.of(
                "&7Получить ключ и QR-код для",
                "&7приложения на телефоне.",
                "",
                totp ? "&eНажмите, чтобы перепривязать" : "&#00FF66➥ Нажмите, чтобы подключить"
        )));

        inv.setItem(13, plugin.makeItem(Material.ENDER_EYE, "&#00E5FF&l[🌐] Личный кабинет (Мини-сайт)", List.of(
                "&7Открыть сайт управления аккаунтом.",
                "&7Вход по вашему нику и паролю.",
                "",
                "&#00E5FF➥ Нажмите, чтобы получить ссылку"
        )));

        inv.setItem(15, plugin.makeItem(Material.PAPER, "&e&l[📧] Управление Почтой", List.of(
                "&7Текущая почта: &f" + email,
                "&7Привязать: &a/2fa email <почта>",
                "&7Отвязать: &c/2fa emailoff <пароль>",
                "",
                "&e➥ Нажмите для подсказки"
        )));

        inv.setItem(22, plugin.makeItem(Material.BARRIER, "&c&l[✖ Закрыть меню]", null));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!plugin.isLoggedIn(p.getUniqueId())) { e.setCancelled(true); return; }

        String clean = ChatColor.stripColor(e.getView().getTitle());
        if (!clean.contains("ЗАЩИТА АККАУНТА")) return;
        e.setCancelled(true);

        int slot = e.getRawSlot();
        if (slot == 22) { p.closeInventory(); return; }
        if (slot == 11) { p.closeInventory(); sendGoogleSetup(p); }
        else if (slot == 13) { p.closeInventory(); sendWebUrl(p); }
        else if (slot == 15) {
            p.closeInventory();
            String prefix = plugin.getPrefix();
            p.sendMessage(prefix + MirTimeAuth.color("&fПривязать почту: &#00FF66/2fa email <ваша_почта@mail.ru>"));
            p.sendMessage(prefix + MirTimeAuth.color("&fОтвязать почту: &c/2fa emailoff <ваш_пароль>"));
        }
    }

    public void sendGoogleSetup(Player p) {
        String key = p.getName().toLowerCase();
        String secret = plugin.getMail().generateRandomBase32(16);
        plugin.getDb().set("users." + key + ".totp-secret", secret);
        plugin.getDb().set("users." + key + ".totp-enabled", false);
        plugin.saveDb();

        String otpUri = "otpauth://totp/MIR-TIME:" + URLEncoder.encode(p.getName(), StandardCharsets.UTF_8)
                + "?secret=" + secret + "&issuer=MIR-TIME";
        String qrUrl = "https://" + "://qrserver.com"
                + URLEncoder.encode(otpUri, StandardCharsets.UTF_8);

        p.sendMessage(MirTimeAuth.color("&#0055ff&l&m                                                "));
        p.sendMessage(MirTimeAuth.color(" &#0055ff&l📱 &#00E5FF&lНАСТРОЙКА GOOGLE AUTHENTICATOR"));
        p.sendMessage(MirTimeAuth.color(" &f1. Ключ для ручного ввода: &#00FF66&l" + secret));
        TextComponent qrLink = new TextComponent(MirTimeAuth.color(" &f2. Или откройте готовый QR-код: &#00E5FF&l[ОТКРЫТЬ QR]"));
        qrLink.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, qrUrl));
        p.spigot().sendMessage(qrLink);
        p.sendMessage(MirTimeAuth.color(" &f3. Подтвердите привязку: &#00FF66/2fa confirm <6_цифр>"));
        p.sendMessage(MirTimeAuth.color("&#0055ff&l&m                                                "));
    }

    public void sendWebUrl(Player p) {
        String prefix = plugin.getPrefix();
        if (!plugin.getConfig().getBoolean("web.enabled", true)) {
            p.sendMessage(prefix + MirTimeAuth.color("&cМини-сайт отключён в настройках сервера."));
            return;
        }
        int port = plugin.getConfig().getInt("web.port", 8085);
        String host = plugin.getConfig().getString("web.public-host", "auth.mir-time.ru");
        String url = "http://" + host + ":" + port + "/";

        p.sendMessage(MirTimeAuth.color("&#0055ff&l&m                                                "));
        p.sendMessage(MirTimeAuth.color(" &#0055ff&l🌐 &#00E5FF&lЛИЧНЫЙ КАБИНЕТ MIR-TIME"));
        p.sendMessage(MirTimeAuth.color(" &fВойдите на сайт под своим игровым ником и паролем:"));
        TextComponent link = new TextComponent(MirTimeAuth.color(" &#00FF66&l[🌐 ПЕРЕЙТИ НА МИНИ-САЙТ]"));
        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        p.spigot().sendMessage(link);
        p.sendMessage(MirTimeAuth.color(" &7Адрес сайта: &b" + url));
        p.sendMessage(MirTimeAuth.color("&#0055ff&l&m                                                "));
    }
}
