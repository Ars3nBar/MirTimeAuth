package ru.mirtime.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public class MailService {
    private final MirTimeAuth plugin;

    public MailService(MirTimeAuth plugin) {
        this.plugin = plugin;
    }

    public void sendHtmlCode(String to, String subject, String titleText, String code) {
        try {
            String host = plugin.getConfig().getString("email.smtp-host", "smtp.yandex.ru");
            int port = plugin.getConfig().getInt("email.smtp-port", 465);
            String user = plugin.getConfig().getString("email.username", "");
            String pass = plugin.getConfig().getString("email.password", "");

            String html = "<div style='background:#050811;padding:36px 16px;font-family:Segoe UI,Arial,sans-serif;color:#eef6ff;text-align:center;'>"
                    + "<div style='max-width:440px;margin:auto;background:linear-gradient(180deg,#0c162c 0%,#080e1d 100%);border:1px solid #0066ff;border-radius:20px;padding:30px;box-shadow:0 0 35px rgba(0,119,255,0.28);'>"
                    + "<div style='display:inline-block;padding:6px 14px;border-radius:20px;background:rgba(0,229,255,0.12);border:1px solid rgba(0,229,255,0.3);color:#00e5ff;font-size:11px;font-weight:bold;letter-spacing:1.5px;margin-bottom:12px;'>БЕЗОПАСНОСТЬ АККАУНТА</div>"
                    + "<h1 style='color:#ffffff;font-size:26px;margin:0 0 8px 0;letter-spacing:1px;'>⚡ MIR-TIME</h1>"
                    + "<p style='color:#9ab2d4;font-size:14px;line-height:1.5;margin:0 0 22px 0;'>" + titleText + "</p>"
                    + "<div style='background:#060b16;border:2px dashed #00ff66;border-radius:14px;padding:18px 12px;margin:0 auto 14px auto;'>"
                    + "<div style='font-size:11px;color:#6d89ad;text-transform:uppercase;letter-spacing:1px;margin-bottom:6px;'>Нажмите на код, чтобы выделить</div>"
                    + "<span style='font-family:Consolas,monospace;font-size:34px;font-weight:900;letter-spacing:8px;color:#00ff66;user-select:all;-webkit-user-select:all;'>" + code + "</span>"
                    + "</div>"
                    + "<div style='background:rgba(0,102,255,0.12);border-radius:10px;padding:10px;font-size:12px;color:#00e5ff;margin-bottom:18px;'>"
                    + "Команда для ввода в игре: <b>/2fa emailconfirm " + code + "</b></div>"
                    + "<p style='color:#546e91;font-size:11px;margin:0;border-top:1px solid rgba(255,255,255,0.06);padding-top:14px;'>"
                    + "Если вы не запрашивали этот код, просто проигнорируйте письмо.</p>"
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

    public String generateRandomBase32(int len) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        return sb.toString();
    }

    public boolean verifyTOTP(String base32Secret, String codeStr) {
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
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0xF;
            return (((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)

                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF)) % 1_000_000;
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
}
