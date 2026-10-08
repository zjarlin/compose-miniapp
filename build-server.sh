#!/bin/bash
# 编译并测试小程序配套后端。默认使用 Java 17+；服务本身不依赖第三方库。
set -e
cd "$(dirname "$0")"

rm -rf server/out/classes
mkdir -p server/out/classes

javac --release 17 -encoding UTF-8 \
  -d server/out/classes \
  server/src/main/java/com/addzero/miniapp/*.java

# 测试类独立编译为包内可见，避免测试类污染服务主包。
javac --release 17 -encoding UTF-8 \
  -cp server/out/classes \
  -d server/out/test-classes \
  server/test/MiniAppServerTest.java

java -cp server/out/classes:server/out/test-classes com.addzero.miniapp.MiniAppServerTest
echo "✔ 支付/订单服务构建与自测通过：server/out/classes"
