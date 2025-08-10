/*
 * Author: canetizen
 * Created on Mon Aug 11 2025
 * Description: Example MQTT-based Java service that demonstrates publishing and/or subscribing 
 *              to specific topics using the Eclipse Paho MQTT client library.
 */

package com.github.canetizen.raptor;

import com.github.canetizen.proto.TacticalBroadcast;
import com.github.canetizen.proto.Weapon;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class RaptorListener {
    private static final Logger log = LogManager.getLogger("Raptor");
    private static final String TOPIC = "TacticalBroadcast";
    private static MqttClient client;

    public static void main(String[] args) {
        try {
            connectMqtt();
            subscribeToTopic();
        } catch (Exception e) {
            log.error("{}", e.toString());
        }
    }

    private static void connectMqtt() throws MqttException {
        String broker = "tcp://" + System.getenv().getOrDefault("BROKER_HOST", "localhost")
                + ":" + System.getenv().getOrDefault("BROKER_PORT", "1883");
        String clientId = "raptor-listener-" + UUID.randomUUID();

        client = new MqttClient(broker, clientId);
        client.setCallback(new MqttCallback() {
            public void connectionLost(Throwable cause) {
                log.warn("Connection lost: {}", cause.toString());
            }

            public void messageArrived(String topic, MqttMessage message) throws Exception {
                TacticalBroadcast msg = TacticalBroadcast.parseFrom(message.getPayload());

                Map<String,Object> fields = new LinkedHashMap<>();
                fields.put("Topic", topic);
                fields.put("AircraftId", msg.getAircraftId());
                fields.put("Lat", msg.getLatitude());
                fields.put("Lon", msg.getLongitude());
                fields.put("AltFt", msg.getAltitudeFt());
                fields.put("HeadingDeg", msg.getHeadingDeg());
                fields.put("AirspeedKts", msg.getAirspeedKts());
                fields.put("FuelPct", msg.getFuelPct());
                fields.put("Weapons", formatWeapons(msg.getWeaponsList()));
                fields.put("Timestamp", msg.getTimestamp());

                log.info(buildMessage("TacticalBroadcast ← received", fields));
            }

            public void deliveryComplete(IMqttDeliveryToken token) {}
        });

        client.connect();
        log.info("Connected to broker {}", broker);
    }

    private static void subscribeToTopic() throws MqttException {
        client.subscribe(TOPIC, 2);
        log.info("Subscribed to topic {}", TOPIC);
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
}
