#!/bin/bash

# maven3 is required
# sudo apt install maven

# java 8+ is required
# sudo apt install openjdk-13-jdk

mvn clean install -Dtest=false -DfailIfNoTests=false
