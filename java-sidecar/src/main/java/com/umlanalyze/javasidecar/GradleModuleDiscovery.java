package com.umlanalyze.javasidecar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gradle 多模块结构发现。
 *
 * <p>当前实现解析 {@code settings.gradle(.kts)} 的 {@code include(...)} 语句，覆盖标准多模块场景。
 * 复杂场景（条件 include、buildSrc、projectDir 覆盖）应升级为 Gradle Tooling API（见 docs/spec.md §4.2）。
 * 非 Gradle 项目回退为单模块源码扫描。</p>
 */
final class GradleModuleDiscovery {

    private static final Pattern INCLUDE = Pattern.compile("include\\s*\\(?([^)\\n]*)\\)?");
    private static final Pattern QUOTED = Pattern.compile("['\"]([^'\"]+)['\"]");

    private GradleModuleDiscovery() {
    }

    static List<Module> discover(Path root) {
        List<Module> modules = new ArrayList<>();
        Path settings = findSettings(root);
        if (settings != null) {
            // 多模块：根项目若无 src/main/java 则视为容器，不扫描其子目录，避免重复抽取
            Path rootSrc = root.resolve("src").resolve("main").resolve("java");
            List<Path> rootSrcDirs = Files.isDirectory(rootSrc) ? List.of(rootSrc) : List.of();
            modules.add(new Module(nameOf(root), root, rootSrcDirs));

            for (String include : parseIncludes(settings)) {
                Path dir = root.resolve(include).normalize();
                if (Files.isDirectory(dir)) {
                    modules.add(moduleFor(dir, include.replace('/', ':')));
                }
            }
        } else {
            modules.add(moduleFor(root, nameOf(root)));
        }
        return modules;
    }

    private static String nameOf(Path root) {
        Path name = root.getFileName();
        return name == null ? "default" : name.toString();
    }

    private static Module moduleFor(Path dir, String name) {
        Path standard = dir.resolve("src").resolve("main").resolve("java");
        List<Path> srcDirs = Files.isDirectory(standard) ? List.of(standard) : List.of(dir);
        return new Module(name, dir, srcDirs);
    }

    private static Path findSettings(Path root) {
        for (String name : List.of("settings.gradle", "settings.gradle.kts")) {
            Path p = root.resolve(name);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    private static List<String> parseIncludes(Path settings) {
        try {
            String content = Files.readString(settings);
            List<String> result = new ArrayList<>();
            Matcher m = INCLUDE.matcher(content);
            while (m.find()) {
                Matcher q = QUOTED.matcher(m.group(1));
                while (q.find()) {
                    result.add(q.group(1).replace(':', '/'));
                }
            }
            return result;
        } catch (IOException e) {
            return List.of();
        }
    }
}
