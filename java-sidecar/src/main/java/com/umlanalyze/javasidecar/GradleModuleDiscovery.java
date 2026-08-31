package com.umlanalyze.javasidecar;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gradle 多模块结构发现。
 *
 * <p>首选 Gradle Tooling API（能正确处理 projectDir/buildFileName 覆盖、条件 include、buildSrc 等）；
 * 失败时回退到解析 {@code settings.gradle(.kts)} 的 {@code include(...)} 语句；非 Gradle 项目回退单模块扫描。</p>
 */
final class GradleModuleDiscovery {

    private static final Pattern INCLUDE = Pattern.compile("include\\s*\\(?([^)\\n]*)\\)?");
    private static final Pattern QUOTED = Pattern.compile("['\"]([^'\"]+)['\"]");

    private GradleModuleDiscovery() {
    }

    static List<Module> discover(Path root) {
        if (!isGradleProject(root)) {
            return List.of(moduleFor(root, nameOf(root), true));
        }

        List<Module> viaToolingApi = tryToolingApi(root);
        if (viaToolingApi != null) {
            return viaToolingApi;
        }
        return discoverFromSettings(root);
    }

    /** Tooling API 会启动目标项目的 Gradle daemon，可能较慢；加超时，失败/超时回退 settings.gradle 解析。 */
    private static List<Module> tryToolingApi(Path root) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<Module>> future = executor.submit(() -> {
                GradleConnector connector = GradleConnector.newConnector()
                        .forProjectDirectory(root.toFile());
                // 目标项目无 wrapper 时，Tooling API 默认会联网下载发行版；改为用本地 Gradle 安装
                String gradleHome = System.getProperty("gradle.home");
                if (gradleHome != null && !gradleHome.isEmpty()) {
                    connector.useInstallation(new File(gradleHome));
                }
                try (ProjectConnection conn = connector.connect()) {
                    GradleProject project = conn.getModel(GradleProject.class);
                    List<Module> modules = new ArrayList<>();
                    collect(project, modules);
                    return modules;
                }
            });
            return future.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.err.println("Gradle Tooling API 超时，回退 settings.gradle 解析");
            return null;
        } catch (Exception e) {
            System.err.println("Gradle Tooling API 失败，回退 settings.gradle 解析: " + e.getMessage());
            return null;
        } finally {
            executor.shutdownNow();
        }
    }

    /** 递归收集模块树；根容器无 src/main/java 时不扫描其子目录，避免重复抽取。 */
    private static void collect(GradleProject project, List<Module> modules) {
        Path dir = project.getProjectDirectory().toPath();
        Path standard = dir.resolve("src").resolve("main").resolve("java");
        boolean hasChildren = !project.getChildren().isEmpty();
        List<Path> srcDirs;
        if (Files.isDirectory(standard)) {
            srcDirs = List.of(standard);
        } else if (!hasChildren) {
            srcDirs = List.of(dir); // 叶子模块且无标准目录 → 扫描自身
        } else {
            srcDirs = List.of(); // 容器根 → 不扫描，避免重复
        }
        modules.add(new Module(projectPathName(project), dir, srcDirs));
        for (GradleProject child : project.getChildren()) {
            collect(child, modules);
        }
    }

    /** 项目路径作为模块名（如 services:api），保证跨大项目唯一；根项目用其 name。 */
    private static String projectPathName(GradleProject project) {
        String path = project.getPath();
        if (path == null || path.isEmpty() || ":".equals(path)) {
            return project.getName();
        }
        return path.startsWith(":") ? path.substring(1) : path;
    }

    private static List<Module> discoverFromSettings(Path root) {
        List<Module> modules = new ArrayList<>();
        Path rootSrc = root.resolve("src").resolve("main").resolve("java");
        modules.add(new Module(nameOf(root), root,
                Files.isDirectory(rootSrc) ? List.of(rootSrc) : List.of()));
        Path settings = findSettings(root);
        if (settings != null) {
            for (String include : parseIncludes(settings)) {
                Path dir = root.resolve(include).normalize();
                if (Files.isDirectory(dir)) {
                    modules.add(moduleFor(dir, include.replace('/', ':'), true));
                }
            }
        }
        return modules;
    }

    private static boolean isGradleProject(Path root) {
        return Files.exists(root.resolve("settings.gradle"))
                || Files.exists(root.resolve("settings.gradle.kts"))
                || Files.exists(root.resolve("build.gradle"))
                || Files.exists(root.resolve("build.gradle.kts"));
    }

    private static String nameOf(Path root) {
        Path name = root.getFileName();
        return name == null ? "default" : name.toString();
    }

    private static Module moduleFor(Path dir, String name, boolean fallbackToDir) {
        Path standard = dir.resolve("src").resolve("main").resolve("java");
        List<Path> srcDirs = Files.isDirectory(standard)
                ? List.of(standard)
                : (fallbackToDir ? List.of(dir) : List.of());
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
