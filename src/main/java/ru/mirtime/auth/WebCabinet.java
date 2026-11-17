package ru.mirtime.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class WebCabinet {
    private final MirTimeAuth plugin;
    private HttpServer server;

    public WebCabinet(MirTimeAuth plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("web.enabled", true)) return;
        int port = plugin.getConfig().getInt("web.port", 8085);
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", this::handle);
            server.setExecutor(null);
            server.start();
            Bukkit.getConsoleSender().sendMessage(MirTimeAuth.color("&#00FF66  [MirTimeAuth] Мини-сайт запущен на порту: &f" + port));
        } catch (IOException e) {
            Bukkit.getConsoleSender().sendMessage(MirTimeAuth.color("&c  [MirTimeAuth] Ошибка мини-сайта: " + e.getMessage()));
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
    }
    private void handle(HttpExchange ex) throws IOException {
        String notice = "";
        String loggedUser = null;
        String savedPass = "";

        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = parseParams(body);
            String action = form.getOrDefault("action", "");
            String user = form.getOrDefault("username", "").trim().toLowerCase();
            String pass = form.getOrDefault("password", "");

            if (!plugin.getDb().contains("users." + user + ".hash")) {
                notice = "<div class='alert err'>✖ Игрок не зарегистрирован на сервере!</div>";
            } else {
                String salt = plugin.getDb().getString("users." + user + ".salt", "");
                if (!plugin.getDb().getString("users." + user + ".hash", "").equals(plugin.hashPassword(pass, salt))) {
                    notice = "<div class='alert err'>✖ Неверный игровой пароль!</div>";
                } else {
                    loggedUser = user;
                    savedPass = pass;
                    if ("enable_2fa".equals(action)) {
                        String secret = plugin.getDb().getString("users." + user + ".totp-secret", "");
                        if (plugin.getMail().verifyTOTP(secret, form.getOrDefault("code", "").trim())) {
                            plugin.getDb().set("users." + user + ".totp-enabled", true);
                            plugin.saveDb();
                            notice = "<div class='alert ok'>✔ Google Authenticator (2FA) успешно активирован!</div>";
                        } else {
                            notice = "<div class='alert err'>✖ Неверный 6-значный код из приложения!</div>";
                        }
                    } else if ("disable_2fa".equals(action)) {
                        plugin.getDb().set("users." + user + ".totp-enabled", false);
                        plugin.getDb().set("users." + user + ".totp-secret", plugin.getMail().generateRandomBase32(16));
                        plugin.saveDb();
                        notice = "<div class='alert ok'>✔ Двухфакторная защита отключена.</div>";
                    } else if ("save_email".equals(action)) {
                        String em = form.getOrDefault("email", "").trim();
                        if (em.contains("@") && em.contains(".")) {
                            plugin.getDb().set("users." + user + ".email", em);
                            plugin.saveDb();
                            notice = "<div class='alert ok'>✔ Почта привязана: " + escape(em) + "</div>";
                        } else {
                            notice = "<div class='alert err'>✖ Укажите корректный Email!</div>";
                        }
                    } else if ("remove_email".equals(action)) {
                        plugin.getDb().set("users." + user + ".email", null);
                        plugin.saveDb();
                        notice = "<div class='alert ok'>✔ Почта успешно отвязана от аккаунта.</div>";
                    } else if ("change_pass".equals(action)) {
                        String np = form.getOrDefault("new_password", "");
                        int min = plugin.getConfig().getInt("security.min-password-length", 4);
                        if (np.length() >= min && np.length() <= 32) {
                            String ns = plugin.getMail().generateRandomBase32(16);
                            plugin.getDb().set("users." + user + ".salt", ns);
                            plugin.getDb().set("users." + user + ".hash", plugin.hashPassword(np, ns));
                            plugin.saveDb();
                            savedPass = np;
                            notice = "<div class='alert ok'>✔ Игровой пароль обновлён!</div>";
                        } else {
                            notice = "<div class='alert err'>✖ Длина пароля: от " + min + " до 32 символов.</div>";
                        }
                    } else if ("kick_self".equals(action)) {
                        String rName = plugin.getDb().getString("users." + user + ".name", user);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Player t = Bukkit.getPlayerExact(rName);
                            if (t != null) t.kickPlayer(MirTimeAuth.color("&#0055ff&l⚡ MIR-TIME &8» &cСессия завершена из личного кабинета!"));
                        });
                        notice = "<div class='alert ok'>✔ Команда кика отправлена на сервер.</div>";
                    }
                }
            }
        }
        String mcHost = "https://mc-heads.net";
        String qrHost = "https://api.qrserver.com/v1/create-qr-code/?size=160x160&data=";
        String fontUrl = "https://fonts.googleapis.com/css2?family=Montserrat:wght@500;700;900&display=swap";

        String css = "<style>@import url('" + fontUrl + "');"
                + "*{box-sizing:border-box}body{margin:0;font-family:'Montserrat',sans-serif;background:#04070f;color:#eef6ff;min-height:100vh;"
                + "display:flex;justify-content:center;align-items:center;padding:20px;"
                + "background-image:radial-gradient(circle at 50% -10%,rgba(0,119,255,0.35),transparent 55%),"
                + "linear-gradient(rgba(0,229,255,0.035) 1px,transparent 1px),linear-gradient(90deg,rgba(0,229,255,0.035) 1px,transparent 1px);"
                + "background-size:100% 100%,34px 34px,34px 34px;}"
                + ".panel{background:rgba(10,17,32,0.88);backdrop-filter:blur(18px);border:1px solid rgba(0,229,255,0.35);"
                + "border-radius:24px;box-shadow:0 0 60px rgba(0,85,255,0.28),inset 0 0 24px rgba(0,229,255,0.05);padding:28px;width:100%;}"
                + ".brand{font-size:28px;font-weight:900;letter-spacing:2px;background:linear-gradient(90deg,#0066ff,#00e5ff,#00ff66);"
                + "-webkit-background-clip:text;-webkit-text-fill-color:transparent;margin:0;}"
                + "input{width:100%;padding:13px 15px;margin:7px 0;border-radius:12px;border:1px solid #1d3563;"
                + "background:rgba(5,10,20,0.92);color:#fff;font-family:inherit;font-size:13px;outline:none;transition:.25s;}"
                + "input:focus{border-color:#00e5ff;box-shadow:0 0 16px rgba(0,229,255,0.3);}"
                + ".btn{width:100%;padding:12px;border:none;border-radius:12px;font-family:inherit;font-weight:700;cursor:pointer;margin-top:8px;"
                + "font-size:12px;letter-spacing:.6px;text-transform:uppercase;transition:.25s;background:linear-gradient(135deg,#0055ff,#00d4ff);color:#fff;}"
                + ".btn:hover{transform:translateY(-2px);box-shadow:0 8px 24px rgba(0,229,255,0.38);}"
                + ".btn.green{background:linear-gradient(135deg,#00b84d,#00ff66);color:#031409;}"
                + ".btn.red{background:linear-gradient(135deg,#b82e24,#ff4757);color:#fff;}"
                + ".alert{padding:12px 14px;border-radius:12px;margin-bottom:14px;font-size:13px;font-weight:700;}"
                + ".alert.ok{background:rgba(0,255,102,0.12);border:1px solid #00ff66;color:#00ff66;}"
                + ".alert.err{background:rgba(255,71,87,0.12);border:1px solid #ff4757;color:#ff6b81;}"
                + ".grid{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-top:14px;}@media(max-width:640px){.grid{grid-template-columns:1fr;}}"
                + ".card{background:rgba(14,24,46,0.78);border:1px solid rgba(0,119,255,0.32);border-radius:18px;padding:18px;display:flex;flex-direction:column;justify-content:space-between;}"
                + ".card h3{margin:0 0 10px;font-size:13px;text-transform:uppercase;letter-spacing:.6px;color:#00e5ff;}"
                + ".badge{display:inline-block;padding:4px 10px;border-radius:8px;font-size:11px;font-weight:700;background:rgba(0,229,255,0.12);color:#00e5ff;margin:4px 4px 0 0;}</style>";
        if (loggedUser == null) {
            String loginHtml = "<!DOCTYPE html><html lang='ru'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                    + "<title>MIR-TIME | Личный кабинет</title>" + css + "</head><body>"
                    + "<div class='panel' style='max-width:410px;text-align:center;'>"
                    + "<div style='display:inline-block;padding:4px;border-radius:20px;background:linear-gradient(135deg,#0055ff,#00ff66);margin-bottom:12px;'>"
                    + "<img id='av' src='" + mcHost + "/avatar/Steve/76' width='76' height='76' style='display:block;border-radius:16px;background:#080e1c;'></div>"
                    + "<h1 class='brand'>⚡ MIR-TIME</h1>"
                    + "<p style='color:#8aa4c8;font-size:12px;margin:6px 0 20px;'>Центр безопасности игрового аккаунта</p>"
                    + notice
                    + "<form method='post'><input type='hidden' name='action' value='login'>"
                    + "<input type='text' name='username' placeholder='Ваш игровой ник' oninput=\"document.getElementById('av').src='" + mcHost + "/avatar/'+(this.value||'Steve')+'/76'\" required>"
                    + "<input type='password' name='password' placeholder='Ваш игровой пароль' required>"
                    + "<button class='btn green' type='submit'>Войти в кабинет ➔</button></form>"
                    + "<div style='margin-top:18px;padding-top:14px;border-top:1px solid rgba(255,255,255,0.07);font-size:11px;color:#637fa3;'>"
                    + "Анархия 1.21+ &bull; Защита 2FA + Email</div></div></body></html>";
            sendHtml(ex, loginHtml);
            return;
        }

        String realName = plugin.getDb().getString("users." + loggedUser + ".name", loggedUser);
        String lastIp = plugin.getDb().getString("users." + loggedUser + ".last-ip", "Неизвестно");
        String email = plugin.getDb().getString("users." + loggedUser + ".email", "");
        boolean hasEmail = !email.isEmpty();
        boolean totpEnabled = plugin.getDb().getBoolean("users." + loggedUser + ".totp-enabled", false);
        String secret = plugin.getDb().getString("users." + loggedUser + ".totp-secret", "");
        if (secret.isEmpty()) {
            secret = plugin.getMail().generateRandomBase32(16);
            plugin.getDb().set("users." + loggedUser + ".totp-secret", secret);
            plugin.saveDb();
        }
        int secScore = 40 + (totpEnabled ? 35 : 0) + (hasEmail ? 25 : 0);
        String secColor = secScore >= 90 ? "#00ff66" : (secScore >= 65 ? "#00e5ff" : "#ffa502");

        String hiddenAuth = "<input type='hidden' name='username' value='" + escape(loggedUser) + "'>"
                + "<input type='hidden' name='password' value='" + escape(savedPass) + "'>";
        String otpUri = "otpauth://totp/MIR-TIME:" + URLEncoder.encode(realName, StandardCharsets.UTF_8) + "?secret=" + secret + "&issuer=MIR-TIME";
        String qrUrl = qrHost + URLEncoder.encode(otpUri, StandardCharsets.UTF_8);

        String twoFaCard = totpEnabled
                ? "<div style='background:rgba(0,255,102,0.1);border:1px solid rgba(0,255,102,0.3);padding:12px;border-radius:12px;color:#00ff66;font-size:13px;font-weight:700;text-align:center;'>"
                + "✔ Google Authenticator подключён</div>"
                + "<form method='post'>" + hiddenAuth + "<input type='hidden' name='action' value='disable_2fa'><button class='btn red' type='submit'>Отключить 2FA</button></form>"
                : "<p style='font-size:11px;color:#9ab2d4;margin:0 0 8px;'>Отсканируйте QR или введите ключ: <code style='background:#050811;padding:3px 6px;border-radius:6px;color:#00ff66;'>" + secret + "</code></p>"
                + "<img src='" + qrUrl + "' style='display:block;margin:8px auto;border-radius:12px;border:4px solid #fff;'>"
                + "<form method='post'>" + hiddenAuth + "<input type='hidden' name='action' value='enable_2fa'><input type='text' name='code' placeholder='6 цифр из приложения' maxlength='6' required><button class='btn green' type='submit'>Включить 2FA ✔</button></form>";

        String emailCard = hasEmail
                ? "<div style='background:rgba(0,229,255,0.08);border:1px solid rgba(0,229,255,0.25);padding:10px;border-radius:10px;font-size:12px;color:#00e5ff;margin-bottom:8px;'>Текущая: <b>" + escape(email) + "</b></div>"
                + "<form method='post'>" + hiddenAuth + "<input type='hidden' name='action' value='save_email'><input type='email' name='email' placeholder='Новый Email' required><button class='btn' type='submit'>Сменить почту</button></form>"
                + "<form method='post'>" + hiddenAuth + "<input type='hidden' name='action' value='remove_email'><button class='btn red' type='submit'>Отвязать почту</button></form>"
                : "<p style='font-size:12px;color:#8aa4c8;margin:0 0 8px;'>Почта нужна для сброса забытого пароля через <b>/recovery</b>.</p>"
                + "<form method='post'>" + hiddenAuth + "<input type='hidden' name='action' value='save_email'><input type='email' name='email' placeholder='Введите ваш Email' required><button class='btn green' type='submit'>Привязать почту</button></form>";
        String dashHtml = "<!DOCTYPE html><html lang='ru'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>MIR-TIME | " + escape(realName) + "</title>" + css + "</head><body>"
                + "<div class='panel' style='max-width:740px;'>"
                + "<div style='display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid rgba(255,255,255,0.08);padding-bottom:16px;gap:12px;flex-wrap:wrap;'>"
                + "<div style='display:flex;align-items:center;gap:16px;'>"
                + "<img src='" + mcHost + "/body/" + escape(realName) + "/96' style='height:96px;filter:drop-shadow(0 0 14px rgba(0,229,255,0.45));'>"
                + "<div><h2 style='margin:0;font-size:22px;color:#fff;'>⚡ " + escape(realName) + "</h2>"
                + "<div class='badge'>IP: " + escape(lastIp) + "</div>"
                + "<div class='badge' style='color:" + secColor + ";'>Защита аккаунта: " + secScore + "%</div>"
                + "<div style='width:180px;height:6px;background:#091122;border-radius:6px;margin-top:8px;overflow:hidden;'>"
                + "<div style='width:" + secScore + "%;height:100%;background:" + secColor + ";'></div></div></div></div>"
                + "<a href='/' style='padding:9px 16px;border-radius:12px;background:rgba(255,71,87,0.15);border:1px solid #ff4757;color:#ff6b81;text-decoration:none;font-size:12px;font-weight:700;'>Выйти</a></div>"
                + "<div style='margin-top:14px;'>" + notice + "</div>"
                + "<div class='grid'>"
                + "<div class='card'><h3>📱 Google Authenticator</h3>" + twoFaCard + "</div>"
                + "<div class='card'><h3>📧 Резервная почта</h3>" + emailCard + "</div>"
                + "<div class='card'><h3>🔑 Смена пароля</h3><form method='post'>" + hiddenAuth + "<input type='hidden' name='action' value='change_pass'><input type='password' name='new_password' placeholder='Новый пароль' required><button class='btn' type='submit'>Обновить пароль</button></form></div>"
                + "<div class='card'><h3>🛡 Экстренная защита</h3><p style='font-size:12px;color:#8aa4c8;margin:0 0 8px;'>Если на аккаунт зашёл посторонний, мгновенно выкиньте его с сервера.</p><form method='post'>" + hiddenAuth + "<input type='hidden' name='action' value='kick_self'><button class='btn red' type='submit'>Кикнуть аккаунт с сервера</button></form></div>"
                + "</div></div></body></html>";
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
            if (kv.length == 2) map.put(URLDecoder.decode(kv, StandardCharsets.UTF_8), URLDecoder.decode(kv, StandardCharsets.UTF_8));
        }
        return map;
    }

    private String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
