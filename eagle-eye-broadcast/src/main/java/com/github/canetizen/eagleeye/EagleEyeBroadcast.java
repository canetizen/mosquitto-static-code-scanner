package com.github.canetizen.eagleeye;

import com.github.canetizen.proto.TacticalBroadcast;
import com.github.canetizen.proto.Weapon;
import org.eclipse.paho.client.mqttv3.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

public class EagleEyeBroadcast {
    private static FileWriter logWriter;
    private static MqttClient client;
    private static final String TOPIC = "TacticalBroadcast";
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
        logWriter = new FileWriter(new File(logDir, "eagle-eye.log"), true);
    }

    private static void connectMqtt() throws MqttException {
        String broker = "tcp://" + System.getenv().getOrDefault("BROKER_HOST", "localhost")
                + ":" + System.getenv().getOrDefault("BROKER_PORT", "1883");
        String clientId = "eagle-eye-broadcast-" + UUID.randomUUID();
        client = new MqttClient(broker, clientId);
        client.connect();
        logInfo("Connected to broker " + broker);
    }

    private static void publishLoop() throws Exception {
        while (true) {
            TacticalBroadcast msg = TacticalBroadcast.newBuilder()
                    .setAircraftId("EAGLE-01")
                    .setLatitude(39.0 + random.nextDouble())
                    .setLongitude(32.0 + random.nextDouble())
                    .setAltitudeFt(20000 + random.nextInt(10000))
                    .setHeadingDeg(random.nextInt(360))
                    .setAirspeedKts(400 + random.nextInt(200))
                    .setFuelPct(50 + random.nextInt(50))
                    .addWeapons(Weapon.newBuilder().setType("AIM-120").setCount(random.nextInt(3)).build())
                    .addWeapons(Weapon.newBuilder().setType("AIM-9").setCount(random.nextInt(3)).build())
                    .setTimestamp(Instant.now().toEpochMilli())
                    .build();

            client.publish(TOPIC, msg.toByteArray(), 1, false);

            Map<String,Object> f = new LinkedHashMap<>();
            f.put("AircraftId", msg.getAircraftId());
            f.put("Lat", msg.getLatitude());
            f.put("Lon", msg.getLongitude());
            f.put("AltFt", msg.getAltitudeFt());
            f.put("HeadingDeg", msg.getHeadingDeg());
            f.put("AirspeedKts", msg.getAirspeedKts());
            f.put("FuelPct", msg.getFuelPct());
            f.put("Weapons", formatWeapons(msg.getWeaponsList())); // << burada düz
            f.put("Timestamp", msg.getTimestamp());
            prettyBlock("TacticalBroadcast → publish", "EagleEye", f);

            Thread.sleep(3000);
        }
    }

    private static String formatWeapons(List<Weapon> weapons){
        if (weapons == null || weapons.isEmpty()) return "-";
        return weapons.stream()
                .map(w -> w.getType() + "×" + w.getCount())
                .collect(Collectors.joining(", "));
    }

    private static void cleanup() {
        try { if (client != null && client.isConnected()) client.disconnect(); } catch (Exception ignored) {}
        try { if (logWriter != null) logWriter.close(); } catch (Exception ignored) {}
    }

    // ===== helpers =====
    private static void logInfo(String msg){ prettyLine("INFO", "EagleEye", msg); }
    private static void logError(String msg){ prettyLine("ERROR","EagleEye", msg); }

    private static void prettyBlock(String title, String app, Map<String,Object> f){
        String ts = ZonedDateTime.now().toString();
        String header = ts + " DATA  " + pad(app,12) + " | " + title;
        String border = repeat('─', Math.max(72, header.length()));
        write(border); write(header); write(border);

        int tmpWidth = f.keySet().stream().mapToInt(String::length).max().orElse(10);
        final int keyWidth = Math.max(10, Math.min(24, tmpWidth));
        f.forEach((k,v)-> write(String.format("%-" + keyWidth + "s : %s", k, v)));

        write(border); write("");
    }
    private static void prettyLine(String level, String app, String msg){
        String ts = ZonedDateTime.now().toString();
        write(String.format("%s %-5s %s | %s", ts, level, pad(app,12), msg));
    }
    private static String pad(String s,int n){ return String.format("%-" + n + "s", s); }
    private static String repeat(char c,int n){ char[] a=new char[n]; java.util.Arrays.fill(a,c); return new String(a); }
    private static void write(String s){
        try{
            if (logWriter == null) setupLogger();
            logWriter.write(s.endsWith("\n")? s : s+"\n");
            logWriter.flush();
        }catch(IOException ignored){}
    }
}
