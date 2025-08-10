/*
 * Author: canetizen
 * Created on Mon Aug 11 2025
 * Description: Example MQTT-based Java service that demonstrates publishing and/or subscribing 
 *              to specific topics using the Eclipse Paho MQTT client library.
 */

package com.github.canetizen.hawk;

import com.github.canetizen.proto.EngagementCommand;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class HawkEngagementMonitor {
    private static final Logger log = LogManager.getLogger("Hawk");
    private static final String TOPIC = "EngagementCommand";
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
        String clientId = "hawk-engagement-monitor-" + UUID.randomUUID();

        client = new MqttClient(broker, clientId);
        client.setCallback(new MqttCallback() {
            public void connectionLost(Throwable cause) {
                log.warn("Connection lost: {}", cause.toString());
            }

            public void messageArrived(String topic, MqttMessage message) throws Exception {
                EngagementCommand cmd = EngagementCommand.parseFrom(message.getPayload());

                Map<String,Object> fields = new LinkedHashMap<>();
                fields.put("Topic", topic);
                fields.put("CommandId", cmd.getCommandId());
                fields.put("From", cmd.getFromCallsign());
                fields.put("To", cmd.getToCallsign());
                fields.put("Type", cmd.getCommandType());
                fields.put("TargetId", cmd.getTargetId());
                fields.put("TargetLat", cmd.getTargetLat());
                fields.put("TargetLon", cmd.getTargetLon());
                fields.put("TargetAltFt", cmd.getTargetAltFt());
                fields.put("Priority", cmd.getPriority());
                fields.put("Timestamp", cmd.getTimestamp());

                log.info(buildMessage("EngagementCommand ← received", fields));
            }

            public void deliveryComplete(IMqttDeliveryToken token) {}
        });

        client.connect();
        log.info("Connected to broker {}", broker);
    }

    private static void subscribeToTopic() throws MqttException {
        client.subscribe(TOPIC, 1);
        log.info("Subscribed to topic {}", TOPIC);
    }

    private static String buildMessage(String title, Map<String, Object> fields){
        StringBuilder sb = new StringBuilder();
        sb.append(title).append('\n');
        fields.forEach((k,v) -> sb.append(k).append(": ").append(v).append('\n'));
        return sb.toString();
    }
}
