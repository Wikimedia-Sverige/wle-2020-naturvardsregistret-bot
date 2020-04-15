# Naturvårdsregistret Wikidata/Wikimedia Commons synchronization bot

This is a project originally written to support Wiki Loves Earth 2020. It is
a bot that compares data downloaded from the Naturvårdsverket 
(Swedish Environmetal Protection Agency) database Naturvårdsregistret (NVR) 
with Wikidata for strutured data and and Wikimedia Commons for geoshapes to
create or update entities that differs from NVR.

## The code

* Java 11, Maven.
* wdtk-wikibaseapi for Wikidata, plus homegrown code for SPARQL.
* jwbf for MediaWiki.
* jts for geodata processing.

Keeps track of progress state. If you abort the bot, it will only process items
previously already processed only if the previously failed. 

Statistics is kept in the state, with specific information about each entity.

## Required environment variables

```
mwse-bot.username="Your bots WikiMedia username"
mwse-bot.password="Your bots WikiMedia password"
mwse-bot.email="Contact email address for this bot"
```

### External data sources

* biosf https://metadatakatalogen.naturvardsverket.se/metadatakatalogen/GetMetaDataById?id=bc2ce857-fa87-42f6-8870-fbdc3a9b113e
* npark https://metadatakatalogen.naturvardsverket.se/metadatakatalogen/GetMetaDataById?id=bfc33845-ffb9-4835-8355-76af3773d4e0
* nminn https://metadatakatalogen.naturvardsverket.se/metadatakatalogen/GetMetaDataById?id=c6b02e88-8084-4b3f-8a7d-33e5d45349c4
* nrese https://metadatakatalogen.naturvardsverket.se/metadatakatalogen/GetMetaDataById?id=2921b01a-0baf-4702-a89f-9c5626c97844

See http://mdp.vic-metria.nu/miljodataportalen/GetMetaDataById?UUID=8df63b07-46e5-45bd-aa06-3f43248617a3 for CC0.

When updating data, make sure to also update download and publish dates in 
the bot source code!

In the future we might want to consider downloading the original shape files
from these sources, reproject to EPSG:4326 and convert to GeoJSON. This bot
could then be executed automatically ever so often.

### Important notices

* Updating multi point coordinate WikiData item claims, e.g. natural monument
points. Currently not a problem as they currently do not exist in WikiData and
will thus only be added.

* Updating categories at commons geoshape discussion page will overwrite any
categories added by third parties. This is OK for now since we only add, but
for future imports categories needs to be parsed and checked for delta!

* All items created by the bot prior to 2020-04-10 is missing description!
* Almost all items created by the bot prior to 2020-04-10 is missing labels!
