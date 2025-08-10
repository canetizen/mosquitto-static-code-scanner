/*
 * Author: canetizen
 * Created on Mon Aug 11 2025
 * Description: Example MQTT-based Java service that demonstrates publishing and/or subscribing 
 *              to specific topics using the Eclipse Paho MQTT client library.
 */

package com.github.canetizen.hydra;

import com.github.canetizen.proto.TacticalBroadcast;
import com.github.canetizen.proto.EngagementCommand;
import com.github.canetizen.proto.Weapon;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

public class HydraDuplexNode {
    private static final Logger log = LogManager.getLogger("Hydra");
    private static final String PUBLISH_TOPIC = "TacticalBroadcast";
    private static final String SUBSCRIBE_TOPIC = "EngagementCommand";
    private static final Random random = new Random();
    private static MqttClient client;

    public static void main(String[] args) {
        try {
            addShutdownHook();
            connectMqtt();
            subscribeToEngagementCommand();
            startPublishTacticalBroadcast();
            log.info("HydraDuplexNode started: Publishing → {} | Subscribing ← {}", PUBLISH_TOPIC, SUBSCRIBE_TOPIC);
            synchronized (HydraDuplexNode.class) { HydraDuplexNode.class.wait(); }
        } catch (Throwable e) {
            log.error("Fatal error", e);
        } finally {
            cleanup();
        }
    }

    private static void connectMqtt() throws MqttException {
        String broker = "tcp://" + System.getenv().getOrDefault("BROKER_HOST", "localhost")
                + ":" + System.getenv().getOrDefault("BROKER_PORT", "1883");
        String clientId = "hydra-duplex-" + UUID.randomUUID();

        client = new MqttClient(broker, clientId);
        client.setCallback(new MqttCallback() {
            public void connectionLost(Throwable cause) {
                log.warn("Connection lost: {}", cause.toString(), cause);
            }

            public void messageArrived(String topic, MqttMessage message) throws Exception {
                if (topic.equals(SUBSCRIBE_TOPIC)) {
                    EngagementCommand cmd = EngagementCommand.parseFrom(message.getPayload());

                    Map<String,Object> fields = new LinkedHashMap<>();
                    fields.put("Topic", topic);
                    fields.put("TargetId", cmd.getTargetId());
                    fields.put("CommandType", cmd.getCommandType());
                    fields.put("Priority", cmd.getPriority());
                    fields.put("Timestamp", cmd.getTimestamp());
                    fields.put("QoS", message.getQos());
                    fields.put("Retained", message.isRetained());

                    log.info(buildMessage("EngagementCommand ← received", fields));
                }
            }

            public void deliveryComplete(IMqttDeliveryToken token) {}
        });

        client.connect();
        log.info("Connected to broker {}", broker);
    }

    private static void subscribeToEngagementCommand() throws MqttException {
        client.subscribe(SUBSCRIBE_TOPIC, 1);
        log.info("Subscribed to topic {} (QoS=1)", SUBSCRIBE_TOPIC);
    }

    private static void startPublishTacticalBroadcast() {
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    TacticalBroadcast msg = TacticalBroadcast.newBuilder()
                            .setAircraftId("HYDRA-" + (1 + random.nextInt(9)))
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

                    client.publish(PUBLISH_TOPIC, msg.toByteArray(), 1, false);

                    Map<String,Object> fields = new LinkedHashMap<>();
                    fields.put("Topic", PUBLISH_TOPIC);
                    fields.put("AircraftId", msg.getAircraftId());
                    fields.put("Lat", msg.getLatitude());
                    fields.put("Lon", msg.getLongitude());
                    fields.put("AltFt", msg.getAltitudeFt());
                    fields.put("HeadingDeg", msg.getHeadingDeg());
                    fields.put("AirspeedKts", msg.getAirspeedKts());
                    fields.put("FuelPct", msg.getFuelPct());
                    fields.put("Weapons", formatWeapons(msg.getWeaponsList()));
                    fields.put("Timestamp", msg.getTimestamp());
                    fields.put("QoS", 1);
                    fields.put("Retained", false);

                    log.info(buildMessage("TacticalBroadcast → publish", fields));
                    Thread.sleep(3000);
                }
            } catch (Throwable e) {
                log.warn("Publisher loop stopped: {}", e.toString(), e);
            }
        }, "hydra-publisher");
        t.setDaemon(true);
        t.start();
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
        Runtime.getRuntime().addShutdownHook(new Thread(HydraDuplexNode::cleanup));
    }

    private static void cleanup() {
        try { if (client != null && client.isConnected()) client.disconnect(); } catch (Exception ignored) {}
    }
}
