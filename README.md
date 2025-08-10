# Mosquitto Static Code Scanner

This repository contains example MQTT-based Java services and a static code scanner tool that detects `publish` and `subscribe` calls within the codebase.

---

## Limitations of the Scanner

The **Static Code Scanner** uses *static analysis* — it parses Java source code without executing it.
Because of this, it has some inherent limitations:

* **Dynamic values**: Topics, QoS, or retained flags read from environment variables, config files, or computed at runtime will appear as raw expressions (e.g., `"config.getTopic()"`) rather than resolved values.
* **MQTT message overloads**:

  * For `publish(String, MqttMessage)`, QoS and retained flags are extracted from the `MqttMessage` object at runtime — the scanner will mark these as `?`.
  * For `subscribe(String[] topics, int[] qos)`, the scanner will print joined arrays but cannot guarantee correct pairing of topic and QoS.
* **Separate `setRetained` calls**: If `setRetained(true/false)` is called on an `MqttMessage` in a separate line from the `publish` call, the scanner reports it as a separate `SET_RETAINED` row without linking it to a specific topic.
* **No cross-method tracking**: If a topic or message is constructed in one method and passed to another, the scanner won't resolve it.
* **No runtime verification**: The scanner cannot confirm whether the code paths are actually executed during service runtime — it only inspects the source.

---

## Running the Example Services

The **Static Code Scanner** performs *static analysis* only — it examines the source code without running the services.
However, to **verify that the services actually work and messages flow correctly**, you can run the included example services.

This project includes:

* **eagle-eye-broadcast** → Publishes `TacticalBroadcast` messages
* **falcon-strike-command** → Publishes `EngagementCommand` messages
* **hawk-engagement-monitor** → Subscribes to `EngagementCommand` messages
* **raptor-listener** → Subscribes to `TacticalBroadcast` messages
* **viper-listener** → Subscribes to `TacticalBroadcast` messages
* **hydra-duplex-node** → Publishes `TacticalBroadcast` and subscribes to `EngagementCommand` messages

### Start All Services (Foreground)

```bash
make up
```

### Start All Services (Detached Mode)

```bash
make up-d
```

### Start a Single Service (e.g., viper-listener)

```bash
make up-viper-listener
```

### Stop All Services

```bash
make down
```

---

## Running the Static Code Scanner

The scanner searches all Maven modules in the repository for MQTT `publish` and `subscribe` calls, reporting:

* Project name
* File path
* Line number
* Call type (`PUBLISH` or `SUBSCRIBE`)
* Topic
* QoS (numeric value + meaning)
* Retained flag (boolean value + meaning)

### Build the Scanner

```bash
make build-scan
```

### Run the Scanner

```bash
make run-scan
```

### Example Output

| Project                 | File                                                                                           | Line | Kind      | Topic             |               QoS | Retained                         |
| ----------------------- | ---------------------------------------------------------------------------------------------- | ---: | --------- | ----------------- | ----------------: | -------------------------------- |
| eagle-eye-broadcast     | eagle-eye-broadcast/src/main/java/com/github/canetizen/eagleeye/EagleEyeBroadcast.java         |   60 | PUBLISH   | TacticalBroadcast | 1 (At least once) | false (Non-retained / Transient) |
| falcon-strike-command   | falcon-strike-command/src/main/java/com/github/canetizen/falconstrike/FalconStrikeCommand.java |   58 | PUBLISH   | EngagementCommand |  2 (Exactly once) | false (Non-retained / Transient) |
| hawk-engagement-monitor | hawk-engagement-monitor/src/main/java/com/github/canetizen/hawk/HawkEngagementMonitor.java     |   64 | SUBSCRIBE | EngagementCommand | 1 (At least once) | ?                                |
| hydra-duplex-node       | hydra-duplex-node/src/main/java/com/github/canetizen/HydraDuplexNode.java                      |   76 | SUBSCRIBE | EngagementCommand | 1 (At least once) | ?                                |
| hydra-duplex-node       | hydra-duplex-node/src/main/java/com/github/canetizen/HydraDuplexNode.java                      |   97 | PUBLISH   | TacticalBroadcast | 1 (At least once) | false (Non-retained / Transient) |
| raptor-listener         | raptor-listener/src/main/java/com/github/canetizen/raptor/RaptorListener.java                  |   66 | SUBSCRIBE | TacticalBroadcast |  2 (Exactly once) | ?                                |
| viper-listener          | viper-listener/src/main/java/com/github/canetizen/viper/ViperListener.java                     |   66 | SUBSCRIBE | TacticalBroadcast | 1 (At least once) | ?                                |

> **Note on `?` values:**
> The `?` symbol means the scanner could not determine the value statically.
> This typically happens for:
>
> * **QoS:** When the value is computed dynamically or retrieved from variables/configuration outside the scanned class.
> * **Retained:** For `SUBSCRIBE` calls, the retained flag is not part of the method signature, so it will always appear as `?`.

---