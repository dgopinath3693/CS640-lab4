# 'make'
# for all .java files in sibling src/, compile .class files also in src/
default: 
	javac -d src -cp src src/*.java

# 'make clean' to delete old .class files
clean:
	rm -f src/*.class