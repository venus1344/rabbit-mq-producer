package com.rdxio;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;

public class Main {
    private static final Properties config = new Properties();
    private static final String QUEUE_NAME = "metrics";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String PC_NAME;
    private static final String SECRET_KEY;
    private static final String ENCRYPTION_KEY;
    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    
    static {
        try {
            // Load configuration
            config.load(Main.class.getClassLoader().getResourceAsStream("config.properties"));
            SECRET_KEY = System.getenv().getOrDefault("SECRET_KEY", config.getProperty("secret.key"));
            ENCRYPTION_KEY = System.getenv().getOrDefault("ENCRYPTION_KEY", config.getProperty("encryption.key"));
            PC_NAME = System.getProperty("user.name") + "-" + 
                     java.net.InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load configuration", e);
        }
    }

    static class EncryptedMessage {
        public String encryptedData;
        public String iv;
        public String hash;
        
        public EncryptedMessage(String encryptedData, String iv, String hash) {
            this.encryptedData = encryptedData;
            this.iv = iv;
            this.hash = hash;
        }
    }

    private static String calculateHMAC(String data) throws Exception {
        Mac sha256Hmac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(SECRET_KEY.getBytes(), "HmacSHA256");
        sha256Hmac.init(secretKey);
        return Base64.getEncoder().encodeToString(sha256Hmac.doFinal(data.getBytes()));
    }

    public static void main(String[] args) {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setUri(System.getenv().getOrDefault("RABBITMQ_URI", config.getProperty("rabbitmq.uri")));
            
            Connection connection = factory.newConnection();
            Channel channel = connection.createChannel();
            
            // Declare a queue instead of an exchange
            channel.queueDeclare(QUEUE_NAME, true, false, false, null);
            
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            executor.scheduleAtFixedRate(() -> publishMetrics(channel), 0, 1, TimeUnit.MILLISECONDS);
            
            Thread.sleep(Long.MAX_VALUE);
            
        } catch (IOException | TimeoutException | InterruptedException | URISyntaxException | KeyManagementException | NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }

    private static void publishMetrics(Channel channel) {
        try {
            SystemInfo si = new SystemInfo();
            HardwareAbstractionLayer hal = si.getHardware();
            
            // Get CPU usage
            CentralProcessor processor = hal.getProcessor();
            double cpuUsage = processor.getSystemCpuLoad(1000);
            
            // Get memory information
            GlobalMemory memory = hal.getMemory();
            long totalMemory = memory.getTotal();
            long usedMemory = totalMemory - memory.getAvailable();
            double memoryUsage = (double) usedMemory / totalMemory * 100;
            
            // Get storage information
            long totalStorage = hal.getDiskStores().stream()
                    .mapToLong(store -> store.getSize())
                    .sum();

            // Create metrics map
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("cpuUsage", String.format("%.2f%%", cpuUsage * 100));
            metrics.put("memoryUsage", String.format("%.2f%%", memoryUsage));
            metrics.put("totalMemory", formatBytes(totalMemory));
            metrics.put("usedMemory", formatBytes(usedMemory));
            metrics.put("totalStorage", formatBytes(totalStorage));
            metrics.put("pcIdentifier", PC_NAME);
            
            // Serialize metrics to JSON
            String metricsJson = objectMapper.writeValueAsString(metrics);
            
            // Generate IV
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            String ivString = Base64.getEncoder().encodeToString(iv);
            
            // Encrypt the data
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(ENCRYPTION_KEY.getBytes(), "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            byte[] encrypted = cipher.doFinal(metricsJson.getBytes());
            String encryptedData = Base64.getEncoder().encodeToString(encrypted);
            
            // Calculate HMAC of encrypted data
            String hash = calculateHMAC(encryptedData);
            
            // Create final message
            EncryptedMessage message = new EncryptedMessage(encryptedData, ivString, hash);
            String finalMessage = objectMapper.writeValueAsString(message);
            
            channel.basicPublish("", QUEUE_NAME, null, finalMessage.getBytes());
            System.out.println("Published encrypted metrics: " + finalMessage);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String formatBytes(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        double size = bytes;
        
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        
        return String.format("%.2f %s", size, units[unitIndex]);
    }
}