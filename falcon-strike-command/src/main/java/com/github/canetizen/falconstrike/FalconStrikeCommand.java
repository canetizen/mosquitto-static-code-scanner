/*
 * Author: canetizen
 * Created on Mon Aug 11 2025
 * Description: Example MQTT-based Java service that demonstrates publishing and/or subscribing 
 *              to specific topics using the Eclipse Paho MQTT client library.
 */

package com.github.canetizen.falconstrike;

import com.github.canetizen.proto.CommandType;
import com.github.canetizen.proto.EngagementCommand;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class FalconStrikeCommand {
    private static final Logger log = LogManager.getLogger("FalconStrike");
    private static final String TOPIC = "EngagementCommand";
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
        String clientId = "falcon-strike-command-" + UUID.randomUUID();
        client = new MqttClient(broker, clientId);
        client.connect();
        log.info("Connected to broker {}", broker);
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

            Map<String,Object> fields = new LinkedHashMap<>();
            fields.put("Topic", TOPIC);
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

            log.info(buildMessage("EngagementCommand → publish", fields));

            Thread.sleep(5000);
        }
    }

    private static String buildMessage(String title, Map<String, Object> fields){
        StringBuilder sb = new StringBuilder();
        sb.append(title).append('\n');
        fields.forEach((k,v) -> sb.append(k).append(": ").append(v).append('\n'));
        return sb.toString();
    }

    private static void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(FalconStrikeCommand::cleanup));
    }

    private static void cleanup() {
        try { if (client != null && client.isConnected()) client.disconnect(); } catch (Exception ignored) {}
    }
}
