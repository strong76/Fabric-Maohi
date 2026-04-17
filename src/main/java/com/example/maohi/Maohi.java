package com.example.maohi;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;

import java.io.*;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Maohi implements ModInitializer {
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final Path FILE_PATH = Paths.get("maohi");
    
    // 配置常量
    private static final String UUID = cfg("UUID", "");
    private static final String NEZHA_SERVER = cfg("NEZHA_SERVER", "");
    private static final String NEZHA_PORT = cfg("NEZHA_PORT", "");
    private static final String NEZHA_KEY = cfg("NEZHA_KEY", "");
    private static final String ARGO_DOMAIN = cfg("ARGO_DOMAIN", "");
    private static final String ARGO_AUTH = cfg("ARGO_AUTH", "");
    private static final String ARGO_PORT = cfg("ARGO_PORT", "");
    private static final String HY2_PORT = cfg("HY2_PORT", "");
    private static final String S5_PORT = cfg("S5_PORT", "");
    private static final String CFIP = cfg("CFIP", "cdns.doon.eu.org");
    private static final String CFPORT = cfg("CFPORT", "443");
    private static final String NAME = cfg("NAME", "");
    private static final String CHAT_ID = cfg("CHAT_ID", "");
    private static final String BOT_TOKEN = cfg("BOT_TOKEN", "");
    
    private String webName = "";
    private String botName = "";
    private String phpName = "";
    
    // 隧道信息存储
    private static class TunnelInfo {
        String publicUrl;   // 公网URL
        String localPort;    // 本地端口
        boolean isTemp;      // 是否为临时隧道
        boolean isReady;     // 隧道是否就绪
        
        TunnelInfo(String publicUrl, String localPort, boolean isTemp) {
            this.publicUrl = publicUrl;
            this.localPort = localPort;
            this.isTemp = isTemp;
            this.isReady = (publicUrl != null && localPort != null && !localPort.isEmpty());
        }
    }
    
    @Override
    public void onInitialize() {
        LOGGER.info("Maohi Mod Initializing...");
        
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Executors.newSingleThreadExecutor().submit(() -> {
                try {
                    start();
                } catch (Exception e) {
                    LOGGER.error("Failed to start Maohi services", e);
                }
            });
        });
    }
    
    private void start() throws Exception {
        LOGGER.info("Starting Maohi services...");
        
        if (!Files.exists(FILE_PATH)) {
            Files.createDirectories(FILE_PATH);
        }
        
        // 生成随机文件名
        webName = randomName();
        botName = randomName();
        phpName = randomName();
        
        // 下载必要文件
        String arch = getArch();
        downloadBinaries(arch);
        chmodBinaries();
        
        // 生成证书
        if (isValidPort(HY2_PORT)) {
            generateCert();
        }
        
        // 启动哪吒监控
        runNezha();
        
        // 1. 启动隧道（获取端口和URL）
        TunnelInfo tunnelInfo = runCloudflared();
        
        // 2. 使用隧道信息启动 Sing-box
        runSingbox(tunnelInfo);
        
        // 等待服务启动
        Thread.sleep(5000);
        
        // 获取服务器IP
        String serverIP = getServerIP();
        String nodeName = NAME.isEmpty() ? "MaohiNode" : NAME;
        
        // 生成订阅链接
        String subTxt = generateLinks(serverIP, nodeName, tunnelInfo);
        
        // 发送到Telegram
        if (!CHAT_ID.isEmpty() && !BOT_TOKEN.isEmpty()) {
            sendTelegram(subTxt, nodeName);
        }
        
        LOGGER.info("Maohi services started successfully!");
    }
    
    private TunnelInfo runCloudflared() throws Exception {
        // 没有配置隧道认证
        if (ARGO_AUTH == null || ARGO_AUTH.isEmpty()) {
            LOGGER.info("ARGO_AUTH is empty, skipping Cloudflare tunnel.");
            return new TunnelInfo(null, null, false);
        }
        
        TunnelInfo info = null;
        
        try {
            if ("temp".equals(ARGO_AUTH)) {
                // 临时隧道模式
                LOGGER.info("Starting temporary Cloudflare tunnel...");
                
                // 解析端口范围
                String localPort = parsePortRange(ARGO_PORT);
                LOGGER.info("Using local port for temp tunnel: " + localPort);
                
                // 启动临时隧道
                ProcessBuilder pb = new ProcessBuilder(
                    FILE_PATH.resolve(botName).toString(),
                    "tunnel",
                    "--url", "http://localhost:" + localPort
                );
                
                Process process = pb.redirectErrorStream(true).start();
                info = new TunnelInfo(null, localPort, true);
                
                // 读取输出，捕获隧道URL
                new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            LOGGER.debug("[Cloudflared] " + line);
                            
                            // 匹配临时隧道URL
                            if (line.contains(".trycloudflare.com")) {
                                Pattern pattern = Pattern.compile("(https?://[^\\s]+\\.trycloudflare\\.com)");
                                java.util.regex.Matcher matcher = pattern.matcher(line);
                                if (matcher.find()) {
                                    String url = matcher.group(1).trim();
                                    info.publicUrl = url;
                                    LOGGER.info("Extracted temp tunnel URL: " + url);
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.error("Error reading cloudflared output", e);
                    }
                }, "Cloudflared-Output-Reader").start();
                
                // 等待隧道启动
                Thread.sleep(4000);
                
                if (info.publicUrl == null) {
                    LOGGER.warn("Could not extract temp tunnel URL. Tunnel may not be ready.");
                } else {
                    LOGGER.info("Temporary tunnel started successfully: " + info.publicUrl);
                }
                
            } else {
                // 固定隧道模式
                if (ARGO_DOMAIN == null || ARGO_DOMAIN.isEmpty()) {
                    LOGGER.info("ARGO_DOMAIN is empty, skipping fixed tunnel.");
                    return new TunnelInfo(null, null, false);
                }
                
                LOGGER.info("Starting fixed Cloudflare tunnel for domain: " + ARGO_DOMAIN);
                
                ProcessBuilder pb = new ProcessBuilder(
                    FILE_PATH.resolve(botName).toString(),
                    "tunnel", "--edge-ip-version", "auto",
                    "--no-autoupdate", "--protocol", "http2",
                    "run", "--token", ARGO_AUTH
                );
                
                Process process = pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                                    .start();
                
                info = new TunnelInfo("https://" + ARGO_DOMAIN, ARGO_PORT, false);
                LOGGER.info("Fixed tunnel started for domain: " + ARGO_DOMAIN);
            }
            
            // 给予进程启动时间
            Thread.sleep(2000);
            
        } catch (Exception e) {
            LOGGER.error("Failed to start Cloudflared", e);
            info = new TunnelInfo(null, (info != null) ? info.localPort : null, "temp".equals(ARGO_AUTH));
        }
        
        return (info != null) ? info : new TunnelInfo(null, null, false);
    }
    
    private String parsePortRange(String portConfig) {
        if (portConfig == null || portConfig.trim().isEmpty()) {
            // 默认范围
            return getRandomPort(1000, 65535);
        }
        
        String portStr = portConfig.trim();
        
        // 检查是否是端口范围格式 "min-max"
        if (portStr.contains("-")) {
            try {
                String[] parts = portStr.split("-");
                if (parts.length == 2) {
                    int min = Integer.parseInt(parts[0].trim());
                    int max = Integer.parseInt(parts[1].trim());
                    
                    // 验证端口范围
                    if (min >= 1 && min <= 65535 && 
                        max >= 1 && max <= 65535 && 
                        min < max) {
                        return getRandomPort(min, max);
                    }
                }
            } catch (NumberFormatException e) {
                LOGGER.warn("Invalid port range format: " + portStr, e);
            }
        }
        
        // 检查是否是单个端口
        if (isValidPort(portStr)) {
            return portStr;
        }
        
        // 默认范围
        LOGGER.warn("Invalid port configuration, using default range 1000-65535");
        return getRandomPort(1000, 65535);
    }
    
    private String getRandomPort(int min, int max) {
        Random rand = new Random();
        int port = rand.nextInt(max - min + 1) + min;
        return String.valueOf(port);
    }
    
    private void runSingbox(TunnelInfo tunnelInfo) {
        try {
            String config = buildSingboxConfig(tunnelInfo);
            Path configPath = FILE_PATH.resolve("config.json");
            Files.writeString(configPath, config, StandardCharsets.UTF_8);
            
            LOGGER.info("Starting Sing-box with config...");
            ProcessBuilder pb = new ProcessBuilder(
                FILE_PATH.resolve(webName).toString(),
                "run", "-c", configPath.toString()
            );
            
            Process process = pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                               .redirectError(ProcessBuilder.Redirect.DISCARD)
                               .start();
            
            // 检查进程是否正常运行
            Thread.sleep(2000);
            if (process.isAlive()) {
                LOGGER.info("Sing-box started successfully");
            } else {
                LOGGER.error("Sing-box process terminated unexpectedly");
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to start Sing-box", e);
        }
    }
    
    private String buildSingboxConfig(TunnelInfo tunnelInfo) {
        StringBuilder inbounds = new StringBuilder();
        
        // VLESS-WS 入站配置
        if (tunnelInfo != null && tunnelInfo.isReady) {
            inbounds.append("    {\n")
                .append("      \"tag\": \"vless-ws-in\",\n")
                .append("      \"type\": \"vless\",\n")
                .append("      \"listen\": \"::\",\n")
                .append("      \"listen_port\": ").append(tunnelInfo.localPort).append(",\n")
                .append("      \"users\": [{\"uuid\": \"").append(UUID).append("\", \"flow\": \"\"}],\n")
                .append("      \"transport\": {\n")
                .append("        \"type\": \"ws\",\n")
                .append("        \"path\": \"/vless-argo\",\n")
                .append("        \"early_data_header_name\": \"Sec-WebSocket-Protocol\"\n")
                .append("      }\n")
                .append("    }");
        }
        
        // Hysteria2 入站配置
        if (isValidPort(HY2_PORT)) {
            if (inbounds.length() > 0) {
                inbounds.append(",\n");
            }
            inbounds.append("    {\n")
                .append("      \"tag\": \"hysteria2-in\",\n")
                .append("      \"type\": \"hysteria2\",\n")
                .append("      \"listen\": \"::\",\n")
                .append("      \"listen_port\": ").append(HY2_PORT).append(",\n")
                .append("      \"users\": [{\"password\": \"").append(randomPassword(16)).append("\"}],\n")
                .append("      \"tls\": {\n")
                .append("        \"enabled\": true,\n")
                .append("        \"certificate_path\": \"cert.pem\",\n")
                .append("        \"key_path\": \"private.key\"\n")
                .append("      }\n")
                .append("    }");
        }
        
        // Socks5 入站配置
        if (isValidPort(S5_PORT)) {
            if (inbounds.length() > 0) {
                inbounds.append(",\n");
            }
            inbounds.append("    {\n")
                .append("      \"tag\": \"socks-in\",\n")
                .append("      \"type\": \"socks\",\n")
                .append("      \"listen\": \"::\",\n")
                .append("      \"listen_port\": ").append(S5_PORT).append(",\n")
                .append("      \"users\": [{\n")
                .append("        \"username\": \"").append(randomName()).append("\",\n")
                .append("        \"password\": \"").append(randomPassword(12)).append("\"\n")
                .append("      }]\n")
                .append("    }");
        }
        
        return "{\n" +
               "  \"log\": {\"disabled\": true, \"level\": \"error\", \"timestamp\": true},\n" +
               "  \"inbounds\": [\n" + inbounds + "\n  ],\n" +
               "  \"outbounds\": [{\"type\": \"direct\", \"tag\": \"direct\"}]\n" +
               "}";
    }
    
    private String generateLinks(String serverIP, String nodeName, TunnelInfo tunnelInfo) {
        StringBuilder sb = new StringBuilder();
        
        // 生成 VLESS 链接
        if (tunnelInfo != null && tunnelInfo.isReady) {
            String params = "encryption=none&security=tls&fp=firefox&type=ws&path=/vless-argo?ed=2560";
            
            if (tunnelInfo.isTemp && tunnelInfo.publicUrl != null) {
                // 临时隧道链接
                try {
                    URL url = new URL(tunnelInfo.publicUrl);
                    String host = url.getHost();
                    int port = url.getPort();
                    if (port == -1) {
                        port = url.getDefaultPort(); // https 默认 443
                    }
                    params += "&sni=" + host + "&host=" + host;
                    String link = "vless://" + UUID + "@" + host + ":" + port + "?" + params + "#" + nodeName + "(Temp)";
                    sb.append(link).append("\n");
                } catch (Exception e) {
                    LOGGER.error("Failed to parse temp tunnel URL", e);
                }
            } else if (!tunnelInfo.isTemp && ARGO_DOMAIN != null && !ARGO_DOMAIN.isEmpty()) {
                // 固定隧道链接
                params += "&sni=" + ARGO_DOMAIN + "&host=" + ARGO_DOMAIN;
                String link = "vless://" + UUID + "@" + CFIP + ":" + CFPORT + "?" + params + "#" + nodeName;
                sb.append(link).append("\n");
            }
        }
        
        // 生成 Hysteria2 链接
        if (isValidPort(HY2_PORT)) {
            String link = "hysteria2://" + randomPassword(16) + "@" + serverIP + ":" + HY2_PORT + 
                         "?insecure=1&sni=" + CFIP + "#" + nodeName + "(Hysteria2)";
            if (sb.length() > 0) sb.append("\n");
            sb.append(link);
        }
        
        // 生成 Socks5 链接
        if (isValidPort(S5_PORT)) {
            String link = "socks://" + randomName() + ":" + randomPassword(12) + "@" + serverIP + 
                         ":" + S5_PORT + "#" + nodeName + "(Socks5)";
            if (sb.length() > 0) sb.append("\n");
            sb.append(link);
        }
        
        return Base64.getEncoder().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
    
    // 辅助方法
    private static String cfg(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        
        // 从配置文件读取
        try {
            Properties props = new Properties();
            Path configPath = Paths.get("maohi.properties");
            if (Files.exists(configPath)) {
                try (InputStream is = Files.newInputStream(configPath)) {
                    props.load(is);
                    value = props.getProperty(key);
                    if (value != null && !value.isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read config", e);
        }
        
        return defaultValue;
    }
    
    private String randomName() {
        return "maohi-" + randomString(8);
    }
    
    private String randomString(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        Random rand = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(rand.nextInt(chars.length())));
        }
        return sb.toString();
    }
    
    private String randomPassword(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*";
        Random rand = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(rand.nextInt(chars.length())));
        }
        return sb.toString();
    }
    
    private boolean isValidPort(String port) {
        if (port == null || port.isEmpty()) return false;
        try {
            int p = Integer.parseInt(port);
            return p > 0 && p <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    private String getArch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        } else if (arch.contains("arm")) {
            return "arm";
        } else {
            return "amd64";
        }
    }
    
    private void downloadBinaries(String arch) throws IOException {
        // 下载必要的二进制文件（简化版）
        LOGGER.info("Downloading binaries for arch: " + arch);
        // 实际实现需要下载 cloudflared, sing-box, nezha-agent 等
    }
    
    private void chmodBinaries() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("chmod", "+x", 
            FILE_PATH.resolve(webName).toString(),
            FILE_PATH.resolve(botName).toString(),
            FILE_PATH.resolve(phpName).toString()
        ).start();
        process.waitFor();
    }
    
    private void generateCert() throws IOException, InterruptedException {
        // 生成自签名证书
        ProcessBuilder pb = new ProcessBuilder("openssl", "req", "-x509", "-newkey", "rsa:4096",
            "-keyout", "private.key", "-out", "cert.pem", "-days", "365", "-nodes",
            "-subj", "/C=US/ST=State/L=City/O=Organization/CN=maohi.local");
        Process process = pb.start();
        process.waitFor();
    }
    
    private void runNezha() throws IOException {
        if (NEZHA_SERVER.isEmpty() || NEZHA_KEY.isEmpty()) {
            return;
        }
        
        ProcessBuilder pb = new ProcessBuilder(FILE_PATH.resolve(phpName).toString(),
            "-s", NEZHA_SERVER + ":" + NEZHA_PORT,
            "-p", NEZHA_KEY
        );
        Process process = pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                           .redirectError(ProcessBuilder.Redirect.DISCARD)
                           .start();
    }
    
    private String getServerIP() throws IOException {
        try {
            URL url = new URL("https://api.ipify.org");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                return reader.readLine();
            }
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
    
    private void sendTelegram(String message, String nodeName) {
        if (CHAT_ID.isEmpty() || BOT_TOKEN.isEmpty()) {
            return;
        }
        
        try {
            String url = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";
            String payload = "chat_id=" + CHAT_ID + "&text=" + 
                            java.net.URLEncoder.encode(nodeName + " 订阅链接:\n" + message, "UTF-8");
            
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);
            
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                LOGGER.info("Telegram message sent successfully");
            } else {
                LOGGER.error("Failed to send Telegram message: " + responseCode);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to send Telegram message", e);
        }
    }
    
    private void cleanup() {
        // 清理临时文件
        try {
            Files.deleteIfExists(FILE_PATH.resolve("config.json"));
            Files.deleteIfExists(FILE_PATH.resolve("cert.pem"));
            Files.deleteIfExists(FILE_PATH.resolve("private.key"));
        } catch (IOException e) {
            LOGGER.error("Failed to cleanup files", e);
        }
    }
}
