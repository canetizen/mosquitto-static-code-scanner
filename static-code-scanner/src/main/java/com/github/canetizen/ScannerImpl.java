/*
 * Author: canetizen
 * Created on Mon Aug 11 2025
 * Description:
 *  Utility methods for module discovery, constant collection, argument resolution,
 *  and intra-method inference.
 */

package com.github.canetizen;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class ScannerImpl {

    /**
     * Walks the filesystem under {@code root} and returns directories that look like Maven modules:
     * a directory containing both a {@code pom.xml} file and a {@code src/} folder.
     *
     * Behavior note: If nothing is found, the caller falls back to scanning the given root itself.
     */
    public static List<File> discoverMavenModules(Path root) throws IOException {
        List<File> modules = new ArrayList<>();
        // Traverse all files, pick any pom.xml and then check its parent has a src directory
        Files.walk(root)
                .filter(p -> p.getFileName().toString().equals("pom.xml"))
                .forEach(pom -> {
                    File dir = pom.getParent().toFile();
                    if (new File(dir, "src").isDirectory()) modules.add(dir);
                });
        // De-duplicate and sort for deterministic order across runs
        return modules.stream().distinct().sorted(Comparator.comparing(File::getAbsolutePath)).toList();
    }

    /**
     * Lightweight check to see whether a compilation unit references Eclipse Paho MQTT types.
     * We prefer imports, but also fall back to a toString() search for common type names
     * to catch fully-qualified or unimported usages.
     */
    public static boolean usesPaho(CompilationUnit cu) {
        return cu.getImports().stream()
                .anyMatch(i -> i.getNameAsString().startsWith("org.eclipse.paho.client.mqttv3"))
                || cu.toString().contains("MqttClient")
                || cu.toString().contains("IMqttClient")
                || cu.toString().contains("MqttMessage");
    }

    /**
     * Collects local {@code static final String} constants defined in the same compilation unit.
     * These are used to resolve topics like {@code publish(TOPIC, ...)}.
     *
     * Limitation: does not follow cross-class or cross-compilation-unit references.
     */
    public static Map<String, String> collectStaticFinalStringConstants(CompilationUnit cu) {
        Map<String, String> map = new HashMap<>();
        cu.findAll(FieldDeclaration.class).forEach(fd -> {
            if (!fd.isStatic() || !fd.isFinal()) return;
            fd.getVariables().forEach(v -> v.getInitializer().ifPresent(init -> {
                if (init.isStringLiteralExpr()) {
                    map.put(v.getNameAsString(), init.asStringLiteralExpr().asString());
                }
            }));
        });
        return map;
    }

    /**
     * Collects local {@code static final int} constants (e.g., QoS constants).
     * Similar scope limitation as string constants—same compilation unit only.
     */
    public static Map<String, String> collectStaticFinalIntConstants(CompilationUnit cu) {
        Map<String, String> map = new HashMap<>();
        cu.findAll(FieldDeclaration.class).forEach(fd -> {
            if (!fd.isStatic() || !fd.isFinal()) return;
            fd.getVariables().forEach(v -> v.getInitializer().ifPresent(init -> {
                if (init.isIntegerLiteralExpr()) {
                    map.put(v.getNameAsString(), init.asIntegerLiteralExpr().getValue());
                }
            }));
        });
        return map;
    }

    /**
     * Analyzes a single {@link MethodCallExpr} and, if it is a Paho publish/subscribe/retained call,
     * returns a populated {@link StaticCodeScanner.Hit}. Otherwise returns {@code null}.
     *
     * Strategy:
     * - Topic/QoS from literals and local constants
     * - Simple string concatenations for topics
     * - Array arguments (topics[], qos[]) are joined with " | "
     * - For {@code publish(String, MqttMessage)} we scan earlier calls on the same message variable
     *   within the enclosing method to infer QoS/retained via {@code setQos}/{@code setRetained}.
     */
    public static StaticCodeScanner.Hit analyzeMethodCall(MethodCallExpr m, File root, File module, File jf,
                                                          Map<String, String> stringConsts, Map<String, String> intConsts) {
        String name = m.getNameAsString();
        // Only interested in publish/subscribe/setRetained
        if (!(name.equals("publish") || name.equals("subscribe") || name.equals("setRetained"))) return null;

        int lineNo = m.getBegin().map(p -> p.line).orElse(-1);
        String kind;
        String topic = "?";
        String qos = "?";
        String retained = "?";

        if (name.equals("publish")) {
            kind = "PUBLISH";
            // Arg0: topic
            if (m.getArguments().size() >= 1) {
                topic = resolveTopicArg(m.getArgument(0), stringConsts);
            }
            // Overload 1: publish(String, MqttMessage)
            if (m.getArguments().size() == 2) {
                InferredMsgSettings s = inferMsgSettingsFromSameMethod(m, m.getArgument(1));
                if (s.qos != null) qos = qosWithMeaning(s.qos);
                if (s.retained != null) retained = retainedWithMeaning(s.retained);
            } else {
                // Overload 2: publish(String, byte[], int qos, boolean retained)
                if (m.getArguments().size() >= 3) {
                    qos = qosWithMeaning(resolveQos(m.getArgument(2), stringConsts, intConsts));
                }
                if (m.getArguments().size() >= 4) {
                    retained = retainedWithMeaning(resolveRetained(m.getArgument(3)));
                }
            }
        } else if (name.equals("subscribe")) {
            kind = "SUBSCRIBE";
            // subscribe(String topic, int qos) or subscribe(String[] topics, int[] qos)
            if (m.getArguments().size() >= 1) {
                topic = resolveTopicArg(m.getArgument(0), stringConsts);
            }
            if (m.getArguments().size() >= 2) {
                Expression qosArg = m.getArgument(1);
                // If qos is an array, resolve each and join with " | "
                if (qosArg.isArrayCreationExpr() || qosArg.isArrayInitializerExpr()) {
                    List<String> qosItems = resolveQosList(qosArg, stringConsts, intConsts)
                            .stream().map(ScannerImpl::qosWithMeaning).toList();
                    qos = String.join(" | ", qosItems);
                } else {
                    qos = qosWithMeaning(resolveQos(qosArg, stringConsts, intConsts));
                }
            }
        } else {
            // setRetained(true/false) applied on an MqttMessage instance
            kind = "SET_RETAINED";
            if (m.getArguments().size() >= 1) {
                retained = retainedWithMeaning(resolveRetained(m.getArgument(0)));
            }
        }

        // Build the report row with normalized relative file path
        return new StaticCodeScanner.Hit(
                module.getName(),
                rel(root, jf),
                String.valueOf(lineNo),
                kind,
                topic,
                qos,
                retained
        );
    }

    /** Small holder for values inferred from earlier calls on the same MqttMessage variable. */
    private static class InferredMsgSettings { String qos; String retained; }

    /**
     * For the {@code publish(String, MqttMessage)} overload, look inside the enclosing method body
     * for earlier calls on the same message variable (by simple name match) such as:
     * {@code msg.setQos(1);} or {@code msg.setRetained(true);}.
     *
     * Constraints:
     * - Only simple variable names are supported (no fields, no array access, no method returns).
     * - Only statements that appear *before* the publish call are considered.
     * - No complex data flow (no reassignment tracking, no cross-method flow).
     */
    private static InferredMsgSettings inferMsgSettingsFromSameMethod(MethodCallExpr publishCall, Expression msgArg) {
        InferredMsgSettings s = new InferredMsgSettings();
        if (!msgArg.isNameExpr()) return s; // only simple var names supported
        String varName = msgArg.asNameExpr().getNameAsString();

        Optional<MethodDeclaration> maybeMethod = publishCall.findAncestor(MethodDeclaration.class);
        if (maybeMethod.isEmpty()) return s;

        Optional<BlockStmt> maybeBlock = maybeMethod.get().getBody();
        if (maybeBlock.isEmpty()) return s;

        int publishLine = publishCall.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE);

        // Find method calls on the same variable, before the publish, within the same block.
        List<MethodCallExpr> priorCalls = maybeBlock.get().findAll(MethodCallExpr.class).stream()
                .filter(mc -> mc != publishCall)
                .filter(mc -> mc.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE) < publishLine)
                .filter(mc -> mc.getScope().isPresent()
                        && mc.getScope().get().isNameExpr()
                        && mc.getScope().get().asNameExpr().getNameAsString().equals(varName))
                .toList();

        // Extract qos/retained from those prior calls if present.
        for (MethodCallExpr mc : priorCalls) {
            String mname = mc.getNameAsString();
            if (mname.equals("setQos") && mc.getArguments().size() >= 1) {
                s.qos = resolveQos(mc.getArgument(0), Map.of(), Map.of());
            } else if (mname.equals("setRetained") && mc.getArguments().size() >= 1) {
                s.retained = resolveRetained(mc.getArgument(0));
            }
        }
        return s;
    }

    /**
     * Resolves a topic argument which could be:
     * - a single string literal or identifier (possibly a local constant), or
     * - an array of strings (initializer/creation), which will be joined with {@code " | "}.
     */
    private static String resolveTopicArg(Expression expr, Map<String, String> stringConsts) {
        if (expr.isArrayCreationExpr()) {
            return expr.asArrayCreationExpr().getInitializer()
                    .map(init -> init.getValues().stream()
                            .map(e -> resolveString(e, stringConsts))
                            .collect(Collectors.joining(" | ")))
                    .orElse(expr.toString()); // if no initializer, keep raw expression
        }
        if (expr.isArrayInitializerExpr()) {
            return expr.asArrayInitializerExpr().getValues().stream()
                    .map(e -> resolveString(e, stringConsts))
                    .collect(Collectors.joining(" | "));
        }
        return resolveString(expr, stringConsts);
    }

    /**
     * Tries to turn an expression into a concrete string value by:
     * - returning literal strings,
     * - replacing local constant names with their values,
     * - resolving simple {@code a + b} concatenations recursively.
     * Falls back to {@code expr.toString()} when not resolvable.
     */
    private static String resolveString(Expression expr, Map<String, String> stringConsts) {
        if (expr.isStringLiteralExpr()) return expr.asStringLiteralExpr().asString();

        if (expr.isNameExpr()) {
            String n = expr.asNameExpr().getNameAsString();
            if (stringConsts.containsKey(n)) return stringConsts.get(n);
        }

        if (expr.isBinaryExpr() && expr.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            return resolveString(expr.asBinaryExpr().getLeft(), stringConsts)
                    + resolveString(expr.asBinaryExpr().getRight(), stringConsts);
        }
        // Unknown or complex expression: keep raw source
        return expr.toString();
    }

    /**
     * Resolves QoS from an expression:
     * - integer literal → "0" / "1" / "2"
     * - local int or string constants by name
     * - otherwise returns the raw expression text
     */
    private static String resolveQos(Expression expr, Map<String, String> stringConsts, Map<String, String> intConsts) {
        if (expr.isIntegerLiteralExpr()) return expr.asIntegerLiteralExpr().getValue();

        if (expr.isNameExpr()) {
            String n = expr.asNameExpr().getNameAsString();
            if (intConsts.containsKey(n)) return intConsts.get(n);
            if (stringConsts.containsKey(n)) return stringConsts.get(n);
        }
        return expr.toString();
    }

    /**
     * Resolves a list of QoS values from an array expression (creation/initializer).
     * For non-array inputs, returns a singleton list for convenience.
     */
    private static List<String> resolveQosList(Expression expr, Map<String, String> stringConsts, Map<String, String> intConsts) {
        List<String> out = new ArrayList<>();
        if (expr.isArrayCreationExpr()) {
            expr.asArrayCreationExpr().getInitializer()
                    .ifPresent(init -> init.getValues().forEach(v -> out.add(resolveQos(v, stringConsts, intConsts))));
            return out;
        }
        if (expr.isArrayInitializerExpr()) {
            expr.asArrayInitializerExpr().getValues()
                    .forEach(v -> out.add(resolveQos(v, stringConsts, intConsts)));
            return out;
        }
        // Single value case
        out.add(resolveQos(expr, stringConsts, intConsts));
        return out;
    }

    /**
     * Extracts boolean retained flag:
     * - boolean literal → "true"/"false"
     * - otherwise the raw expression text (e.g., a variable name or method call)
     */
    private static String resolveRetained(Expression expr) {
        if (expr.isBooleanLiteralExpr()) return String.valueOf(expr.asBooleanLiteralExpr().getValue());
        return expr.toString();
    }

    /**
     * Maps raw QoS values ("0", "1", "2" or unknown strings) to a human-friendly label.
     */
    private static String qosWithMeaning(String qos) {
        return switch (qos) {
            case "0" -> "0 (At most once)";
            case "1" -> "1 (At least once)";
            case "2" -> "2 (Exactly once)";
            default -> qos + " (Unknown)";
        };
    }

    /**
     * Maps retained flag to a human-friendly label.
     */
    private static String retainedWithMeaning(String retained) {
        return switch (retained) {
            case "true" -> "true (Retained / Persistent)";
            case "false" -> "false (Non-retained / Transient)";
            default -> retained + " (Unknown)";
        };
    }

    /**
     * Computes the file path of {@code f} relative to {@code root}, both normalized and absolute,
     * to make output stable regardless of the current working directory.
     */
    private static String rel(File root, File f) {
        return root.toPath().toAbsolutePath().normalize()
                .relativize(f.toPath().toAbsolutePath().normalize()).toString();
    }
}
