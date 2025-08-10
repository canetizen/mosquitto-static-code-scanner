package com.github.canetizen.viper;

import com.github.canetizen.proto.TacticalBroadcast;
import com.github.canetizen.proto.Weapon;
import org.eclipse.paho.client.mqttv3.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class ViperListener {
    private static FileWriter logWriter;
    private static MqttClient client;
    private static final String TOPIC = "TacticalBroadcast";

    public static void main(String[] args) {
        try {
            setupLogger();
            connectMqtt();
            subscribeToTopic();
        } catch (Exception e) {
            logError(e.getMessage());
        }
    }

    private static void setupLogger() throws IOException {
        File logDir = new File("logs");
        if (!logDir.exists()) logDir.mkdirs();
        logWriter = new FileWriter(new File(logDir, "viper-listener.log"), true);
    }

    private static void connectMqtt() throws MqttException {
        String broker = "tcp://" + System.getenv().getOrDefault("BROKER_HOST", "localhost")
                + ":" + System.getenv().getOrDefault("BROKER_PORT", "1883");
        String clientId = "viper-listener-" + UUID.randomUUID();
        client = new MqttClient(broker, clientId);
        client.setCallback(new MqttCallback() {
            public void connectionLost(Throwable cause) {}
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                TacticalBroadcast msg = TacticalBroadcast.parseFrom(message.getPayload());
                Map<String,Object> f = new LinkedHashMap<>();
                f.put("Topic", topic);
                f.put("AircraftId", msg.getAircraftId());
                f.put("Lat", msg.getLatitude());
                f.put("Lon", msg.getLongitude());
                f.put("AltFt", msg.getAltitudeFt());
                f.put("HeadingDeg", msg.getHeadingDeg());
                f.put("AirspeedKts", msg.getAirspeedKts());
                f.put("FuelPct", msg.getFuelPct());
                f.put("Weapons", formatWeapons(msg.getWeaponsList())); // tek satır
                f.put("Timestamp", msg.getTimestamp());
                prettyBlock("TacticalBroadcast ← received", "Viper", f);
            }
            public void deliveryComplete(IMqttDeliveryToken token) {}
        });
        client.connect();
        logInfo("Connected to broker " + broker);
    }

    private static void subscribeToTopic() throws MqttException {
        client.subscribe(TOPIC, 1);
        logInfo("Subscribed to topic " + TOPIC);
    }

    private static String formatWeapons(List<Weapon> weapons){
        if (weapons == null || weapons.isEmpty()) return "-";
        return weapons.stream()
                .map(w -> w.getType() + "×" + w.getCount())
                .collect(Collectors.joining(", "));
    }

    // ===== helpers =====
    private static void logInfo(String msg){ prettyLine("INFO", "Viper", msg); }
    private static void logError(String msg){ prettyLine("ERROR","Viper", msg); }

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
