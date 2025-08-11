/*
 * Author: canetizen
 * Created on Mon Aug 11 2025
 * Description:
 *  Core scanning logic for detecting Eclipse Paho MQTT publish/subscribe calls.
 *  Contains the Hit record model and the method to scan given root/modules.
 */

package com.github.canetizen;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.util.*;

public class StaticCodeScanner {

    /** One row in the report. */
    public record Hit(String project, String file, String line, String kind, String topic, String qos, String retained) {}

    /**
     * Scans given module and appends results into hits.
     */
    public void scanModule(File root, File module, List<Hit> hits) throws Exception {
        File src = new File(module, "src");
        File scanRoot = (src.exists() && src.isDirectory()) ? src : module;

        Collection<File> javaFiles = FileUtils.listFiles(scanRoot, new String[]{"java"}, true);
        for (File jf : javaFiles) {
            CompilationUnit cu;
            try {
                cu = StaticJavaParser.parse(jf);
            } catch (Exception ex) {
                continue;
            }
            if (!ScannerUtils.usesPaho(cu)) continue;

            Map<String, String> stringConsts = ScannerUtils.collectStaticFinalStringConstants(cu);
            Map<String, String> intConsts = ScannerUtils.collectStaticFinalIntConstants(cu);

            new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(MethodCallExpr m, Void arg) {
                    super.visit(m, arg);
                    Hit hit = ScannerUtils.analyzeMethodCall(m, root, module, jf, stringConsts, intConsts);
                    if (hit != null) {
                        hits.add(hit);
                    }
                }
            }.visit(cu, null);
        }
    }
}