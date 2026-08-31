package com.umlanalyze.javasidecar;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("用法: <项目根路径>");
            System.exit(1);
        }
        Path root = Paths.get(args[0]).toAbsolutePath().normalize();
        System.out.println(new JavaAnalyzer().analyze(root));
    }
}
