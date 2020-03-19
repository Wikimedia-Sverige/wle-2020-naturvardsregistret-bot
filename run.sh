#!/bin/bash

mwse-bot.username="Your bots WikiMedia username"
mwse-bot.password="Your bots WikiMedia password"
mwse-bot.email="Contact email address for this bot"

mvn exec:java -Dexec.mainClass="se.wikimedia.wle.naturvardsverket.Main"