FROM eclipse-temurin:17-jre
WORKDIR /app
COPY server/out/classes/ /app/classes/
COPY server/out/lib/ /app/lib/
EXPOSE 18092
CMD ["java", "-cp", "/app/classes:/app/lib/*", "com.addzero.miniapp.MiniAppServer"]
