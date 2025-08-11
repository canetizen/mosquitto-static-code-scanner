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

public class ScannerUtils {

    public static List<File> discoverMavenModules(Path root) throws IOException {
        List<File> modules = new ArrayList<>();
        Files.walk(root)
                .filter(p -> p.getFileName().toString().equals("pom.xml"))
                .forEach(pom -> {
                    File dir = pom.getParent().toFile();
                    if (new File(dir, "src").isDirectory()) modules.add(dir);
                });
        return modules.stream().distinct().sorted(Comparator.comparing(File::getAbsolutePath)).toList();
    }

    public static boolean usesPaho(CompilationUnit cu) {
        return cu.getImports().stream()
                .anyMatch(i -> i.getNameAsString().startsWith("org.eclipse.paho.client.mqttv3"))
                || cu.toString().contains("MqttClient")
                || cu.toString().contains("IMqttClient")
                || cu.toString().contains("MqttMessage");
    }

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

    public static StaticCodeScanner.Hit analyzeMethodCall(MethodCallExpr m, File root, File module, File jf,
                                                          Map<String, String> stringConsts, Map<String, String> intConsts) {
        String name = m.getNameAsString();
        if (!(name.equals("publish") || name.equals("subscribe") || name.equals("setRetained"))) return null;

        int lineNo = m.getBegin().map(p -> p.line).orElse(-1);
        String kind;
        String topic = "?";
        String qos = "?";
        String retained = "?";

        if (name.equals("publish")) {
            kind = "PUBLISH";
            if (m.getArguments().size() >= 1) {
                topic = resolveTopicArg(m.getArgument(0), stringConsts);
            }
            if (m.getArguments().size() == 2) {
                InferredMsgSettings s = inferMsgSettingsFromSameMethod(m, m.getArgument(1));
                if (s.qos != null) qos = qosWithMeaning(s.qos);
                if (s.retained != null) retained = retainedWithMeaning(s.retained);
            } else {
                if (m.getArguments().size() >= 3) {
                    qos = qosWithMeaning(resolveQos(m.getArgument(2), stringConsts, intConsts));
                }
                if (m.getArguments().size() >= 4) {
                    retained = retainedWithMeaning(resolveRetained(m.getArgument(3)));
                }
            }
        } else if (name.equals("subscribe")) {
            kind = "SUBSCRIBE";
            if (m.getArguments().size() >= 1) {
                topic = resolveTopicArg(m.getArgument(0), stringConsts);
            }
            if (m.getArguments().size() >= 2) {
                Expression qosArg = m.getArgument(1);
                if (qosArg.isArrayCreationExpr() || qosArg.isArrayInitializerExpr()) {
                    List<String> qosItems = resolveQosList(qosArg, stringConsts, intConsts)
                            .stream().map(ScannerUtils::qosWithMeaning).toList();
                    qos = String.join(" | ", qosItems);
                } else {
                    qos = qosWithMeaning(resolveQos(qosArg, stringConsts, intConsts));
                }
            }
        } else {
            kind = "SET_RETAINED";
            if (m.getArguments().size() >= 1) {
                retained = retainedWithMeaning(resolveRetained(m.getArgument(0)));
            }
        }

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

    private static class InferredMsgSettings { String qos; String retained; }

    private static InferredMsgSettings inferMsgSettingsFromSameMethod(MethodCallExpr publishCall, Expression msgArg) {
        InferredMsgSettings s = new InferredMsgSettings();
        if (!msgArg.isNameExpr()) return s;
        String varName = msgArg.asNameExpr().getNameAsString();
        Optional<MethodDeclaration> maybeMethod = publishCall.findAncestor(MethodDeclaration.class);
        if (maybeMethod.isEmpty()) return s;
        Optional<BlockStmt> maybeBlock = maybeMethod.get().getBody();
        if (maybeBlock.isEmpty()) return s;
        int publishLine = publishCall.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE);

        List<MethodCallExpr> priorCalls = maybeBlock.get().findAll(MethodCallExpr.class).stream()
                .filter(mc -> mc != publishCall)
                .filter(mc -> mc.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE) < publishLine)
                .filter(mc -> mc.getScope().isPresent()
                        && mc.getScope().get().isNameExpr()
                        && mc.getScope().get().asNameExpr().getNameAsString().equals(varName))
                .toList();

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

    private static String resolveTopicArg(Expression expr, Map<String, String> stringConsts) {
        if (expr.isArrayCreationExpr()) {
            return expr.asArrayCreationExpr().getInitializer()
                    .map(init -> init.getValues().stream()
                            .map(e -> resolveString(e, stringConsts))
                            .collect(Collectors.joining(" | ")))
                    .orElse(expr.toString());
        }
        if (expr.isArrayInitializerExpr()) {
            return expr.asArrayInitializerExpr().getValues().stream()
                    .map(e -> resolveString(e, stringConsts))
                    .collect(Collectors.joining(" | "));
        }
        return resolveString(expr, stringConsts);
    }

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
        return expr.toString();
    }

    private static String resolveQos(Expression expr, Map<String, String> stringConsts, Map<String, String> intConsts) {
        if (expr.isIntegerLiteralExpr()) return expr.asIntegerLiteralExpr().getValue();
        if (expr.isNameExpr()) {
            String n = expr.asNameExpr().getNameAsString();
            if (intConsts.containsKey(n)) return intConsts.get(n);
            if (stringConsts.containsKey(n)) return stringConsts.get(n);
        }
        return expr.toString();
    }

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
        out.add(resolveQos(expr, stringConsts, intConsts));
        return out;
    }

    private static String resolveRetained(Expression expr) {
        if (expr.isBooleanLiteralExpr()) return String.valueOf(expr.asBooleanLiteralExpr().getValue());
        return expr.toString();
    }

    private static String qosWithMeaning(String qos) {
        return switch (qos) {
            case "0" -> "0 (At most once)";
            case "1" -> "1 (At least once)";
            case "2" -> "2 (Exactly once)";
            default -> qos + " (Unknown)";
        };
    }

    private static String retainedWithMeaning(String retained) {
        return switch (retained) {
            case "true" -> "true (Retained / Persistent)";
            case "false" -> "false (Non-retained / Transient)";
            default -> retained + " (Unknown)";
        };
    }

    private static String rel(File root, File f) {
        return root.toPath().toAbsolutePath().normalize()
                .relativize(f.toPath().toAbsolutePath().normalize()).toString();
    }
}
