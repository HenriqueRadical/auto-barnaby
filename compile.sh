rm *.jar

echo Compiling...
javac AutoBarnaby.java

echo Creating jar...
jar cfm AutoBarnaby.jar Manifest.txt *.class

rm *.class