/*
 * Author: canetizen
 * Created on Mon Aug 11 2025
 * Description:
 *  Scans a given root directory for Java sources and reports Eclipse Paho MQTT
 *  publish/subscribe calls with topic, QoS, and retained flag. Works in two modes:
 *
 *      1) Maven mode: recursively finds directories that contain both pom.xml and src/,
 *     treats each as a module and scans under module/src.
 *      2) Fallback mode: if no Maven modules are found, scans ALL .java files under the root.
 *
 *  Output:
 *      - CSV block
 *      - Markdown table block (also written to scan-report.md)
 *
 *  Resolution strategy (fast; no symbol-solver):
 *      - Topic/QoS from literals
 *      - Topic from local static final String fields (same CU)
 *      - Simple "a" + "b" concatenations
 *      - String[] topics joined with " | "
 *      - subscribe(String[] topics, int[] qos) → QoS list joined with " | "
 *      - publish(String, MqttMessage) → scan same method for msg.setQos / msg.setRetained before publish
 *
 *  Limitations:
 *      - Complex data flow (cross-method, reassignments) is not followed.
 *      - If topics/QoS/retained come from env/config at runtime, you'll see raw expressions.
 */

package com.github.canetizen;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.apache.commons.io.FileUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;


public class StaticCodeScanner {

    /** One row in the report. */
    record Hit(String project, String file, String line, String kind, String topic, String qos, String retained) {}

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".");
        if (!root.isDirectory()) {
            System.err.println("Root is not a directory: " + root.getAbsolutePath());
            System.exit(1);
        }

        List<File> modules = discoverMavenModules(root.toPath());
        if (modules.isEmpty()) {
            modules = List.of(root);
        }

        List<Hit> hits = new ArrayList<>();

        for (File module : modules) {
            File src = new File(module, "src");
            final boolean mavenLike = src.exists() && src.isDirectory();
            final File scanRoot = mavenLike ? src : module;

            Collection<File> javaFiles = FileUtils.listFiles(scanRoot, new String[]{"java"}, true);
            for (File jf : javaFiles) {
                CompilationUnit cu;
                try {
                    cu = StaticJavaParser.parse(jf);
                } catch (Exception ex) {
                    continue;
                }

                boolean usesPaho = cu.getImports().stream()
                        .anyMatch(i -> i.getNameAsString().startsWith("org.eclipse.paho.client.mqttv3"))
                        || cu.toString().contains("MqttClient")
                        || cu.toString().contains("IMqttClient")
                        || cu.toString().contains("MqttMessage");
                if (!usesPaho) continue;

                final Map<String, String> stringConsts = collectStaticFinalStringConstants(cu);
                final Map<String, String> intConsts = collectStaticFinalIntConstants(cu);
                final String moduleName = module.getName();
                final File rootRef = root;
                final File jfRef = jf;

                new VoidVisitorAdapter<Void>() {
                    @Override public void visit(MethodCallExpr m, Void arg) {
                        super.visit(m, arg);

                        String name = m.getNameAsString();
                        if (!(name.equals("publish") || name.equals("subscribe") || name.equals("setRetained"))) return;

                        int lineNo = m.getBegin().map(p -> p.line).orElse(-1);
                        String kind;
                        String topic = "?";
                        String qos = "?";
                        String retained = "?";

                        if (name.equals("publish")) {
                            kind = "PUBLISH";
                            // topic: arg #0
                            if (m.getArguments().size() >= 1) {
                                topic = resolveTopicArg(m.getArgument(0), stringConsts);
                            }
                            if (m.getArguments().size() == 2) {
                                // publish(String, MqttMessage)
                                Expression msgArg = m.getArgument(1);
                                InferredMsgSettings s = inferMsgSettingsFromSameMethod(m, msgArg);
                                if (s.qos != null) qos = qosWithMeaning(s.qos);
                                if (s.retained != null) retained = retainedWithMeaning(s.retained);
                            } else {
                                // publish(String, byte[], int qos, boolean retained)
                                if (m.getArguments().size() >= 3) {
                                    qos = qosWithMeaning(resolveQos(m.getArgument(2), stringConsts, intConsts));
                                }
                                if (m.getArguments().size() >= 4) {
                                    retained = retainedWithMeaning(resolveRetained(m.getArgument(3)));
                                }
                            }
                        } else if (name.equals("subscribe")) {
                            kind = "SUBSCRIBE";
                            // subscribe(String topic, int qos) OR subscribe(String[] topics, int[] qos)
                            if (m.getArguments().size() >= 1) {
                                topic = resolveTopicArg(m.getArgument(0), stringConsts);
                            }
                            if (m.getArguments().size() >= 2) {
                                Expression qosArg = m.getArgument(1);
                                if (qosArg.isArrayCreationExpr() || qosArg.isArrayInitializerExpr()) {
                                    List<String> qosItems = resolveQosList(qosArg, stringConsts, intConsts)
                                            .stream().map(StaticCodeScanner::qosWithMeaning).collect(Collectors.toList());
                                    qos = String.join(" | ", qosItems);
                                } else {
                                    qos = qosWithMeaning(resolveQos(qosArg, stringConsts, intConsts));
                                }
                            }
                        } else {
                            // setRetained(true/false) on MqttMessage
                            kind = "SET_RETAINED";
                            if (m.getArguments().size() >= 1) {
                                retained = retainedWithMeaning(resolveRetained(m.getArgument(0)));
                            }
                        }

                        hits.add(new Hit(
                                moduleName,
                                rel(rootRef, jfRef),
                                String.valueOf(lineNo),
                                kind,
                                topic,
                                qos,
                                retained
                        ));
                    }
                }.visit(cu, null);
            }
        }

        // CSV
        System.out.println("=== CSV ===");
        System.out.println("Project,File,Line,Kind,Topic,QoS,Retained");
        for (Hit h : hits) {
            System.out.printf("%s,%s,%s,%s,%s,%s,%s%n",
                    h.project(), h.file(), h.line(), h.kind(),
                    sanitize(h.topic()), sanitize(h.qos()), sanitize(h.retained()));
        }

        // Markdown
        System.out.println();
        System.out.println("=== Markdown ===");
        final String md;
        if (hits.isEmpty()) {
            md = "_No MQTT publish/subscribe calls found._\n";
            System.out.print(md);
        } else {
            md = getMarkdownTable(hits);
            System.out.print(md);
        }

        // Save markdown
        Path outFile = root.toPath().resolve("scan-report.md");
        try (BufferedWriter writer = Files.newBufferedWriter(outFile)) {
            writer.write(md);
        }
        System.out.println("\nMarkdown report saved to: " + outFile);
    }

    // --------- Intra-method inference for publish(String, MqttMessage) ---------

    private static class InferredMsgSettings {
        String qos;      // "0" | "1" | "2"
        String retained; // "true" | "false"
    }

    private static InferredMsgSettings inferMsgSettingsFromSameMethod(MethodCallExpr publishCall, Expression msgArg) {
        InferredMsgSettings s = new InferredMsgSettings();

        if (!msgArg.isNameExpr()) return s; // only simple var names supported
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
                .collect(Collectors.toList());

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

    // --------- Module discovery ---------

    private static List<File> discoverMavenModules(Path root) throws IOException {
        List<File> modules = new ArrayList<>();
        Files.walk(root)
                .filter(p -> p.getFileName().toString().equals("pom.xml"))
                .forEach(pom -> {
                    File dir = pom.getParent().toFile();
                    if (new File(dir, "src").isDirectory()) {
                        modules.add(dir);
                    }
                });
        return modules.stream()
                .distinct()
                .sorted(Comparator.comparing(File::getAbsolutePath))
                .collect(Collectors.toList());
    }

    // --------- Constant collectors ---------

    private static Map<String, String> collectStaticFinalStringConstants(CompilationUnit cu) {
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

    private static Map<String, String> collectStaticFinalIntConstants(CompilationUnit cu) {
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

    // --------- Resolvers ---------

    private static String resolveTopicArg(Expression expr, Map<String, String> stringConsts) {
        if (expr.isArrayCreationExpr()) {
            ArrayCreationExpr ac = expr.asArrayCreationExpr();
            return ac.getInitializer().map(init ->
                    init.getValues().stream()
                            .map(e -> resolveString(e, stringConsts))
                            .collect(Collectors.joining(" | "))
            ).orElse(expr.toString());
        }
        if (expr.isArrayInitializerExpr()) {
            ArrayInitializerExpr ai = expr.asArrayInitializerExpr();
            return ai.getValues().stream()
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

        if (expr.isBinaryExpr()) {
            BinaryExpr be = expr.asBinaryExpr();
            if (be.getOperator() == BinaryExpr.Operator.PLUS) {
                return resolveString(be.getLeft(), stringConsts)
                        + resolveString(be.getRight(), stringConsts);
            }
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
            ArrayCreationExpr ac = expr.asArrayCreationExpr();
            ac.getInitializer().ifPresent(init -> init.getValues().forEach(v -> out.add(resolveQos(v, stringConsts, intConsts))));
            return out;
        }
        if (expr.isArrayInitializerExpr()) {
            ArrayInitializerExpr ai = expr.asArrayInitializerExpr();
            ai.getValues().forEach(v -> out.add(resolveQos(v, stringConsts, intConsts)));
            return out;
        }
        out.add(resolveQos(expr, stringConsts, intConsts));
        return out;
    }

    private static String resolveRetained(Expression expr) {
        if (expr.isBooleanLiteralExpr()) return String.valueOf(expr.asBooleanLiteralExpr().getValue());
        return expr.toString();
    }

    // --------- Presentation helpers ---------

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
                .relativize(f.toPath().toAbsolutePath().normalize())
                .toString();
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n|,]+", " ").trim();
    }

    private static String getMarkdownTable(List<Hit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("| Project | File | Line | Kind | Topic | QoS | Retained |\n");
        sb.append("|---|---|---:|---|---|---:|---|\n");
        for (Hit h : hits) {
            sb.append("| ")
              .append(escapeMd(h.project())).append(" | ")
              .append(escapeMd(h.file())).append(" | ")
              .append(h.line()).append(" | ")
              .append(h.kind()).append(" | ")
              .append(escapeMd(h.topic())).append(" | ")
              .append(escapeMd(h.qos())).append(" | ")
              .append(escapeMd(h.retained())).append(" |\n");
        }
        return sb.toString();
    }

    private static String escapeMd(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", " ").trim();
    }
}
