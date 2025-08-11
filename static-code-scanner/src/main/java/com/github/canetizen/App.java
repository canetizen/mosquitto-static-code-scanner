/*
 * Author: canetizen
 * Created on Mon Aug 11 2025
 * Description:
 *  Application entry point. Orchestrates module discovery, scanning, and reporting.
 */

package com.github.canetizen;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class App {
    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".");
        if (!root.isDirectory()) {
            System.err.println("Root is not a directory: " + root.getAbsolutePath());
            System.exit(1);
        }

        List<File> modules = ScannerImpl.discoverMavenModules(root.toPath());
        if (modules.isEmpty()) modules = List.of(root);

        StaticCodeScanner scanner = new StaticCodeScanner();
        List<StaticCodeScanner.Hit> hits = new ArrayList<>();

        for (File module : modules) {
            scanner.scanModule(root, module, hits);
        }

        ReportPrinter.printCsv(hits);
        String md = ReportPrinter.printMarkdown(hits);

        // Save markdown
        Path outFile = root.toPath().resolve("scan-report.md");
        try (BufferedWriter writer = Files.newBufferedWriter(outFile)) {
            writer.write(md);
        }
        System.out.println("\nMarkdown report saved to: " + outFile);
    }
}