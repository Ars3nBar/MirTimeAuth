package ru.mirtime.auth;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
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

    private final Set<UUID> loggedIn = ConcurrentHashMap.newKeySet();
    private final Set<UUID> awaiting2FA = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> pendingCaptcha = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack> savedMainHand = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> savedSlot = new ConcurrentHashMap<>();
    private final Map<UUID, Long> joinTime = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        usersFile = new File(getDataFolder(), "users.yml");
        if (!usersFile.exists()) {
            try { usersFile.createNewFile(); } catch (IOException ignored) {}
        }
        db = YamlConfiguration.loadConfiguration(usersFile);

        setupAuthWorld();

        for (String cmd : List.of("register", "login", "changepassword", "2fa", "logout", "authspawn")) {
            if (getCommand(cmd) != null) getCommand(cmd).setExecutor(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);

        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                long maxMs = getConfig().getLong("security.login-timeout-seconds", 90) * 1000L;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (loggedIn.contains(p.getUniqueId())) continue;
                    long joined = joinTime.getOrDefault(p.getUniqueId(), now);
                    if (now - joined > maxMs) {
                        restoreHandItem(p);
                        p.kickPlayer(color("&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» &cВремя на авторизацию вышло!"));
                        continue;
                    }
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
        }.runTaskTimer(this, 20L, 20L);

        Bukkit.getConsoleSender().sendMessage(color("&#00E5FF  MirTimeAuth v1.0.0 (VoidWorld + MapCaptcha + 2FA) успешно запущен!"));
    }
    private void setupAuthWorld() {
        String wName = getConfig().getString("security.auth-world", "auth_void");
        authWorld = Bukkit.getWorld(wName);
        if (authWorld == null) {
            WorldCreator wc = new WorldCreator(wName);
            wc.environment(World.Environment.THE_END);
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
        }
    }

    private void sendToVoid(Player p) {
        if (authWorld == null) return;
        Location voidLoc = new Location(authWorld, 0.5, 120.0, 0.5, 0f, 0f);
        p.teleport(voidLoc);
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
        if (getConfig().getBoolean("security.captcha-enabled", true)) {
            giveCaptchaMap(p, prefix);
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

        int slot = p.getInventory().getHeldItemSlot();
        savedSlot.put(p.getUniqueId(), slot);
        savedMainHand.put(p.getUniqueId(), p.getInventory().getItem(slot));

        MapView view = Bukkit.createMap(p.getWorld());
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
        p.sendMessage(prefix + color("&#00E5FFПроверка на бота! &fПосмотрите на карту в руке и напишите &e4 цифры &fпросто в чат."));
    }

    private void restoreHandItem(Player p) {
        Integer slot = savedSlot.remove(p.getUniqueId());
        ItemStack prev = savedMainHand.remove(p.getUniqueId());
        if (slot != null) {
            p.getInventory().setItem(slot, prev);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
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

        if (cName.equals("register")) {
            if (loggedIn.contains(p.getUniqueId())) { p.sendMessage(prefix + color("&cВы уже авторизованы!")); return true; }
            if (db.contains("users." + key + ".hash")) { p.sendMessage(prefix + color("&cАккаунт уже зарегистрирован! Введите &#00FF66/login <пароль>")); return true; }
            if (args.length < 2) { p.sendMessage(prefix + color("&fИспользование: &#00FF66/reg <пароль> <повтор_пароля>")); return true; }
            String pass1 = List.of(args).get(0);
            String pass2 = List.of(args).get(1);
            if (!pass1.equals(pass2)) { p.sendMessage(prefix + color("&cПароли не совпадают!")); return true; }
            int min = getConfig().getInt("security.min-password-length", 6);
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
            p.sendMessage(prefix + color("&#00FF66Регистрация завершена! &7Привязать Google Authenticator: &#00E5FF/2fa setup"));
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

        if (cName.equals("2fa")) {
            if (awaiting2FA.contains(p.getUniqueId())) {
                if (args.length < 1) {
                    p.sendMessage(prefix + color("&fПросто напишите &e6 цифр &fиз Google Authenticator прямо в чат!"));
                    return true;
                }
                String code = List.of(args).get(0);
                String secret = db.getString("users." + key + ".totp-secret", "");
                if (!verifyTOTP(secret, code)) {
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                    p.sendMessage(prefix + color("&cНеверный или устаревший код Google Authenticator!"));
                    return true;
                }
                awaiting2FA.remove(p.getUniqueId());
                completeLogin(p, key, prefix, false);
                return true;
            }

            if (!loggedIn.contains(p.getUniqueId())) {
                p.sendMessage(prefix + color("&cСначала войдите в аккаунт!"));
                return true;
            }

            if (args.length == 0) {
                boolean enabled = db.getBoolean("users." + key + ".totp-enabled", false);
                p.sendMessage(prefix + color("&fСтатус Google Authenticator: " + (enabled ? "&#00FF66ВКЛЮЧЁН" : "&cВЫКЛЮЧЕН")));
                p.sendMessage(prefix + color("&7Команды: &#00E5FF/2fa setup &8| &#00FF66/2fa confirm <код> &8| &c/2fa off <пароль>"));
                return true;
            }

            String sub = List.of(args).get(0).toLowerCase();
            if (sub.equals("setup")) {
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
                TextComponent qrLink = new TextComponent(color(" &f2. Или нажмите, чтобы открыть QR-код: &#00E5FF&l[ОТКРЫТЬ QR]"));
                qrLink.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, qrUrl));
                p.spigot().sendMessage(qrLink);
                p.sendMessage(color(" &f3. Подтвердите привязку: &#00FF66/2fa confirm <6_цифр>"));
                p.sendMessage(color("&#0055ff&l&m                                                "));
                return true;
            }

            if (sub.equals("confirm") && args.length >= 2) {
                String secret = db.getString("users." + key + ".totp-secret", "");
                if (secret.isEmpty() || !verifyTOTP(secret, List.of(args).get(1))) {
                    p.sendMessage(prefix + color("&cНеверный код! Введите сначала &#00E5FF/2fa setup"));
                    return true;
                }
                db.set("users." + key + ".totp-enabled", true);
                saveDb();
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
                p.sendMessage(prefix + color("&#00FF66Google Authenticator успешно привязан! Теперь при входе достаточно просто написать 6 цифр в чат."));
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
            return true;
        }
        if (!loggedIn.contains(p.getUniqueId())) {
            p.sendMessage(prefix + color("&cСначала пройдите авторизацию!"));
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
            p.sendMessage(prefix + color("&#00FF66Точка спавна после авторизации успешно установлена!"));
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
        if (target != null) {
            p.teleport(target);
        }

        p.resetTitle();
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        if (autoSession) {
            p.sendMessage(prefix + color("&#00FF66Автоматический вход по активной сессии! &7Защита 2FA: &#00E5FF/2fa"));
        } else {
            p.sendMessage(prefix + color("&#00FF66Успешный вход в аккаунт! &7Защита 2FA: &#00E5FF/2fa"));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent e) {
        if (!loggedIn.contains(e.getPlayer().getUniqueId()) && e.hasChangedPosition()) {
            e.setTo(e.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCmd(PlayerCommandPreprocessEvent e) {
        if (loggedIn.contains(e.getPlayer().getUniqueId())) return;
        String base = List.of(e.getMessage().split(" ")).get(0).toLowerCase();
        if (!getConfig().getStringList("security.allowed-commands").contains(base)) {
            e.setCancelled(true);
        }
    }

    @EventHandler public void onSwapHand(PlayerSwapHandItemsEvent e) { if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onHeldChange(PlayerItemHeldEvent e) { if (pendingCaptcha.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onInteract(PlayerInteractEvent e) { if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onInvClick(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onInvOpen(InventoryOpenEvent e) { if (e.getPlayer() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onAttack(EntityDamageByEntityEvent e) { if (e.getDamager() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true); }

    private String hashPassword(String pass, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((salt + pass).getBytes(StandardCharsets.UTF_8));
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
        for (int i = -1; i <= 1; i++) {
            if (generateTOTP(key, window + i) == code) return true;
        }
        return false;
    }

    private int generateTOTP(byte[] key, long counter) {
        try {
            byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0xF;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)

                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            return binary % 1_000_000;
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
            if (bitsLeft >= 8) {
                out[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out;
    }

    public static String color(String text) {
        if (text == null) return "";
        Pattern pattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
        Matcher matcher = pattern.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder rep = new StringBuilder("§x");
            for (char c : hex.toCharArray()) rep.append('§').append(c);
            matcher.appendReplacement(buffer, rep.toString());
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }
}
