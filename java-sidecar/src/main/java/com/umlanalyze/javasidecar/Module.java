package com.umlanalyze.javasidecar;

import java.nio.file.Path;
import java.util.List;

/** 一个被分析的项目模块：名称、物理目录与源码目录。 */
record Module(String name, Path path, List<Path> sourceDirs) {
}
