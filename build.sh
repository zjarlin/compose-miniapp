#!/bin/bash
# 编译转译器（kotlinc 工具链，不用 Gradle）
set -e
cd "$(dirname "$0")"
mkdir -p out
kotlinc compiler/src/*.kt -include-runtime -d out/transpiler.jar
echo "✔ 转译器编译完成：out/transpiler.jar"
