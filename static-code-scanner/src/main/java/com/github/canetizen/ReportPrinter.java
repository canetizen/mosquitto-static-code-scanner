/*
 * Author: canetizen
 * Created on Mon Aug 11 2025
 * Description:
 *  Handles printing CSV and Markdown reports for MQTT method call scan results.
 */

package com.github.canetizen;

import java.util.List;

public class ReportPrinter {

    public static void printCsv(List<StaticCodeScanner.Hit> hits) {
        System.out.println("=== CSV ===");
        System.out.println("Project,File,Line,Kind,Topic,QoS,Retained");
        for (StaticCodeScanner.Hit h : hits) {
            System.out.printf("%s,%s,%s,%s,%s,%s,%s%n",
                    h.project(), h.file(), h.line(), h.kind(),
                    sanitize(h.topic()), sanitize(h.qos()), sanitize(h.retained()));
        }
    }

    public static String printMarkdown(List<StaticCodeScanner.Hit> hits) {
        System.out.println("\n=== Markdown ===");
        if (hits.isEmpty()) {
            String md = "_No MQTT publish/subscribe calls found._\n";
            System.out.print(md);
            return md;
        }
        String md = getMarkdownTable(hits);
        System.out.print(md);
        return md;
    }

    private static String getMarkdownTable(List<StaticCodeScanner.Hit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("| Project | File | Line | Kind | Topic | QoS | Retained |\n");
        sb.append("|---|---|---:|---|---|---:|---|\n");
        for (StaticCodeScanner.Hit h : hits) {
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

    private static String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n|,]+", " ").trim();
    }

    private static String escapeMd(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", " ").trim();
    }
}