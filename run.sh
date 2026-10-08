#!/bin/bash
# compose-miniapp 一键构建与转译（kotlinc 工具链，无 Gradle）
set -e
cd "$(dirname "$0")"

# 1. 编译转译器
./build.sh

# 2. 转译旧 DSL sample（sample-app）→ out/miniapp
echo "── 转译 sample-app（类 Compose DSL 写法）──"
java -jar out/transpiler.jar sample-app/src/pages out/miniapp

# 3. 转译标准 Compose sample（sample-compose）→ out/miniapp-compose
echo "── 转译 sample-compose（标准 Compose 写法）──"
java -jar out/transpiler.jar sample-compose/src/pages out/miniapp-compose

# 4. 转译美团风格外卖示例（sample-meituan）→ out/miniapp-meituan
echo "── 转译 sample-meituan（外卖业务示例）──"
java -jar out/transpiler.jar sample-meituan/src/pages out/miniapp-meituan miniApp/src/miniAppMain

# 5. 反例测试
echo "── 反例测试 ──"
kotlinc $(ls compiler/src/*.kt | grep -v Main.kt) tests/NegativeTest.kt -include-runtime -d out/negative-test.jar
java -jar out/negative-test.jar

echo
echo "完成：out/miniapp、out/miniapp-compose 与 out/miniapp-meituan 均已生成"
