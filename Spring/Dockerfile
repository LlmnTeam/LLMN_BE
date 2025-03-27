# Gradle 7.6 및 JDK 17을 기반으로 하는 이미지를 사용하여 빌드 단계 설정
FROM gradle:7.6-jdk17 AS builder

# 작업 디렉토리를 /project로 설정
WORKDIR /project

# build.gradle 및 settings.gradle 파일을 컨테이너의 /project 디렉토리로 복사
COPY build.gradle settings.gradle /project/

# 초기 빌드를 수행하여 설정 오류나 종속성 문제를 빠르게 확인
RUN gradle build --no-daemon --parallel --info || return 0

# 나머지 프로젝트 파일을 컨테이너의 현재 작업 디렉토리(/project)로 복사
COPY . .

# 프로젝트를 깨끗하게 초기화하고 테스트 단계를 제외한 빌드 실행
RUN gradle clean build -x test

# OpenJDK 17 슬림 버전을 기반으로 하는 런타임 단계 설정
FROM openjdk:17-jdk-slim AS runtime

# 빌드 단계에서 생성된 JAR 파일을 런타임 단계의 /app 디렉토리로 복사
COPY --from=builder /project/build/libs/llm-ops-0.0.1-SNAPSHOT.jar /app/llm-ops-0.0.1-SNAPSHOT.jar

# 컨테이너가 시작될 때 JAR 파일을 실행하도록 설정
CMD ["java", "-jar", "-Duser.timezone=Asia/Seoul", "/app/llm-ops-0.0.1-SNAPSHOT.jar"]