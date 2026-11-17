package ru.mirtime.auth;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
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

    private MailService mailService;
    private CaptchaManager captchaManager;
    private LobbyClock lobbyClock;
    private Menu2FA menu2FA;
    private WebCabinet webCabinet;

    private final Set<UUID> loggedIn = ConcurrentHashMap.newKeySet();
    private final Set<UUID> awaiting2FA = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> joinTime = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> authBars = new ConcurrentHashMap<>();
    private final Map<String, String> emailVerifyCodes = new ConcurrentHashMap<>();
    private final Map<String, String> emailPendingAddr = new ConcurrentHashMap<>();
    private final Map<String, String> recoveryCodes = new ConcurrentHashMap<>();

    public YamlConfiguration getDb() { return db; }
    public MailService getMail() { return mailService; }
    public boolean isLoggedIn(UUID id) { return loggedIn.contains(id); }
    public String getPrefix() { return color(getConfig().getString("prefix", "&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» ")); }
    @Override
    public void onEnable() {
        saveDefaultConfig();
        usersFile = new File(getDataFolder(), "users.yml");
        if (!usersFile.exists()) {
            try { usersFile.createNewFile(); } catch (IOException ignored) {}
        }
        db = YamlConfiguration.loadConfiguration(usersFile);

        mailService = new MailService(this);
        captchaManager = new CaptchaManager();
        lobbyClock = new LobbyClock(this);
        menu2FA = new Menu2FA(this);
        webCabinet = new WebCabinet(this);

        setupAuthWorld();
        webCabinet.start();

        for (String cmd : List.of("register", "login", "changepassword", "2fa", "recovery", "logout", "authspawn")) {
            if (getCommand(cmd) != null) getCommand(cmd).setExecutor(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(lobbyClock, this);
        Bukkit.getPluginManager().registerEvents(menu2FA, this);

        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                long maxMs = getConfig().getLong("security.login-timeout-seconds", 90) * 1000L;

                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (loggedIn.contains(p.getUniqueId())) { removeAuthBar(p); continue; }
                    long elapsed = now - joinTime.getOrDefault(p.getUniqueId(), now);
                    if (elapsed > maxMs) {
                        removeAuthBar(p);
                        captchaManager.restoreHand(p);
                        p.kickPlayer(color("&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» &cВремя на авторизацию вышло!"));
                        continue;
                    }
                    long leftSec = Math.max(1, (maxMs - elapsed) / 1000L);
                    updateAuthBar(p, leftSec, Math.max(0.0, Math.min(1.0, (double) (maxMs - elapsed) / maxMs)));

                    String key = p.getName().toLowerCase();
                    if (captchaManager.isPending(p.getUniqueId())) {
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
    }

    @Override
    public void onDisable() {
        if (webCabinet != null) webCabinet.stop();
        for (BossBar bar : authBars.values()) bar.removeAll();
        authBars.clear();
    }

    private void updateAuthBar(Player p, long leftSec, double progress) {
        BossBar bar = authBars.computeIfAbsent(p.getUniqueId(), id -> {
            BossBar b = Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SOLID);
            b.addPlayer(p);
            return b;
        });
        String stage = captchaManager.isPending(p.getUniqueId()) ? "Пройдите капчу с карты"
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
            for (Entity ent : authWorld.getEntities()) if (!(ent instanceof Player)) ent.remove();
        }
    }

    private void sendToVoid(Player p) {
        if (authWorld == null) return;
        p.teleport(new Location(authWorld, 0.5, 120.0, 0.5, 0f, 0f));
        p.setGravity(false);
        p.setAllowFlight(true);
        p.setFlying(true);
    }

    public Location getTargetSpawn() {
        if (db.contains("spawn.world")) {
            World w = Bukkit.getWorld(db.getString("spawn.world", "world"));
            if (w != null) return new Location(w, db.getDouble("spawn.x"), db.getDouble("spawn.y"), db.getDouble("spawn.z"), (float) db.getDouble("spawn.yaw"), (float) db.getDouble("spawn.pitch"));
        }
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    public synchronized void saveDb() {
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

        loggedIn.remove(id);
        awaiting2FA.remove(id);
        captchaManager.clear(id);
        joinTime.put(id, System.currentTimeMillis());

        if (db.contains("users." + key + ".hash")) {
            String lastIp = db.getString("users." + key + ".last-ip", "");
            long sessionUntil = db.getLong("users." + key + ".session-until", 0L);
            if (getIp(p).equals(lastIp) && System.currentTimeMillis() < sessionUntil) {
                completeLogin(p, key, true);
                return;
            }
        }

        sendToVoid(p);
        if (getConfig().getBoolean("security.captcha-enabled", true) && !p.isOp()) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline() && !loggedIn.contains(p.getUniqueId())) captchaManager.giveMap(p, getPrefix());
            }, 5L);
        } else {
            promptAuth(p, key);
        }
    }

    private void promptAuth(Player p, String key) {
        p.sendMessage(getPrefix() + color(db.contains("users." + key + ".hash")
                ? "&fДобро пожаловать! Введите &#00FF66/login <пароль>"
                : "&fДобро пожаловать на &bMIR-TIME&f! Зарегистрируйтесь: &#00FF66/reg <пароль> <повтор>"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        removeAuthBar(p);
        captchaManager.restoreHand(p);
        p.setGravity(true);
        if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) { p.setFlying(false); p.setAllowFlight(false); }
        loggedIn.remove(id); awaiting2FA.remove(id); captchaManager.clear(id); joinTime.remove(id);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (loggedIn.contains(id)) return;
        e.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();

        if (captchaManager.isPending(id)) {
            String expected = captchaManager.getCode(id);
            Bukkit.getScheduler().runTask(this, () -> {
                if (!p.isOnline()) return;
                if (msg.equals(expected)) {
                    captchaManager.restoreHand(p);
                    captchaManager.clear(id);
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.3f);
                    p.sendMessage(getPrefix() + color("&#00FF66Капча успешно пройдена!"));
                    promptAuth(p, p.getName().toLowerCase());
                } else {
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                    p.sendMessage(getPrefix() + color("&cНеверный код с карты! Напишите &e4 цифры &cв чат."));
                }
            });
            return;
        }

        if (awaiting2FA.contains(id)) {
            String key = p.getName().toLowerCase();
            String secret = db.getString("users." + key + ".totp-secret", "");
            Bukkit.getScheduler().runTask(this, () -> {
                if (!p.isOnline()) return;
                if (mailService.verifyTOTP(secret, msg)) {
                    awaiting2FA.remove(id);
                    completeLogin(p, key, false);
                } else {
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                    p.sendMessage(getPrefix() + color("&cНеверный код! Напишите &e6 цифр &cиз Google Authenticator в чат."));
                }
            });
        }
    }
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        String prefix = getPrefix();
        String cName = cmd.getName().toLowerCase();
        String key = p.getName().toLowerCase();

        if (captchaManager.isPending(p.getUniqueId())) {
            p.sendMessage(prefix + color("&cСначала напишите &e4 цифры &cс карты в руке прямо в чат!"));
            return true;
        }

        if (cName.equals("recovery")) {
            if (!getConfig().getBoolean("email.enabled", false)) { p.sendMessage(prefix + color("&cВосстановление по почте отключено.")); return true; }
            String email = db.getString("users." + key + ".email", "");
            if (email.isEmpty()) { p.sendMessage(prefix + color("&cК вашему аккаунту не привязана почта!")); return true; }
            if (args.length == 0) {
                String code = String.valueOf(100000 + new SecureRandom().nextInt(900000));
                recoveryCodes.put(key, code);
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> mailService.sendHtmlCode(email, "MIR-TIME: Сброс пароля", "Ваш код для восстановления доступа к аккаунту:", code));
                p.sendMessage(prefix + color("&#00FF66Код сброса отправлен на почту! &fВведите: &#00E5FF/recovery <код> <новый_пароль>"));
                return true;
            }
            if (args.length >= 2) {
                String saved = recoveryCodes.get(key);
                if (saved == null || !saved.equals(List.of(args).get(0))) { p.sendMessage(prefix + color("&cНеверный код восстановления!")); return true; }
                String newPass = List.of(args).get(1);
                String salt = mailService.generateRandomBase32(16);
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
            if (!pass1.equals(List.of(args).get(1))) { p.sendMessage(prefix + color("&cПароли не совпадают!")); return true; }
            int min = getConfig().getInt("security.min-password-length", 4);
            int max = getConfig().getInt("security.max-password-length", 32);
            if (pass1.length() < min || pass1.length() > max) { p.sendMessage(prefix + color("&cДлина пароля: от " + min + " до " + max + " символов!")); return true; }

            String salt = mailService.generateRandomBase32(16);
            db.set("users." + key + ".name", p.getName());
            db.set("users." + key + ".salt", salt);
            db.set("users." + key + ".hash", hashPassword(pass1, salt));
            completeLogin(p, key, false);
            return true;
        }

        if (cName.equals("login")) {
            if (loggedIn.contains(p.getUniqueId())) { p.sendMessage(prefix + color("&cВы уже вошли в аккаунт!")); return true; }
            if (!db.contains("users." + key + ".hash")) { p.sendMessage(prefix + color("&cВы не зарегистрированы! Введите &#00FF66/reg <пароль> <пароль>")); return true; }
            if (args.length < 1) { p.sendMessage(prefix + color("&fИспользование: &#00FF66/login <пароль>")); return true; }

            String salt = db.getString("users." + key + ".salt", "");
            if (!db.getString("users." + key + ".hash", "").equals(hashPassword(List.of(args).get(0), salt))) {
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                p.sendMessage(prefix + color("&cНеверный пароль!"));
                return true;
            }
            if (db.getBoolean("users." + key + ".totp-enabled", false) && !db.getString("users." + key + ".totp-secret", "").isEmpty()) {
                awaiting2FA.add(p.getUniqueId());
                p.sendMessage(prefix + color("&#00E5FFПароль верный! &fНапишите &e6-значный код &fиз Google Authenticator прямо в чат."));
                return true;
            }
            completeLogin(p, key, false);
            return true;
        }

        if (!loggedIn.contains(p.getUniqueId())) { p.sendMessage(prefix + color("&cСначала пройдите авторизацию!")); return true; }
        if (cName.equals("2fa")) {
            if (args.length == 0) { menu2FA.open(p); return true; }
            String sub = List.of(args).get(0).toLowerCase();
            if (sub.equals("setup")) { menu2FA.sendGoogleSetup(p); return true; }
            if (sub.equals("site") || sub.equals("web")) { menu2FA.sendWebUrl(p); return true; }
            if (sub.equals("confirm") && args.length >= 2) {
                String secret = db.getString("users." + key + ".totp-secret", "");
                if (secret.isEmpty() || !mailService.verifyTOTP(secret, List.of(args).get(1))) { p.sendMessage(prefix + color("&cНеверный код!")); return true; }
                db.set("users." + key + ".totp-enabled", true);
                saveDb();
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
                p.sendMessage(prefix + color("&#00FF66Google Authenticator успешно привязан!"));
                return true;
            }
            if (sub.equals("off") && args.length >= 2) {
                if (!checkPass(key, List.of(args).get(1))) { p.sendMessage(prefix + color("&cНеверный пароль!")); return true; }
                db.set("users." + key + ".totp-enabled", false);
                db.set("users." + key + ".totp-secret", null);
                saveDb();
                p.sendMessage(prefix + color("&eДвухфакторная защита (2FA) отключена."));
                return true;
            }
            if (sub.equals("email") && args.length >= 2) {
                String addr = List.of(args).get(1).trim();
                if (!addr.contains("@") || !addr.contains(".")) { p.sendMessage(prefix + color("&cУкажите корректный Email!")); return true; }
                if (!getConfig().getBoolean("email.enabled", false)) {
                    db.set("users." + key + ".email", addr); saveDb();
                    p.sendMessage(prefix + color("&#00FF66Почта сохранена: &f" + addr)); return true;
                }
                String code = String.valueOf(100000 + new SecureRandom().nextInt(900000));
                emailVerifyCodes.put(key, code);
                emailPendingAddr.put(key, addr);
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> mailService.sendHtmlCode(addr, "MIR-TIME: Подтверждение почты", "Используйте этот код для привязки почты к аккаунту <b>" + p.getName() + "</b>:", code));
                p.sendMessage(prefix + color("&#00E5FFКод отправлен на &f" + addr + "&#00E5FF! Введите: &#00FF66/2fa emailconfirm <код>"));
                return true;
            }
            if (sub.equals("emailconfirm") && args.length >= 2) {
                String saved = emailVerifyCodes.get(key);
                if (saved == null || !saved.equals(List.of(args).get(1))) { p.sendMessage(prefix + color("&cНеверный код!")); return true; }
                db.set("users." + key + ".email", emailPendingAddr.remove(key));
                emailVerifyCodes.remove(key);
                saveDb();
                p.sendMessage(prefix + color("&#00FF66Почта успешно подтверждена и привязана!"));
                return true;
            }
            if (sub.equals("emailoff") && args.length >= 2) {
                if (!checkPass(key, List.of(args).get(1))) { p.sendMessage(prefix + color("&cНеверный пароль аккаунта!")); return true; }
                db.set("users." + key + ".email", null);
                saveDb();
                p.sendMessage(prefix + color("&eПочта успешно отвязана от вашего аккаунта."));
                return true;
            }
            return true;
        }

        if (cName.equals("authspawn") && p.hasPermission("mirtime.auth.admin")) {
            Location l = p.getLocation();
            db.set("spawn.world", l.getWorld().getName()); db.set("spawn.x", l.getX()); db.set("spawn.y", l.getY());
            db.set("spawn.z", l.getZ()); db.set("spawn.yaw", l.getYaw()); db.set("spawn.pitch", l.getPitch());
            saveDb(); p.sendMessage(prefix + color("&#00FF66Точка спавна установлена!")); return true;
        }

        if (cName.equals("changepassword") && args.length >= 2) {
            if (!checkPass(key, List.of(args).get(0))) { p.sendMessage(prefix + color("&cСтарый пароль неверен!")); return true; }
            String ns = mailService.generateRandomBase32(16);
            db.set("users." + key + ".salt", ns); db.set("users." + key + ".hash", hashPassword(List.of(args).get(1), ns));
            saveDb(); p.sendMessage(prefix + color("&#00FF66Пароль изменён!")); return true;
        }

        if (cName.equals("logout")) {
            db.set("users." + key + ".session-until", 0L); saveDb();
            loggedIn.remove(p.getUniqueId()); joinTime.put(p.getUniqueId(), System.currentTimeMillis());
            sendToVoid(p); p.sendMessage(prefix + color("&eВы вышли из сессии.")); return true;
        }
        return true;
    }

    private boolean checkPass(String key, String rawPass) {
        String salt = db.getString("users." + key + ".salt", "");
        return db.getString("users." + key + ".hash", "").equals(hashPassword(rawPass, salt));
    }

    private void completeLogin(Player p, String key, boolean autoSession) {
        loggedIn.add(p.getUniqueId());
        removeAuthBar(p);
        long hours = getConfig().getLong("security.session-hours", 12);
        db.set("users." + key + ".last-ip", getIp(p));
        db.set("users." + key + ".session-until", System.currentTimeMillis() + hours * 3600_000L);
        saveDb();

        p.setGravity(true);
        if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) { p.setFlying(false); p.setAllowFlight(false); }
        Location target = getTargetSpawn();
        if (target != null) p.teleport(target);
        lobbyClock.giveClock(p);

        p.resetTitle();
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        p.sendMessage(getPrefix() + color(autoSession
                ? "&#00FF66Автоматический вход по сессии! &7Меню защиты: &#00E5FF/2fa"
                : "&#00FF66Успешный вход! &7Меню защиты: &#00E5FF/2fa"));
    }

    @EventHandler(priority = EventPriority.LOWEST) public void onMove(PlayerMoveEvent e) { if (!loggedIn.contains(e.getPlayer().getUniqueId()) && e.hasChangedPosition()) e.setTo(e.getFrom()); }
    @EventHandler(priority = EventPriority.LOWEST) public void onCmd(PlayerCommandPreprocessEvent e) { if (!loggedIn.contains(e.getPlayer().getUniqueId()) && !getConfig().getStringList("security.allowed-commands").contains(List.of(e.getMessage().split(" ")).get(0).toLowerCase())) e.setCancelled(true); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) { if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onHeld(PlayerItemHeldEvent e) { if (captchaManager.isPending(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (!loggedIn.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onInvOpen(InventoryOpenEvent e) { if (e.getPlayer() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onAttack(EntityDamageByEntityEvent e) { if (e.getDamager() instanceof Player p && !loggedIn.contains(p.getUniqueId())) e.setCancelled(true); }

    public ItemStack makeItem(Material mat, String name, List<String> lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            if (lore != null) { List<String> l = new ArrayList<>(); for (String s : lore) l.add(color(s)); meta.setLore(l); }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            is.setItemMeta(meta);
        }
        return is;
    }

    public String hashPassword(String pass, String salt) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((salt + pass).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
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
