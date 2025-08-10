package com.github.canetizen.falconstrike;

import com.github.canetizen.proto.CommandType;
import com.github.canetizen.proto.EngagementCommand;
import org.eclipse.paho.client.mqttv3.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class FalconStrikeCommand {
    private static FileWriter logWriter;
    private static MqttClient client;
    private static final String TOPIC = "EngagementCommand";
    private static final Random random = new Random();

    public static void main(String[] args) {
        try {
            setupLogger();
            connectMqtt();
            publishLoop();
        } catch (Exception e) {
            logError(e.getMessage());
        } finally {
            cleanup();
        }
    }

    private static void setupLogger() throws IOException {
        File logDir = new File("logs");
        if (!logDir.exists()) logDir.mkdirs();
        logWriter = new FileWriter(new File(logDir, "falcon-strike.log"), true);
    }

    private static void connectMqtt() throws MqttException {
        String broker = "tcp://" + System.getenv().getOrDefault("BROKER_HOST", "localhost")
                + ":" + System.getenv().getOrDefault("BROKER_PORT", "1883");
        String clientId = "falcon-strike-command-" + UUID.randomUUID();
        client = new MqttClient(broker, clientId);
        client.connect();
        logInfo("Connected to broker " + broker);
    }

    private static void publishLoop() throws Exception {
        while (true) {
            EngagementCommand cmd = EngagementCommand.newBuilder()
                    .setCommandId(UUID.randomUUID().toString())
                    .setFromCallsign("FALCON-OPS")
                    .setToCallsign("VIPER-" + (1 + random.nextInt(5)))
                    .setCommandType(CommandType.ENGAGE)
                    .setTargetId("TARGET-" + random.nextInt(1000))
                    .setTargetLat(39.0 + random.nextDouble())
                    .setTargetLon(32.0 + random.nextDouble())
                    .setTargetAltFt(15000 + random.nextInt(10000))
                    .setPriority(random.nextInt(3) + 1)
                    .setTimestamp(Instant.now().toEpochMilli())
                    .build();

            client.publish(TOPIC, cmd.toByteArray(), 2, false);

            Map<String,Object> f = new LinkedHashMap<>();
            f.put("CommandId", cmd.getCommandId());
            f.put("From", cmd.getFromCallsign());
            f.put("To", cmd.getToCallsign());
            f.put("Type", cmd.getCommandType());
            f.put("TargetId", cmd.getTargetId());
            f.put("TargetLat", cmd.getTargetLat());
            f.put("TargetLon", cmd.getTargetLon());
            f.put("TargetAltFt", cmd.getTargetAltFt());
            f.put("Priority", cmd.getPriority());
            f.put("Timestamp", cmd.getTimestamp());
            prettyBlock("EngagementCommand → publish", "FalconStrike", f);

            Thread.sleep(5000);
        }
    }

    private static void cleanup() {
        try { if (client != null && client.isConnected()) client.disconnect(); } catch (Exception ignored) {}
        try { if (logWriter != null) logWriter.close(); } catch (Exception ignored) {}
    }

    // ====== Local formatting helpers (module-only) ======
    private static void logInfo(String msg){ prettyLine("INFO", "FalconStrike", msg); }
    private static void logError(String msg){ prettyLine("ERROR","FalconStrike", msg); }

    private static void prettyBlock(String title, String app, Map<String,Object> f){
        String ts = ZonedDateTime.now().toString();
        String header = ts + " DATA  " + pad(app,12) + " | " + title;
        String border = repeat('─', Math.max(72, header.length()));
        write(border);
        write(header);
        write(border);

        int tmpWidth = f.keySet().stream().mapToInt(String::length).max().orElse(10);
        final int keyWidth = Math.max(10, Math.min(24, tmpWidth)); // final → lambda-safe

        f.forEach((k,v)-> write(String.format("%-" + keyWidth + "s : %s", k, v)));
        write(border);
        write("");
    }

    private static void prettyLine(String level, String app, String msg){
        String ts = ZonedDateTime.now().toString();
        write(String.format("%s %-5s %s | %s", ts, level, pad(app,12), msg));
    }

    private static String pad(String s,int n){ return String.format("%-" + n + "s", s); }
    private static String repeat(char c,int n){ char[] a=new char[n]; java.util.Arrays.fill(a,c); return new String(a); }

    private static void write(String s) {
        try {
            if (logWriter == null) setupLogger();
            logWriter.write(s.endsWith("\n") ? s : (s + "\n"));
            logWriter.flush();
        } catch (IOException ignored) {}
    }
}
