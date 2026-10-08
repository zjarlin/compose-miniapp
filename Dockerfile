FROM eclipse-temurin:17-jre
WORKDIR /app
COPY server/out/classes/ /app/classes/
EXPOSE 18092
CMD ["java", "-cp", "/app/classes", "com.addzero.miniapp.MiniAppServer"]
