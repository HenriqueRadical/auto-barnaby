@echo off
del /q *.jar

echo Compiling...
javac AutoBarnaby.java

echo Creating jar...
jar cfm AutoBarnaby.jar Manifest.txt *.class

del /q *.class