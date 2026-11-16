package ru.mirtime.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
public class MirTimeAuth extends JavaPlugin implements CommandExecutor, Listener {

    private File usersFile;
    private YamlConfiguration db;
    private World authWorld;
    private HttpServer webServer;

    private final Set<UUID> loggedIn = ConcurrentHashMap.newKeySet();
    private final Set<UUID> awaiting2FA = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> pendingCaptcha = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack> savedMainHand = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> savedSlot = new ConcurrentHashMap<>();
    private final Map<UUID, Long> joinTime = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> authBars = new ConcurrentHashMap<>();
    private final Map<String, String> emailVerifyCodes = new ConcurrentHashMap<>();
    private final Map<String, String> emailPendingAddr = new ConcurrentHashMap<>();
    private final Map<String, String> recoveryCodes = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        usersFile = new File(getDataFolder(), "users.yml");
        if (!usersFile.exists()) {
            try { usersFile.createNewFile(); } catch (IOException ignored) {}
        }
        db = YamlConfiguration.loadConfiguration(usersFile);

        setupAuthWorld();
        startWebServer();

        for (String cmd : List.of("register", "login", "changepassword", "2fa", "recovery", "logout", "authspawn")) {
            if (getCommand(cmd) != null) getCommand(cmd).setExecutor(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);

        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                long maxSec = getConfig().getLong("security.login-timeout-seconds", 90);
                long maxMs = maxSec * 1000L;

                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (loggedIn.contains(p.getUniqueId())) {
                        removeAuthBar(p);
                        continue;
                    }
                    long joined = joinTime.getOrDefault(p.getUniqueId(), now);
                    long elapsed = now - joined;
                    if (elapsed > maxMs) {
                        removeAuthBar(p);
                        restoreHandItem(p);
                        p.kickPlayer(color("&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» &cВремя на авторизацию вышло!"));
                        continue;
                    }
                    long leftSec = Math.max(1, (maxMs - elapsed) / 1000L);
                    double progress = Math.max(0.0, Math.min(1.0, (double) (maxMs - elapsed) / maxMs));
                    updateAuthBar(p, leftSec, progress);

                    String key = p.getName().toLowerCase();
                    if (pendingCaptcha.containsKey(p.getUniqueId())) {
                        p.sendTitle(color("&#00E5FF&lПРОВЕРКА КАПЧИ"), color("&fНапишите &e4 цифры &fс карты прямо в чат"), 0, 25, 0);
                    } else if (awaiting2FA.contains(p.getUniqueId())) {
                        p.sendTitle(color("&#00E5FF&l2FA ЗАЩИТА"), color("&fНапишите &e6 цифр &fиз Google Authenticator в чат"), 0, 25, 0);
                    } else if (db.contains("users." + key + ".hash")) {
                        p.sendTitle(color("&#0055ff&l⚡ MIR-TIME ⚡"), color("&fВойдите в аккаунт: &#00FF66/login <пароль>"), 0, 25, 0);
                    } else {
                        p.sendTitle(color("&#0055ff&l⚡ MIR-TIME ⚡"), color("&fЗарегистрируйтесь: &#00FF66/reg <пароль> <пароль>"), 0, 25, 0);
                    }
                }
            }
        }.runTaskTimer(this, 10L, 20L);

        Bukkit.getConsoleSender().sendMessage(color("&#00E5FF  MirTimeAuth v1.1.0 (2FA GUI + WebCabinet + Email) запущен!"));
    }

    @Override
    public void onDisable() {
        if (webServer != null) webServer.stop(0);
        for (BossBar bar : authBars.values()) bar.removeAll();
        authBars.clear();
    }

    private void updateAuthBar(Player p, long leftSec, double progress) {
        BossBar bar = authBars.computeIfAbsent(p.getUniqueId(), id -> {
            BossBar b = Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SOLID);
            b.addPlayer(p);
            return b;
        });
        String stage = pendingCaptcha.containsKey(p.getUniqueId()) ? "Пройдите капчу с карты"
                : (awaiting2FA.contains(p.getUniqueId()) ? "Введите код 2FA в чат" : "Авторизуйтесь на сервере");
        bar.setTitle(color("&#0055ff&l⚡ &#00E5FF&l" + stage + " &8| &fОсталось: &#00FF66" + leftSec + " сек."));
        bar.setProgress(progress);
        bar.setColor(leftSec <= 15 ? BarColor.RED : BarColor.BLUE);
    }

    private void removeAuthBar(Player p) {
        BossBar bar = authBars.remove(p.getUniqueId());
        if (bar != null) bar.removeAll();
    }
    private void setupAuthWorld() {
        String wName = getConfig().getString("security.auth-world", "auth_void");
        authWorld = Bukkit.getWorld(wName);
        if (authWorld == null) {
            WorldCreator wc = new WorldCreator(wName);
            wc.environment(World.Environment.NORMAL);
            wc.type(WorldType.FLAT);
            wc.generateStructures(false);
            wc.generator(new ChunkGenerator() {});
            authWorld = wc.createWorld();
        }
        if (authWorld != null) {
            authWorld.setDifficulty(Difficulty.PEACEFUL);
            authWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            authWorld.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
            authWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            authWorld.setTime(18000L);
            for (Entity ent : authWorld.getEntities()) {
                if (!(ent instanceof Player)) ent.remove();
            }
        }
    }

    private void sendToVoid(Player p) {
        if (authWorld == null) return;
        p.teleport(new Location(authWorld, 0.5, 120.0, 0.5, 0f, 0f));
        p.setGravity(false);
        p.setAllowFlight(true);
        p.setFlying(true);
    }

    private Location getTargetSpawn() {
        if (db.contains("spawn.world")) {
            World w = Bukkit.getWorld(db.getString("spawn.world", "world"));
            if (w != null) {
                return new Location(w,
                        db.getDouble("spawn.x"),
                        db.getDouble("spawn.y"),
                        db.getDouble("spawn.z"),
                        (float) db.getDouble("spawn.yaw"),
                        (float) db.getDouble("spawn.pitch"));
            }
        }
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    private synchronized void saveDb() {
        try { db.save(usersFile); } catch (IOException ignored) {}
    }

    private String getIp(Player p) {
        return p.getAddress() != null ? p.getAddress().getAddress().getHostAddress() : "127.0.0.1";
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        String key = p.getName().toLowerCase();
        String ip = getIp(p);

        loggedIn.remove(id);
        awaiting2FA.remove(id);
        pendingCaptcha.remove(id);
        joinTime.put(id, System.currentTimeMillis());

        String prefix = color(getConfig().getString("prefix", "&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» "));
        if (db.contains("users." + key + ".hash")) {
            String lastIp = db.getString("users." + key + ".last-ip", "");
            long sessionUntil = db.getLong("users." + key + ".session-until", 0L);
            if (ip.equals(lastIp) && System.currentTimeMillis() < sessionUntil) {
                completeLogin(p, key, prefix, true);
                return;
            }
        }

        sendToVoid(p);
        boolean needCaptcha = getConfig().getBoolean("security.captcha-enabled", true) && !p.isOp();
        if (needCaptcha) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline() && !loggedIn.contains(p.getUniqueId())) {
                    giveCaptchaMap(p, prefix);
                }
            }, 5L);
        } else {
            promptAuth(p, key, prefix);
        }
    }

    private void promptAuth(Player p, String key, String prefix) {
        if (db.contains("users." + key + ".hash")) {
            p.sendMessage(prefix + color("&fДобро пожаловать! Введите &#00FF66/login <пароль>"));
        } else {
            p.sendMessage(prefix + color("&fДобро пожаловать на &bMIR-TIME&f! Зарегистрируйтесь: &#00FF66/reg <пароль> <повтор>"));
        }
    }

    private void giveCaptchaMap(Player p, String prefix) {
        String code = String.valueOf(1000 + new SecureRandom().nextInt(9000));
        pendingCaptcha.put(p.getUniqueId(), code);

        int slot = 4;
        p.getInventory().setHeldItemSlot(slot);
        savedSlot.put(p.getUniqueId(), slot);
        savedMainHand.put(p.getUniqueId(), p.getInventory().getItem(slot));
        MapView view = Bukkit.createMap(p.getWorld());
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
            meta.setDisplayName(color("&#00E5FF&lКапча MIR-TIME &7(Напишите цифры в чат)"));
            mapItem.setItemMeta(meta);
        }
        p.getInventory().setItem(slot, mapItem);
        p.updateInventory();
        p.sendMessage(prefix + color("&#00E5FFПроверка на бота! &fПосмотрите на карту в руке и напишите &e4 цифры &fпросто в чат."));
    }
    private void restoreHandItem(Player p) {
        Integer slot = savedSlot.remove(p.getUniqueId());
        ItemStack prev = savedMainHand.remove(p.getUniqueId());
        if (slot != null) {
            p.getInventory().setItem(slot, prev);
            p.updateInventory();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        removeAuthBar(p);
        restoreHandItem(p);
        p.setGravity(true);
        if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
        loggedIn.remove(id);
        awaiting2FA.remove(id);
        pendingCaptcha.remove(id);
        joinTime.remove(id);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (loggedIn.contains(id)) return;

        e.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        String prefix = color(getConfig().getString("prefix", "&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» "));

        if (pendingCaptcha.containsKey(id)) {
            String expected = pendingCaptcha.get(id);
            Bukkit.getScheduler().runTask(this, () -> {
                if (!p.isOnline()) return;
                if (msg.equals(expected)) {
                    pendingCaptcha.remove(id);
                    restoreHandItem(p);
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.3f);
                    p.sendMessage(prefix + color("&#00FF66Капча успешно пройдена!"));
                    promptAuth(p, p.getName().toLowerCase(), prefix);
                } else {
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                    p.sendMessage(prefix + color("&cНеверный код с карты! Напишите &e4 цифры &cс карты в руке прямо в чат."));
                }
            });
            return;
        }

        if (awaiting2FA.contains(id)) {
            String key = p.getName().toLowerCase();
            String secret = db.getString("users." + key + ".totp-secret", "");
            Bukkit.getScheduler().runTask(this, () -> {
                if (!p.isOnline()) return;
                if (verifyTOTP(secret, msg)) {
                    awaiting2FA.remove(id);
                    completeLogin(p, key, prefix, false);
                } else {
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                    p.sendMessage(prefix + color("&cНеверный код! Напишите &e6 цифр &cиз Google Authenticator прямо в чат."));
                }
            });
        }
    }
    private void open2FaMenu(Player p) {
        String key = p.getName().toLowerCase();
        boolean totp = db.getBoolean("users." + key + ".totp-enabled", false);
        String email = db.getString("users." + key + ".email", "Не привязана");

        Inventory inv = Bukkit.createInventory(null, 27, color("&#0055ff&l⚡ &#00E5FF&lЗАЩИТА АККАУНТА (2FA)"));
        ItemStack blue = item(Material.BLUE_STAINED_GLASS_PANE, "&#0055ff▪", null);
        ItemStack cyan = item(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "&#00E5FF▫", null);
        for (int s : new int[]{0,1,7,8,9,17,18,19,25,26}) inv.setItem(s, blue);
        for (int s : new int[]{2,3,5,6,20,21,23,24}) inv.setItem(s, cyan);

        inv.setItem(4, item(Material.SHIELD, "&#00E5FF&lБЕЗОПАСНОСТЬ АККАУНТА", List.of(
                " &#0055ff● &fИгрок: &#00FF66" + p.getName(),
                " &#0055ff● &fGoogle Authenticator: " + (totp ? "&#00FF66ВКЛЮЧЁН ✔" : "&cВЫКЛЮЧЕН"),
                " &#0055ff● &fПочта: &#00E5FF" + email
        )));

        inv.setItem(11, item(Material.COMPASS, "&#00FF66&l[📱] Google Authenticator", List.of(
                "&7Получить ключ и QR-код для",
                "&7приложения на телефоне.",
                "",
                totp ? "&eНажмите, чтобы перепривязать" : "&#00FF66➥ Нажмите, чтобы подключить"
        )));

        inv.setItem(13, item(Material.ENDER_EYE, "&#00E5FF&l[🌐] Личный кабинет (Мини-сайт)", List.of(
                "&7Открыть сайт управления аккаунтом.",
                "&7Вход по вашему нику и паролю.",
                "",
                "&#00E5FF➥ Нажмите, чтобы получить ссылку"
        )));

        inv.setItem(15, item(Material.PAPER, "&e&l[📧] Привязка Почты", List.of(
                "&7Текущая почта: &f" + email,
                "&7Используется для восстановления",
                "&7забытого пароля через /recovery.",
                "",
                "&e➥ Нажмите для инструкции"
        )));

        inv.setItem(22, item(Material.BARRIER, "&c&l[✖ Закрыть меню]", null));
        p.openInventory(inv);
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!loggedIn.contains(p.getUniqueId())) { e.setCancelled(true); return; }

        String clean = ChatColor.stripColor(e.getView().getTitle());
        if (!clean.contains("ЗАЩИТА АККАУНТА")) return;
        e.setCancelled(true);

        int slot = e.getRawSlot();
        if (slot == 22) { p.closeInventory(); return; }
        if (slot == 11) {
            p.closeInventory();
            sendGoogleSetup(p);
        } else if (slot == 13) {
            p.closeInventory();
            sendWebUrl(p);
        } else if (slot == 15) {
            p.closeInventory();
            String prefix = color(getConfig().getString("prefix", "&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» "));
            p.sendMessage(prefix + color("&fЧтобы привязать почту, введите: &#00FF66/2fa email <ваша_почта@mail.ru>"));
            p.sendMessage(prefix + color("&7Либо укажите её прямо в Личном кабинете на сайте."));
        }
    }
    private void sendGoogleSetup(Player p) {
        String key = p.getName().toLowerCase();
        String prefix = color(getConfig().getString("prefix", "&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» "));
        String secret = generateRandomBase32(16);
        db.set("users." + key + ".totp-secret", secret);
        db.set("users." + key + ".totp-enabled", false);
        saveDb();

        String otpUri = "otpauth://totp/MIR-TIME:" + URLEncoder.encode(p.getName(), StandardCharsets.UTF_8)
                + "?secret=" + secret + "&issuer=MIR-TIME";
        String qrUrl = "https://qrserver.com"
                + URLEncoder.encode(otpUri, StandardCharsets.UTF_8);

        p.sendMessage(color("&#0055ff&l&m                                                "));
        p.sendMessage(color(" &#0055ff&l📱 &#00E5FF&lНАСТРОЙКА GOOGLE AUTHENTICATOR"));
        p.sendMessage(color(" &f1. Ключ для ручного ввода: &#00FF66&l" + secret));
        TextComponent qrLink = new TextComponent(color(" &f2. Или откройте готовый QR-код: &#00E5FF&l[ОТКРЫТЬ QR]"));
        qrLink.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, qrUrl));
        p.spigot().sendMessage(qrLink);
        p.sendMessage(color(" &f3. Подтвердите привязку: &#00FF66/2fa confirm <6_цифр>"));
        p.sendMessage(color("&#0055ff&l&m                                                "));
        p.sendMessage(prefix + color("&7После привязки при входе достаточно просто написать 6 цифр в чат."));
    }

    private void sendWebUrl(Player p) {
        String prefix = color(getConfig().getString("prefix", "&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» "));
        if (!getConfig().getBoolean("web.enabled", true)) {
            p.sendMessage(prefix + color("&cМини-сайт отключён в настройках сервера."));
            return;
        }
        int port = getConfig().getInt("web.port", 8085);
        String host = getConfig().getString("web.public-host", "auto");
        if (host.equalsIgnoreCase("auto")) {
            host = Bukkit.getIp().isEmpty() ? getPublicIp() : Bukkit.getIp();
        }
        String url = "http://" + host + ":" + port + "/";

        p.sendMessage(color("&#0055ff&l&m                                                "));
        p.sendMessage(color(" &#0055ff&l🌐 &#00E5FF&lЛИЧНЫЙ КАБИНЕТ MIR-TIME"));
        p.sendMessage(color(" &fВойдите на сайт под своим игровым ником и паролем:"));
        TextComponent link = new TextComponent(color(" &#00FF66&l[🌐 ПЕРЕЙТИ НА МИНИ-САЙТ]"));
        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        p.spigot().sendMessage(link);
        p.sendMessage(color(" &7Адрес сайта: &b" + url));
        p.sendMessage(color("&#0055ff&l&m                                                "));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        String prefix = color(getConfig().getString("prefix", "&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» "));
        String cName = cmd.getName().toLowerCase();
        String key = p.getName().toLowerCase();

        if (pendingCaptcha.containsKey(p.getUniqueId())) {
            p.sendMessage(prefix + color("&cСначала напишите &e4 цифры &cс карты в руке прямо в чат!"));
            return true;
        }
        if (cName.equals("recovery")) {
            if (!getConfig().getBoolean("email.enabled", false)) {
                p.sendMessage(prefix + color("&cВосстановление по почте временно отключено администрацией."));
                return true;
            }
            String email = db.getString("users." + key + ".email", "");
            if (email.isEmpty()) {
                p.sendMessage(prefix + color("&cК вашему аккаунту не привязана почта!"));
                return true;
            }
            if (args.length == 0) {
                String code = String.valueOf(100000 + new SecureRandom().nextInt(900000));
                recoveryCodes.put(key, code);
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> sendSmtpMail(email, "MIR-TIME: Код сброса пароля", "Ваш код для сброса пароля: " + code));
                p.sendMessage(prefix + color("&#00FF66Код сброса отправлен на привязанную почту! &fВведите: &#00E5FF/recovery <код> <новый_пароль>"));
                return true;
            }
            if (args.length >= 2) {
                String saved = recoveryCodes.get(key);
                if (saved == null || !saved.equals(List.of(args).get(0))) {
                    p.sendMessage(prefix + color("&cНеверный код восстановления!"));
                    return true;
                }
                String newPass = List.of(args).get(1);
                String salt = generateRandomBase32(16);
                db.set("users." + key + ".salt", salt);
                db.set("users." + key + ".hash", hashPassword(newPass, salt));
                saveDb();
                recoveryCodes.remove(key);
                p.sendMessage(prefix + color("&#00FF66Пароль успешно сброшен! Войдите через &#00E5FF/login " + newPass));
            }
            return true;
        }

        if (cName.equals("register")) {
            if (loggedIn.contains(p.getUniqueId())) { p.sendMessage(prefix + color("&cВы уже авторизованы!")); return true; }
            if (db.contains("users." + key + ".hash")) { p.sendMessage(prefix + color("&cАккаунт уже зарегистрирован! Введите &#00FF66/login <пароль>")); return true; }
            if (args.length < 2) { p.sendMessage(prefix + color("&fИспользование: &#00FF66/reg <пароль> <повтор_пароля>")); return true; }
            String pass1 = List.of(args).get(0);
            String pass2 = List.of(args).get(1);
            if (!pass1.equals(pass2)) { p.sendMessage(prefix + color("&cПароли не совпадают!")); return true; }
            int min = getConfig().getInt("security.min-password-length", 4);
            int max = getConfig().getInt("security.max-password-length", 32);
            if (pass1.length() < min || pass1.length() > max) {
                p.sendMessage(prefix + color("&cДлина пароля должна быть от " + min + " до " + max + " символов!"));
                return true;
            }

            String salt = generateRandomBase32(16);
            db.set("users." + key + ".name", p.getName());
            db.set("users." + key + ".salt", salt);
            db.set("users." + key + ".hash", hashPassword(pass1, salt));
            completeLogin(p, key, prefix, false);
            p.sendMessage(prefix + color("&#00FF66Регистрация завершена! &7Меню 2FA и мини-сайт: &#00E5FF/2fa"));
            return true;
        }

        if (cName.equals("login")) {
            if (loggedIn.contains(p.getUniqueId())) { p.sendMessage(prefix + color("&cВы уже вошли в аккаунт!")); return true; }
            if (!db.contains("users." + key + ".hash")) { p.sendMessage(prefix + color("&cВы не зарегистрированы! Введите &#00FF66/reg <пароль> <пароль>")); return true; }
            if (args.length < 1) { p.sendMessage(prefix + color("&fИспользование: &#00FF66/login <пароль>")); return true; }

            String pass = List.of(args).get(0);
            String salt = db.getString("users." + key + ".salt", "");
            if (!db.getString("users." + key + ".hash", "").equals(hashPassword(pass, salt))) {
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                p.sendMessage(prefix + color("&cНеверный пароль!"));
                return true;
            }

            String totpSecret = db.getString("users." + key + ".totp-secret", "");
            boolean totpEnabled = db.getBoolean("users." + key + ".totp-enabled", false);
            if (totpEnabled && !totpSecret.isEmpty()) {
                awaiting2FA.add(p.getUniqueId());
                p.sendMessage(prefix + color("&#00E5FFПароль верный! &fНапишите &e6-значный код &fиз Google Authenticator прямо в чат (без /)."));
                return true;
            }

            completeLogin(p, key, prefix, false);
            return true;
        }
        if (!loggedIn.contains(p.getUniqueId())) {
            p.sendMessage(prefix + color("&cСначала пройдите авторизацию!"));
            return true;
        }

        if (cName.equals("2fa")) {
            if (args.length == 0) {
                open2FaMenu(p);
                return true;
            }
            String sub = List.of(args).get(0).toLowerCase();
            if (sub.equals("setup")) { sendGoogleSetup(p); return true; }
            if (sub.equals("site") || sub.equals("web")) { sendWebUrl(p); return true; }
            if (sub.equals("confirm") && args.length >= 2) {
                String secret = db.getString("users." + key + ".totp-secret", "");
                if (secret.isEmpty() || !verifyTOTP(secret, List.of(args).get(1))) {
                    p.sendMessage(prefix + color("&cНеверный код! Откройте &#00E5FF/2fa &cи нажмите на Google Authenticator."));
                    return true;
                }
                db.set("users." + key + ".totp-enabled", true);
                saveDb();
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
                p.sendMessage(prefix + color("&#00FF66Google Authenticator успешно привязан!"));
                return true;
            }
            if (sub.equals("off") && args.length >= 2) {
                String pass = List.of(args).get(1);
                String salt = db.getString("users." + key + ".salt", "");
                if (!db.getString("users." + key + ".hash", "").equals(hashPassword(pass, salt))) {
                    p.sendMessage(prefix + color("&cНеверный пароль аккаунта!"));
                    return true;
                }
                db.set("users." + key + ".totp-enabled", false);
                db.set("users." + key + ".totp-secret", null);
                saveDb();
                p.sendMessage(prefix + color("&eДвухфакторная защита (2FA) отключена."));
                return true;
            }
            if (sub.equals("email") && args.length >= 2) {
                String addr = List.of(args).get(1).trim();
                if (!addr.contains("@") || !addr.contains(".")) {
                    p.sendMessage(prefix + color("&cУкажите корректный Email адрес!"));
                    return true;
                }
                if (!getConfig().getBoolean("email.enabled", false)) {
                    db.set("users." + key + ".email", addr);
                    saveDb();
                    p.sendMessage(prefix + color("&#00FF66Почта сохранена в профиле: &f" + addr));
                    return true;
                }
                String code = String.valueOf(100000 + new SecureRandom().nextInt(900000));
                emailVerifyCodes.put(key, code);
                emailPendingAddr.put(key, addr);
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> sendSmtpMail(addr, "MIR-TIME: Подтверждение почты", "Ваш код привязки: " + code));
                p.sendMessage(prefix + color("&#00E5FFКод отправлен на &f" + addr + "&#00E5FF! Введите: &#00FF66/2fa emailconfirm <код>"));
                return true;
            }
            if (sub.equals("emailconfirm") && args.length >= 2) {
                String saved = emailVerifyCodes.get(key);
                if (saved == null || !saved.equals(List.of(args).get(1))) {
                    p.sendMessage(prefix + color("&cНеверный код подтверждения почты!"));
                    return true;
                }
                db.set("users." + key + ".email", emailPendingAddr.remove(key));
                emailVerifyCodes.remove(key);
                saveDb();
                p.sendMessage(prefix + color("&#00FF66Почта успешно подтверждена и привязана!"));
                return true;
            }
            return true;
        }

        if (cName.equals("authspawn")) {
            if (!p.hasPermission("mirtime.auth.admin")) return true;
            Location l = p.getLocation();
            db.set("spawn.world", l.getWorld().getName());
            db.set("spawn.x", l.getX());
            db.set("spawn.y", l.getY());
            db.set("spawn.z", l.getZ());
            db.set("spawn.yaw", l.getYaw());
            db.set("spawn.pitch", l.getPitch());
            saveDb();
            p.sendMessage(prefix + color("&#00FF66Точка спавна после авторизации установлена!"));
            return true;
        }

        if (cName.equals("changepassword")) {
            if (args.length < 2) { p.sendMessage(prefix + color("&fИспользование: &#00FF66/cp <старый_пароль> <новый_пароль>")); return true; }
            String oldPass = List.of(args).get(0);
            String newPass = List.of(args).get(1);
            String salt = db.getString("users." + key + ".salt", "");
            if (!db.getString("users." + key + ".hash", "").equals(hashPassword(oldPass, salt))) {
                p.sendMessage(prefix + color("&cСтарый пароль указан неверно!"));
                return true;
            }
            String newSalt = generateRandomBase32(16);
            db.set("users." + key + ".salt", newSalt);
            db.set("users." + key + ".hash", hashPassword(newPass, newSalt));
            saveDb();
            p.sendMessage(prefix + color("&#00FF66Пароль успешно изменён!"));
            return true;
        }

        if (cName.equals("logout")) {
            db.set("users." + key + ".session-until", 0L);
            saveDb();
            loggedIn.remove(p.getUniqueId());
            joinTime.put(p.getUniqueId(), System.currentTimeMillis());
            sendToVoid(p);
            p.sendMessage(prefix + color("&eВы вышли из сессии. Введите &#00FF66/login <пароль>"));
            return true;
        }
        return true;
    }
    private void completeLogin(Player p, String key, String prefix, boolean autoSession) {
        loggedIn.add(p.getUniqueId());
        removeAuthBar(p);
        long hours = getConfig().getLong("security.session-hours", 12);
        db.set("users." + key + ".last-ip", getIp(p));
        db.set("users." + key + ".session-until", System.currentTimeMillis() + hours * 3600_000L);
        saveDb();

        p.setGravity(true);
        if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }

        Location target = getTargetSpawn();
        if (target != null) p.teleport(target);

        p.resetTitle();
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        p.sendMessage(prefix + color(autoSession
                ? "&#00FF66Автоматический вход по сессии! &7Меню защиты и мини-сайт: &#00E5FF/2fa"
                : "&#00FF66Успешный вход! &7Меню защиты и мини-сайт: &#00E5FF/2fa"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent e) {
        if (!loggedIn.contains(e.getPlayer().getUniqueId()) && e.hasChangedPosition()) e.setTo(e.getFrom());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCmd(PlayerCommandPreprocessEvent e) {
        if (loggedIn.contains(e.getPlayer().getUniqueId())) return;
        String base = List.of(e.getMessage().split(" ")).get(0).toLowerCase();
        if (!getConfig().getStringList("security.allowed-commands").contains(base)) e.setCancelled(true);
    }

    @EventHandler public void onSwapHand(PlayerSwapHandItemsEvent e) { if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onHeldChange(PlayerItemHeldEvent e) { if (pendingCaptcha.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onInteract(PlayerInteractEvent e) { if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onInvOpen(InventoryOpenEvent e) { if (e.getPlayer() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onAttack(EntityDamageByEntityEvent e) { if (e.getDamager() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true); }
    private void startWebServer() {
        if (!getConfig().getBoolean("web.enabled", true)) return;
        int port = getConfig().getInt("web.port", 8085);
        try {
            webServer = HttpServer.create(new InetSocketAddress(port), 0);
            webServer.createContext("/", this::handleWebRequest);
            webServer.setExecutor(null);
            webServer.start();
            Bukkit.getConsoleSender().sendMessage(color("&#00FF66  [MirTimeAuth] Мини-сайт запущен на порту: &f" + port));
        } catch (IOException e) {
            Bukkit.getConsoleSender().sendMessage(color("&c  [MirTimeAuth] Ошибка мини-сайта: " + e.getMessage()));
        }
    }
    private void handleWebRequest(HttpExchange ex) throws IOException {
        String notice = "";
        String loggedUser = null;
        String savedPass = "";

        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = parseParams(body);
            String action = form.getOrDefault("action", "");
            String user = form.getOrDefault("username", "").trim().toLowerCase();
            String pass = form.getOrDefault("password", "");

            if (!db.contains("users." + user + ".hash")) {
                notice = "<div class='alert err'>✖ Игрок с таким ником не зарегистрирован!</div>";
            } else {
                String salt = db.getString("users." + user + ".salt", "");
                if (!db.getString("users." + user + ".hash", "").equals(hashPassword(pass, salt))) {
                    notice = "<div class='alert err'>✖ Неверный игровой пароль!</div>";
                } else {
                    loggedUser = user;
                    savedPass = pass;
                    if ("enable_2fa".equals(action)) {
                        String secret = db.getString("users." + user + ".totp-secret", "");
                        if (verifyTOTP(secret, form.getOrDefault("code", "").trim())) {
                            db.set("users." + user + ".totp-enabled", true);
                            saveDb();
                            notice = "<div class='alert ok'>✔ Google Authenticator (2FA) активирован!</div>";
                        } else {
                            notice = "<div class='alert err'>✖ Неверный 6-значный код из приложения!</div>";
                        }
                    } else if ("disable_2fa".equals(action)) {
                        db.set("users." + user + ".totp-enabled", false);
                        db.set("users." + user + ".totp-secret", generateRandomBase32(16));
                        saveDb();
                        notice = "<div class='alert ok'>✔ Двухфакторная защита отключена.</div>";
                    } else if ("save_email".equals(action)) {
                        String em = form.getOrDefault("email", "").trim();
                        if (em.contains("@") && em.contains(".")) {
                            db.set("users." + user + ".email", em);
                            saveDb();
                            notice = "<div class='alert ok'>✔ Почта привязана: " + escapeHtml(em) + "</div>";
                        } else {
                            notice = "<div class='alert err'>✖ Укажите корректный Email адрес!</div>";
                        }
                    } else if ("change_pass".equals(action)) {
                        String np = form.getOrDefault("new_password", "");
                        int min = getConfig().getInt("security.min-password-length", 4);
                        if (np.length() >= min && np.length() <= 32) {
                            String ns = generateRandomBase32(16);
                            db.set("users." + user + ".salt", ns);
                            db.set("users." + user + ".hash", hashPassword(np, ns));
                            saveDb();
                            savedPass = np;
                            notice = "<div class='alert ok'>✔ Игровой пароль успешно обновлён!</div>";
                        } else {
                            notice = "<div class='alert err'>✖ Длина пароля: от " + min + " до 32 символов.</div>";
                        }
                    } else if ("kick_self".equals(action)) {
                        String rName = db.getString("users." + user + ".name", user);
                        Bukkit.getScheduler().runTask(this, () -> {
                            Player t = Bukkit.getPlayerExact(rName);
                            if (t != null) t.kickPlayer(color("&#0055ff&l⚡ MIR-TIME &8» &cСессия завершена из личного кабинета!"));
                        });
                        notice = "<div class='alert ok'>✔ Команда кика отправлена на сервер.</div>";
                    }
                }
            }
        }

        // ЗАМЕНИ ЭТИ 3 СТРОКИ НА ДЕКОДИРОВАННЫЕ ИЗ BASE64:
        String mcHost = "https://mc-heads.net";
        String qrHost = "https://api.qrserver.com/v1/create-qr-code/?size=160x160&data=";
        String fontUrl = "https://fonts.googleapis.com/css2?family=Montserrat:wght@500;700;900&display=swap";
        String baseCss = "<style>@import url('" + fontUrl + "');"
                + "*{box-sizing:border-box}body{margin:0;font-family:'Montserrat',sans-serif;background:#050811;color:#eef6ff;min-height:100vh;"
                + "display:flex;justify-content:center;align-items:center;padding:20px;"
                + "background-image:radial-gradient(circle at 50% 0%,rgba(0,119,255,0.28),transparent 60%),"
                + "linear-gradient(rgba(0,229,255,0.04) 1px,transparent 1px),linear-gradient(90deg,rgba(0,229,255,0.04) 1px,transparent 1px);"
                + "background-size:100% 100%,32px 32px,32px 32px;}"
                + ".panel{background:rgba(10,17,32,0.85);backdrop-filter:blur(16px);border:1px solid rgba(0,229,255,0.35);"
                + "border-radius:24px;box-shadow:0 0 50px rgba(0,85,255,0.3),inset 0 0 20px rgba(0,229,255,0.06);padding:28px;width:100%;}"
                + ".brand{font-size:28px;font-weight:900;letter-spacing:2px;background:linear-gradient(90deg,#0066ff,#00e5ff,#00ff66);"
                + "-webkit-background-clip:text;-webkit-text-fill-color:transparent;margin:0;}"
                + "input{width:100%;padding:13px 15px;margin:8px 0;border-radius:12px;border:1px solid #1d3563;"
                + "background:rgba(6,11,22,0.9);color:#fff;font-family:inherit;font-size:13px;outline:none;transition:.25s;}"
                + "input:focus{border-color:#00e5ff;box-shadow:0 0 15px rgba(0,229,255,0.3);}"
                + ".btn{width:100%;padding:13px;border:none;border-radius:12px;font-family:inherit;font-weight:700;cursor:pointer;margin-top:8px;"
                + "font-size:13px;letter-spacing:.5px;text-transform:uppercase;transition:.25s;background:linear-gradient(135deg,#0055ff,#00d4ff);color:#fff;}"
                + ".btn:hover{transform:translateY(-2px);box-shadow:0 8px 25px rgba(0,229,255,0.4);}"
                + ".btn.green{background:linear-gradient(135deg,#00b84d,#00ff66);color:#031409;}"
                + ".btn.red{background:linear-gradient(135deg,#c0392b,#ff4757);color:#fff;}"
                + ".alert{padding:12px 14px;border-radius:12px;margin-bottom:14px;font-size:13px;font-weight:700;}"
                + ".alert.ok{background:rgba(0,255,102,0.12);border:1px solid #00ff66;color:#00ff66;}"
                + ".alert.err{background:rgba(255,71,87,0.12);border:1px solid #ff4757;color:#ff6b81;}</style>";

        if (loggedUser == null) {
            String loginHtml = "<!DOCTYPE html><html lang='ru'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                    + "<title>MIR-TIME | Авторизация</title>" + baseCss + "</head><body>"
                    + "<div class='panel' style='max-width:410px;text-align:center;'>"
                    + "<div style='display:inline-block;padding:4px;border-radius:18px;background:linear-gradient(135deg,#0055ff,#00ff66);margin-bottom:12px;'>"
                    + "<img id='av' src='" + mcHost + "/avatar/Steve/72' width='72' height='72' style='display:block;border-radius:14px;background:#080e1c;'></div>"
                    + "<h1 class='brand'>⚡ MIR-TIME</h1>"
                    + "<p style='color:#8aa4c8;font-size:12px;margin:6px 0 20px;'>Единый центр безопасности аккаунта</p>"
                    + notice
                    + "<form method='post'><input type='hidden' name='action' value='login'>"
                    + "<input type='text' name='username' placeholder='Введите ваш игровой ник' oninput=\"document.getElementById('av').src='" + mcHost + "/avatar/'+(this.value||'Steve')+'/72'\" required>"
                    + "<input type='password' name='password' placeholder='Введите ваш игровой пароль' required>"
                    + "<button class='btn green' type='submit'>Войти в систему ➔</button></form>"
                    + "<div style='margin-top:18px;padding-top:14px;border-top:1px solid rgba(255,255,255,0.07);font-size:11px;color:#637fa3;'>"
                    + "IP сервера: <b style='color:#00e5ff'>play.mir-time.ru</b> &bull; Paper 1.21+</div>"
                    + "</div></body></html>";
            sendHtml(ex, loginHtml);
            return;
        }
        String realName = db.getString("users." + loggedUser + ".name", loggedUser);
        String lastIp = db.getString("users." + loggedUser + ".last-ip", "Неизвестно");
        String email = db.getString("users." + loggedUser + ".email", "Не привязана");
        boolean totpEnabled = db.getBoolean("users." + loggedUser + ".totp-enabled", false);
        String secret = db.getString("users." + loggedUser + ".totp-secret", "");
        if (secret.isEmpty()) {
            secret = generateRandomBase32(16);
            db.set("users." + loggedUser + ".totp-secret", secret);
            saveDb();
        }

        String hiddenAuth = "<input type='hidden' name='username' value='" + escapeHtml(loggedUser) + "'>"
                + "<input type='hidden' name='password' value='" + escapeHtml(savedPass) + "'>";

        String otpUri = "otpauth://totp/MIR-TIME:" + URLEncoder.encode(realName, StandardCharsets.UTF_8) + "?secret=" + secret + "&issuer=MIR-TIME";
        String qrUrl = qrHost + URLEncoder.encode(otpUri, StandardCharsets.UTF_8);

        String twoFaCard = totpEnabled
                ? "<div style='background:rgba(0,255,102,0.1);border:1px solid rgba(0,255,102,0.3);padding:10px 14px;border-radius:10px;margin-bottom:10px;color:#00ff66;font-size:13px;font-weight:700;'>"
                + "✔ Двухфакторная защита (2FA) активна</div>"
                + "<form method='post'>" + hiddenAuth + "<input type='hidden' name='action' value='disable_2fa'>"
                + "<button class='btn red' type='submit'>Отключить Google Authenticator</button></form>"
                : "<p style='font-size:12px;color:#9ab2d4;margin:0 0 10px;'>Отсканируйте QR-код телефоном или введите ключ: "
                + "<code style='background:#050811;padding:4px 8px;border-radius:6px;color:#00ff66;font-weight:bold;'>" + secret + "</code></p>"
                + "<img src='" + qrUrl + "' style='display:block;margin:12px auto;border-radius:14px;border:5px solid #fff;box-shadow:0 0 20px rgba(0,229,255,0.25);'>"
                + "<form method='post'>" + hiddenAuth + "<input type='hidden' name='action' value='enable_2fa'>"
                + "<input type='text' name='code' placeholder='Введите 6 цифр из приложения' maxlength='6' required>"
                + "<button class='btn green' type='submit'>Подтвердить и включить 2FA ✔</button></form>";

        String dashHtml = "<!DOCTYPE html><html lang='ru'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>MIR-TIME | Кабинет " + escapeHtml(realName) + "</title>" + baseCss
                + "<style>.card{background:rgba(15,25,48,0.75);border:1px solid rgba(0,119,255,0.35);border-radius:16px;padding:18px;margin-top:14px;}"
                + ".card h3{margin:0 0 10px;font-size:14px;text-transform:uppercase;letter-spacing:.5px;color:#00e5ff;}"
                + ".badge{display:inline-block;padding:4px 10px;border-radius:8px;font-size:11px;font-weight:700;background:rgba(0,229,255,0.12);color:#00e5ff;margin-top:4px;}</style></head><body>"
                + "<div class='panel' style='max-width:620px;'>"
                + "<div style='display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid rgba(255,255,255,0.08);padding-bottom:16px;gap:12px;'>"
                + "<div style='display:flex;align-items:center;gap:16px;'>"
                + "<img src='" + mcHost + "/body/" + escapeHtml(realName) + "/90' style='height:90px;filter:drop-shadow(0 0 12px rgba(0,229,255,0.4));'>"
                + "<div><h2 style='margin:0;font-size:22px;color:#fff;'>⚡ " + escapeHtml(realName) + "</h2>"
                + "<div class='badge'>IP: " + escapeHtml(lastIp) + "</div> "
                + "<div class='badge' style='color:#00ff66;background:rgba(0,255,102,0.12);'>Почта: " + escapeHtml(email) + "</div></div></div>"
                + "<a href='/' style='padding:8px 14px;border-radius:10px;background:rgba(255,71,87,0.15);border:1px solid #ff4757;color:#ff6b81;text-decoration:none;font-size:12px;font-weight:700;'>Выйти</a></div>"
                + "<div style='margin-top:14px;'>" + notice + "</div>"
                + "<div class='card'><h3>📱 Двухфакторная защита (Google Authenticator)</h3>" + twoFaCard + "</div>"
                + "<div class='card'><h3>🔑 Смена игрового пароля</h3>"
                + "<form method='post'>" + hiddenAuth + "<input type='hidden' name='action' value='change_pass'>"
                + "<input type='password' name='new_password' placeholder='Придумайте новый пароль' required>"
                + "<button class='btn' type='submit'>Сохранить новый пароль</button></form></div>"
                + "<div class='card'><h3>📧 Резервная почта</h3>"
                + "<form method='post'>" + hiddenAuth + "<input type='hidden' name='action' value='save_email'>"
                + "<input type='email' name='email' placeholder='Введите ваш Email' required>"
                + "<button class='btn' type='submit'>Привязать почту</button></form></div>"
                + "<div class='card'><h3>🛡 Экстренное управление</h3>"
                + "<form method='post'>" + hiddenAuth + "<input type='hidden' name='action' value='kick_self'>"
                + "<button class='btn red' type='submit'>Кикнуть мой аккаунт с сервера</button></form></div>"
                + "</div></body></html>";
        sendHtml(ex, dashHtml);
    }

    private void sendSmtpMail(String to, String subject, String body) {
        try {
            String host = getConfig().getString("email.smtp-host", "smtp.yandex.ru");
            int port = getConfig().getInt("email.smtp-port", 465);
            String user = getConfig().getString("email.username", "");
            String pass = getConfig().getString("email.password", "");
            String codeOnly = body.replaceAll("\\D+", "");
            String html = "<div style='background:#070b14;padding:30px;font-family:sans-serif;color:#e6f1ff;text-align:center;'>"
                    + "<div style='max-width:420px;margin:auto;background:#0d1526;border:1px solid #0055ff;border-radius:14px;padding:24px;'>"
                    + "<h2 style='color:#00e5ff;margin:0 0 10px 0;'>⚡ MIR-TIME</h2>"
                    + "<p style='color:#9ab2d4;font-size:14px;'>" + escapeHtml(body) + "</p>"
                    + "<div style='background:#111c33;border:1px solid #00ff66;border-radius:10px;padding:14px;margin:16px 0;font-size:28px;font-weight:bold;letter-spacing:6px;color:#00ff66;'>"
                    + codeOnly + "</div>"
                    + "<p style='color:#5c7699;font-size:12px;margin:0;'>Если вы не запрашивали код, просто проигнорируйте это письмо.</p>"
                    + "</div></div>";

            try (SSLSocket sock = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
                 BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream()), true)) {
                in.readLine();
                out.println("EHLO mirtime"); while (!in.readLine().startsWith("250 ")) {}
                out.println("AUTH LOGIN"); in.readLine();
                out.println(Base64.getEncoder().encodeToString(user.getBytes())); in.readLine();
                out.println(Base64.getEncoder().encodeToString(pass.getBytes())); in.readLine();
                out.println("MAIL FROM:<" + user + ">"); in.readLine();
                out.println("RCPT TO:<" + to + ">"); in.readLine();
                out.println("DATA"); in.readLine();
                out.println("From: MIR-TIME <" + user + ">\r\nTo: <" + to + ">\r\nSubject: =?UTF-8?B?"
                        + Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8))
                        + "?=\r\nMIME-Version: 1.0\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html + "\r\n.");
                in.readLine();
                out.println("QUIT");
            }
        } catch (Exception ignored) {}
    }
        String dashHtml = "<!DOCTYPE html><html lang='ru'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>MIR-TIME | Кабинет " + escapeHtml(realName) + "</title>" + baseCss
                + "<style>.card{background:rgba(15,25,48,0.75);border:1px solid rgba(0,119,255,0.35);border-radius:16px;padding:18px;margin-top:14px;}"
                + ".card h3{margin:0 0 10px;font-size:14px;text-transform:uppercase;letter-spacing:.5px;color:#00e5ff;}"
                + ".badge{display:inline-block;padding:4px 10px;border-radius:8px;font-size:11px;font-weight:700;background:rgba(0,229,255,0.12);color:#00e5ff;margin-top:4px;}</style></head><body>"
                + "<div class='panel' style='max-width:620px;'>"
                + "<div style='display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid rgba(255,255,255,0.08);padding-bottom:16px;gap:12px;'>"
                + "<div style='display:flex;align-items:center;gap:16px;'>"
                + "<img src='https://mc-heads.net" + escapeHtml(realName) + "/90' style='height:90px;filter:drop-shadow(0 0 12px rgba(0,229,255,0.4));'>"
                + "<div><h2 style='margin:0;font-size:22px;color:#fff;'>⚡ " + escapeHtml(realName) + "</h2>"
                + "<div class='badge'>IP: " + escapeHtml(lastIp) + "</div> "
                + "<div class='badge' style='color:#00ff66;background:rgba(0,255,102,0.12);'>Почта: " + escapeHtml(email) + "</div></div></div>"
                + "<a href='/' style='padding:8px 14px;border-radius:10px;background:rgba(255,71,87,0.15);border:1px solid #ff4757;color:#ff6b81;text-decoration:none;font-size:12px;font-weight:700;'>Выйти</a></div>"
                + "<div style='margin-top:14px;'>" + notice + "</div>"
                + "<div class='card'><h3>📱 Двухфакторная защита (Google Authenticator)</h3>" + twoFaCard + "</div>"
                + "<div class='card'><h3>🔑 Смена игрового пароля</h3>"
                + "<form method='post'>" + hiddenAuth + "<input type='hidden' name='action' value='change_pass'>"
                + "<input type='password' name='new_password' placeholder='Придумайте новый пароль' required>"
                + "<button class='btn' type='submit'>Сохранить новый пароль</button></form></div>"
                + "<div class='card'><h3>📧 Резервная почта</h3>"
                + "<form method='post'>" + hiddenAuth + "<input type='hidden' name='action' value='save_email'>"
                + "<input type='email' name='email' placeholder='Введите ваш Email' required>"
                + "<button class='btn' type='submit'>Привязать почту</button></form></div>"
                + "<div class='card'><h3>🛡 Экстренное управление</h3>"
                + "<form method='post'>" + hiddenAuth + "<input type='hidden' name='action' value='kick_self'>"
                + "<button class='btn red' type='submit'>Кикнуть мой аккаунт с сервера</button></form></div>"
                + "</div></body></html>";
        sendHtml(ex, dashHtml);
    }

    private void sendHtml(HttpExchange ex, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private Map<String, String> parseParams(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) return map;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return map;
    }

    private String escapeHtml(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private String getPublicIp() { try { return InetAddress.getLocalHost().getHostAddress(); } catch (Exception e) { return "127.0.0.1"; } }

    private ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            if (lore != null) { List<String> list = new ArrayList<>(); for (String s : lore) list.add(color(s)); meta.setLore(list); }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            is.setItemMeta(meta);
        }
        return is;
    }

    private String hashPassword(String pass, String salt) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((salt + pass).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private String generateRandomBase32(int len) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        return sb.toString();
    }

    private boolean verifyTOTP(String base32Secret, String codeStr) {
        if (codeStr == null || !codeStr.matches("\\d{6}")) return false;
        int code = Integer.parseInt(codeStr);
        byte[] key = decodeBase32(base32Secret);
        long window = System.currentTimeMillis() / 30000L;
        for (int i = -1; i <= 1; i++) { if (generateTOTP(key, window + i) == code) return true; }
        return false;
    }

    private int generateTOTP(byte[] key, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0xF;
            return (((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16) | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF)) % 1_000_000;
        } catch (Exception e) { return -1; }
    }

    private byte[] decodeBase32(String s) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        s = s.toUpperCase().replaceAll("[^A-Z2-7]", "");
        byte[] out = new byte[s.length() * 5 / 8];
        int buffer = 0, bitsLeft = 0, index = 0;
        for (char c : s.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bitsLeft += 5;
            if (bitsLeft >= 8) { out[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF); bitsLeft -= 8; }
        }
        return out;
    }

    public static String color(String text) {
        if (text == null) return "";
        Matcher matcher = Pattern.compile("&#([A-Fa-f0-9]{6})").matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char c : matcher.group(1).toCharArray()) rep.append('§').append(c);
            matcher.appendReplacement(buffer, rep.toString());
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }
}
