@echo off
set "JAVA_HOME=C:\Program Files\Unity\Hub\Editor\6000.3.10f1\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK"
set "PATH=%JAVA_HOME%\bin;%PATH%"
gradlew.bat %*
