#!/bin/bash
# 编译并测试小程序配套后端。默认使用 Java 17+，Gson 负责请求 JSON 解析。
set -e
cd "$(dirname "$0")"

rm -rf server/out/classes
mkdir -p server/out/classes
mkdir -p server/out/lib
GSON=server/out/lib/gson-2.13.2.jar
if [ ! -f "$GSON" ]; then
  curl -fsSL https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar -o "$GSON"
fi
echo "dd0ce1b55a3ed2080cb70f9c655850cda86c206862310009dcb5e5c95265a5e0  $GSON" | shasum -a 256 -c -

javac --release 17 -encoding UTF-8 \
  -cp "$GSON" \
  -d server/out/classes \
  server/src/main/java/com/addzero/miniapp/*.java

# 测试类独立编译为包内可见，避免测试类污染服务主包。
javac --release 17 -encoding UTF-8 \
  -cp "server/out/classes:$GSON" \
  -d server/out/test-classes \
  server/test/MiniAppServerTest.java

java -cp "server/out/classes:server/out/test-classes:$GSON" com.addzero.miniapp.MiniAppServerTest
echo "✔ 支付/订单服务构建与自测通过：server/out/classes"
