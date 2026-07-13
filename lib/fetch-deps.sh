#!/usr/bin/env bash
# Download runtime dependencies from Maven Central.
cd "$(dirname "$0")"
curl -sLO https://repo1.maven.org/maven2/org/jetbrains/pty4j/pty4j/0.13.4/pty4j-0.13.4.jar
curl -sLO https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar
curl -sLO https://repo1.maven.org/maven2/net/java/dev/jna/jna-platform/5.14.0/jna-platform-5.14.0.jar
curl -sLO https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar
curl -sLO https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar
curl -sLO https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/2.0.13/slf4j-nop-2.0.13.jar
