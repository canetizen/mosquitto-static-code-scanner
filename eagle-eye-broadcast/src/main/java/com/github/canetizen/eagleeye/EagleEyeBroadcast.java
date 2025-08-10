/*
 * Author: canetizen
 * Created on Mon Aug 11 2025
 * Description: Example MQTT-based Java service that demonstrates publishing and/or subscribing 
 *              to specific topics using the Eclipse Paho MQTT client library.
 */

package com.github.canetizen.eagleeye;

import com.github.canetizen.proto.TacticalBroadcast;
import com.github.canetizen.proto.Weapon;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

public class EagleEyeBroadcast {
    private static final Logger log = LogManager.getLogger("EagleEye");
    private static final String TOPIC = "TacticalBroadcast";
    private static final Random random = new Random();
    private static MqttClient client;

    public static void main(String[] args) {
        try {
            addShutdownHook();
            connectMqtt();
            publishLoop();
        } catch (Exception e) {
            log.error("{}", e.toString());
        } finally {
            cleanup();
        }
    }

    private static void connectMqtt() throws MqttException {
        String broker = "tcp://" + System.getenv().getOrDefault("BROKER_HOST", "localhost")
                + ":" + System.getenv().getOrDefault("BROKER_PORT", "1883");
        String clientId = "eagle-eye-broadcast-" + UUID.randomUUID();
        client = new MqttClient(broker, clientId);
        client.connect();
        log.info("Connected to broker {}", broker);
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

            Map<String,Object> fields = new LinkedHashMap<>();
            fields.put("Topic", TOPIC);
            fields.put("AircraftId", msg.getAircraftId());
            fields.put("Lat", msg.getLatitude());
            fields.put("Lon", msg.getLongitude());
            fields.put("AltFt", msg.getAltitudeFt());
            fields.put("HeadingDeg", msg.getHeadingDeg());
            fields.put("AirspeedKts", msg.getAirspeedKts());
            fields.put("FuelPct", msg.getFuelPct());
            fields.put("Weapons", formatWeapons(msg.getWeaponsList()));
            fields.put("Timestamp", msg.getTimestamp());

            log.info(buildMessage("TacticalBroadcast → publish", fields));
            Thread.sleep(3000);
        }
    }

    private static String formatWeapons(List<Weapon> weapons){
        if (weapons == null || weapons.isEmpty()) return "-";
        return weapons.stream()
                .map(w -> w.getType() + "×" + w.getCount())
                .collect(Collectors.joining(", "));
    }

    private static String buildMessage(String title, Map<String, Object> fields){
        StringBuilder sb = new StringBuilder();
        sb.append(title).append('\n');
        fields.forEach((k,v) -> sb.append(k).append(": ").append(v).append('\n'));
        return sb.toString();
    }

    private static void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(EagleEyeBroadcast::cleanup));
    }

    private static void cleanup() {
        try { if (client != null && client.isConnected()) client.disconnect(); } catch (Exception ignored) {}
    }
}
