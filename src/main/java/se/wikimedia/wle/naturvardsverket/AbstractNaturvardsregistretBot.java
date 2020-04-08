package se.wikimedia.wle.naturvardsverket;

import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.Getter;
import org.geojson.Feature;
import org.geojson.FeatureCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikidata.wdtk.datamodel.helpers.ItemDocumentBuilder;
import org.wikidata.wdtk.datamodel.helpers.ReferenceBuilder;
import org.wikidata.wdtk.datamodel.helpers.StatementBuilder;
import org.wikidata.wdtk.datamodel.implementation.QuantityValueImpl;
import org.wikidata.wdtk.datamodel.implementation.StringValueImpl;
import org.wikidata.wdtk.datamodel.implementation.ValueSnakImpl;
import org.wikidata.wdtk.datamodel.interfaces.*;
import org.wikidata.wdtk.wikibaseapi.apierrors.MediaWikiApiErrorException;

import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public abstract class AbstractNaturvardsregistretBot extends AbstractBot {

  private Logger log = LoggerFactory.getLogger(getClass());


  @Getter
  private Progress.Entity progressEntity;

  public AbstractNaturvardsregistretBot() {
    super("Naturvardsregistret_bot", "0.1");
  }

  private DateTimeFormatter featureValueDateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd");

  public abstract String commonGeoshapeArticleNameFactory(NaturvardsregistretObject object);

  /**
   * Files to be processed
   */
  protected abstract File[] getNaturvardsregistretGeoJsonFiles();

  /**
   * Q describing e.g. nature reserve, national park, etc
   */
  protected abstract String getNaturvardsregistretObjectTypeEntityId();

  public abstract String[] getCommonsArticleCategories(NaturvardsregistretObject object);

  /**
   * E.g. https://metadatakatalogen.naturvardsverket.se/metadatakatalogen/GetMetaDataById?id=2921b01a-0baf-4702-a89f-9c5626c97844
   */
  protected abstract String getNaturvardsregistretObjectTypeSourceUrl();

  private EntityDocument naturvardsregistretEntityType;

  protected EntityDocument getNaturvardsregistretObjectTypeEntityDocument() throws MediaWikiApiErrorException, IOException {
    if (naturvardsregistretEntityType == null) {
      naturvardsregistretEntityType = getWikiData().getEntityDocument(getNaturvardsregistretObjectTypeEntityId(), true);
      if (naturvardsregistretEntityType == null) {
        naturvardsregistretEntityType = WikiData.NULL_ENTITY;
      }
    }
    return naturvardsregistretEntityType == WikiData.NULL_ENTITY ? null : naturvardsregistretEntityType;
  }


  @Override
  protected void execute() throws Exception {

    initializeWikiData();

    for (File file : getNaturvardsregistretGeoJsonFiles()) {
      log.info("Processing {}", file.getAbsolutePath());
      FeatureCollection featureCollection = getObjectMapper().readValue(file, FeatureCollection.class);

      log.info("Ensure that we are aware of all WikiData operator references");
      for (Feature feature : featureCollection.getFeatures()) {
        String operator = (String) feature.getProperties().get("FORVALTARE");
        if (operatorsByNvrProperty.get(operator) == null) {
          String operatorId = wikiData.findSingleObjectByUniqueLabel(operator, "sv");
          if (operatorId != null) {
            operatorsByNvrProperty.put(operator, getWikiData().getEntityIdValue(operatorId, true));
            log.info("Operator '{}' was resolved using unique label at WikiData as {}", operator, operatorId);
          } else {
            log.warn("Operator '{}' is an unknown WikiData object for us. The NVRID using this will not be handled in regard with operator claims.", operator);
          }
        }
      }

      Progress progress;
      File progressBackupFile = new File("data/progress/" + getClass().getSimpleName() + ".backup.1.json");
      File progressFile = new File("data/progress/" + getClass().getSimpleName() + ".json");
      if (progressFile.exists()) {
        progress = getObjectMapper().readValue(progressFile, Progress.class);
        log.info("Loaded progress from {} with {} previously processed items.", progressFile.getAbsolutePath(), progress.getProcessed().size());
      } else {
        progress = new Progress();
      }

      log.info("Processing entities...");
      for (Feature feature : featureCollection.getFeatures()) {
        // filter out null value properties
        feature.getProperties().entrySet().removeIf(property -> property.getValue() == null);

        String nvrid = (String) feature.getProperty("NVRID");
        if (nvrid == null) {
          log.error("NVRID missing in {}", getObjectMapper().writeValueAsString(feature));
          continue;
        }

        Progress.Entity previousExecution = progress.getProcessed().get(nvrid);
        if (previousExecution != null && previousExecution.getError() == null) {
          log.info("{} is listed in progress without error. Will be skipped", nvrid);
        } else {
          // process
          try {
            progressEntity = new Progress.Entity();
            progressEntity.setPreviousExecution(previousExecution);
            progressEntity.setEpochStarted(System.currentTimeMillis());
            progressEntity.setNvrid(nvrid);
            process(feature);
          } catch (Exception e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            pw.flush();
            progressEntity.setError(sw.toString());
            log.error("Caught exception", e);
          }
          progressEntity.setEpochEnded(System.currentTimeMillis());
          progress.add(progressEntity);
          progressBackupFile.delete();
          progressFile.renameTo(progressBackupFile);
          getObjectMapper().writeValue(progressFile, progress); // todo rotate log file, keep a few in case of crash while saving!
          System.currentTimeMillis();
        }
      }

    }

  }

  private Map<String, EntityIdValue> iuncCategories = new HashMap<>();


  private Map<String, EntityIdValue> operatorsByNvrProperty = new HashMap<>();


  private void initializeWikiData() throws MediaWikiApiErrorException, IOException {
    log.info("Initializing WikiData properties...");

    getWikiData().getNamedEntities().put("instance of", getWikiData().getEntityIdValue("P31"));

    getWikiData().getNamedEntities().put("inception date", getWikiData().getEntityIdValue("P571"));

    getWikiData().getNamedEntities().put("IUCN protected areas category", getWikiData().getEntityIdValue("P814"));


    // , Områden som ej kan klassificeras enligt IUCN: s system.
    iuncCategories.put("0", WikiData.NULL_ENTITY_VALUE);
    // , Strikt naturreservat (Strict Nature Reserve)
    iuncCategories.put("IA", getWikiData().getEntityIdValue("Q14545608"));
    // , Vildmarksområde (Wilderness Area)
    iuncCategories.put("IB", getWikiData().getEntityIdValue("Q14545620"));
    //, Nationalpark (National Park)
    iuncCategories.put("II", getWikiData().getEntityIdValue("Q14545628"));
    // , Naturmonument (Natural Monument)
    iuncCategories.put("III", getWikiData().getEntityIdValue("Q14545633"));
    //  Habitat/Artskyddsområde (Habitat/Species Management Area)
    iuncCategories.put("IV", getWikiData().getEntityIdValue("Q14545639"));
    // Skyddat landskap/havsområde (Protected Landscape/Seascape)
    iuncCategories.put("V", getWikiData().getEntityIdValue("Q14545646"));

    getWikiData().getNamedEntities().put("country", getWikiData().getEntityIdValue("P17"));
    getWikiData().getNamedEntities().put("Sweden", getWikiData().getEntityIdValue("Q34"));

    getWikiData().getNamedEntities().put("located in the administrative territorial entity", getWikiData().getEntityIdValue("P131"));


    getWikiData().getNamedEntities().put("coordinate location", getWikiData().getEntityIdValue("P625"));

    getWikiData().getNamedEntities().put("geoshape", getWikiData().getEntityIdValue("P3896"));

    getWikiData().getNamedEntities().put("operator", getWikiData().getEntityIdValue("P137"));

    getWikiData().getNamedEntities().put("hectare", getWikiData().getEntityIdValue("Q35852"));
    getWikiData().getNamedEntities().put("area", getWikiData().getEntityIdValue("P2046"));
    getWikiData().getNamedEntities().put("applies to part", getWikiData().getEntityIdValue("P518"));
    getWikiData().getNamedEntities().put("forest", getWikiData().getEntityIdValue("Q4421"));
    getWikiData().getNamedEntities().put("land", getWikiData().getEntityIdValue("Q11081619"));
    getWikiData().getNamedEntities().put("body of water", getWikiData().getEntityIdValue("Q15324"));

    getWikiData().getNamedEntities().put("nvrid", getWikiData().getEntityIdValue("P3613"));
    getWikiData().getNamedEntities().put("wdpaid", getWikiData().getEntityIdValue("P809"));

    getWikiData().getNamedEntities().put("reference URL", getWikiData().getEntityIdValue("P854"));
    getWikiData().getNamedEntities().put("stated in", getWikiData().getEntityIdValue("P248"));
    getWikiData().getNamedEntities().put("Protected Areas (Nature Reserves)", getWikiData().getEntityIdValue("Q29580583"));
    getWikiData().getNamedEntities().put("retrieved", getWikiData().getEntityIdValue("P813"));
    getWikiData().getNamedEntities().put("point in time", getWikiData().getEntityIdValue("P585"));
    getWikiData().getNamedEntities().put("publication date", getWikiData().getEntityIdValue("P577"));


    log.info("Loading operators...");
    {
      ArrayNode forvaltare = getObjectMapper().readValue(new File("data/forvaltare.json"), ArrayNode.class);
      for (int i = 0; i < forvaltare.size(); i++) {
        operatorsByNvrProperty.put(forvaltare.get(i).get("sv").textValue(), wikiData.getEntityIdValue(forvaltare.get(i).get("item").textValue()));
      }
    }
    {
      ArrayNode municipality = getObjectMapper().readValue(new File("data/municipalities.json"), ArrayNode.class);
      for (int i = 0; i < municipality.size(); i++) {
        operatorsByNvrProperty.put(municipality.get(i).get("sv").textValue(), wikiData.getEntityIdValue(municipality.get(i).get("item").textValue()));
      }
    }


  }

  private void process(Feature feature) throws Exception {

    String nvrid = (String) feature.getProperty("NVRID");
    log.info("Processing {}", nvrid);

    // create object
    NaturvardsregistretObject naturvardsregistretObject = new NaturvardsregistretObject();
    naturvardsregistretObject.setFeature(feature);

    // todo this need to be updated with new data!
    naturvardsregistretObject.setPublishedDate(LocalDate.parse("2020-02-25"));
    naturvardsregistretObject.setRetrievedDate(LocalDate.parse("2020-02-25"));

    naturvardsregistretObject.setNvrid(nvrid);
    naturvardsregistretObject.setName(feature.getProperty("NAMN"));


/*
    ███████╗██╗███╗   ██╗██████╗      ██████╗ ██████╗      ██████╗██████╗ ███████╗ █████╗ ████████╗███████╗
    ██╔════╝██║████╗  ██║██╔══██╗    ██╔═══██╗██╔══██╗    ██╔════╝██╔══██╗██╔════╝██╔══██╗╚══██╔══╝██╔════╝
    █████╗  ██║██╔██╗ ██║██║  ██║    ██║   ██║██████╔╝    ██║     ██████╔╝█████╗  ███████║   ██║   █████╗
    ██╔══╝  ██║██║╚██╗██║██║  ██║    ██║   ██║██╔══██╗    ██║     ██╔══██╗██╔══╝  ██╔══██║   ██║   ██╔══╝
    ██║     ██║██║ ╚████║██████╔╝    ╚██████╔╝██║  ██║    ╚██████╗██║  ██║███████╗██║  ██║   ██║   ███████╗
    ╚═╝     ╚═╝╚═╝  ╚═══╝╚═════╝      ╚═════╝ ╚═╝  ╚═╝     ╚═════╝╚═╝  ╚═╝╚══════╝╚═╝  ╚═╝   ╚═╝   ╚══════╝

    ██╗    ██╗██╗██╗  ██╗██╗██████╗  █████╗ ████████╗ █████╗     ██╗████████╗███████╗███╗   ███╗
    ██║    ██║██║██║ ██╔╝██║██╔══██╗██╔══██╗╚══██╔══╝██╔══██╗    ██║╚══██╔══╝██╔════╝████╗ ████║
    ██║ █╗ ██║██║█████╔╝ ██║██║  ██║███████║   ██║   ███████║    ██║   ██║   █████╗  ██╔████╔██║
    ██║███╗██║██║██╔═██╗ ██║██║  ██║██╔══██║   ██║   ██╔══██║    ██║   ██║   ██╔══╝  ██║╚██╔╝██║
    ╚███╔███╔╝██║██║  ██╗██║██████╔╝██║  ██║   ██║   ██║  ██║    ██║   ██║   ███████╗██║ ╚═╝ ██║
     ╚══╝╚══╝ ╚═╝╚═╝  ╚═╝╚═╝╚═════╝ ╚═╝  ╚═╝   ╚═╝   ╚═╝  ╚═╝    ╚═╝   ╚═╝   ╚══════╝╚═╝     ╚═╝
*/

    log.debug("Find unique WikiData item matching (instance of Naturvårdsregistret object type && Naturvårdsregistret object id).");
    String sparqlQuery = "SELECT ?item WHERE { ?item wdt:P3613 ?value. ?item wdt:P31 wd:" + getNaturvardsregistretObjectTypeEntityDocument().getEntityId().getId() + ". FILTER (?value IN (\""
        + naturvardsregistretObject.getNvrid() + "\")) SERVICE wikibase:label { bd:serviceParam wikibase:language \"[AUTO_LANGUAGE],en\". }} LIMIT 2";

    naturvardsregistretObject.setWikiDataObjectKey(wikiData.getSingleObject(sparqlQuery));

    if (naturvardsregistretObject.getWikiDataObjectKey() == null) {
      log.debug("Creating new WikiData item as there is none describing nvrid {}", naturvardsregistretObject.getNvrid());
      ItemDocumentBuilder builder = ItemDocumentBuilder.forItemId(ItemIdValue.NULL);
      builder.withStatement(
          addNaturvardsregistretReferences(naturvardsregistretObject, StatementBuilder
              .forSubjectAndProperty(ItemIdValue.NULL, getWikiData().property("instance of"))
              .withValue(wikiData.entity(getNaturvardsregistretObjectTypeEntityId()))
          ).build());
      builder.withStatement(
          addNaturvardsregistretReferences(naturvardsregistretObject, StatementBuilder
              .forSubjectAndProperty(ItemIdValue.NULL,
                  getWikiData().property("nvrid"))
              .withValue(new StringValueImpl(naturvardsregistretObject.getNvrid()))
          ).build());
      if (!isDryRun()) {
        naturvardsregistretObject.setWikiDataItem(getWikiData().getDataEditor().createItemDocument(
            builder.build(),
            "Created by bot from data supplied by Naturvårdsverket",
            null
        ));
        progressEntity.setCreatedWikidata(true);
        log.info("Committed new fairly empty item {} to WikiData", naturvardsregistretObject.getWikiDataItem().getEntityId().getId());
      } else {
        naturvardsregistretObject.setWikiDataItem(builder.build());
      }

    } else {
      log.debug("WikiData item {} is describing nvrid {}", naturvardsregistretObject.getWikiDataObjectKey(), naturvardsregistretObject.getNvrid());
      naturvardsregistretObject.setWikiDataItem((ItemDocument) getWikiData().getDataFetcher().getEntityDocument(naturvardsregistretObject.getWikiDataObjectKey()));
    }

    progressEntity.setWikidataIdentity(naturvardsregistretObject.getWikiDataObjectKey());

/*
    ███████╗██╗   ██╗ █████╗ ██╗     ██╗   ██╗ █████╗ ████████╗███████╗    ██████╗ ███████╗██╗  ████████╗ █████╗
    ██╔════╝██║   ██║██╔══██╗██║     ██║   ██║██╔══██╗╚══██╔══╝██╔════╝    ██╔══██╗██╔════╝██║  ╚══██╔══╝██╔══██╗
    █████╗  ██║   ██║███████║██║     ██║   ██║███████║   ██║   █████╗      ██║  ██║█████╗  ██║     ██║   ███████║
    ██╔══╝  ╚██╗ ██╔╝██╔══██║██║     ██║   ██║██╔══██║   ██║   ██╔══╝      ██║  ██║██╔══╝  ██║     ██║   ██╔══██║
    ███████╗ ╚████╔╝ ██║  ██║███████╗╚██████╔╝██║  ██║   ██║   ███████╗    ██████╔╝███████╗███████╗██║   ██║  ██║
    ╚══════╝  ╚═══╝  ╚═╝  ╚═╝╚══════╝ ╚═════╝ ╚═╝  ╚═╝   ╚═╝   ╚══════╝    ╚═════╝ ╚══════╝╚══════╝╚═╝   ╚═╝  ╚═╝
 */
    log.debug("Searching for delta between local data and WikiData item");

    List<Statement> addStatements = new ArrayList();
    List<Statement> deleteStatements = new ArrayList();

    // todo assert instance of nature reserve and nvrid. if not then fail!

    evaluateDelta(naturvardsregistretObject, addStatements, deleteStatements);


/*
     ██████╗ ██████╗ ███╗   ███╗███╗   ███╗██╗████████╗    ██╗    ██╗██╗██╗  ██╗██╗██████╗  █████╗ ████████╗ █████╗
    ██╔════╝██╔═══██╗████╗ ████║████╗ ████║██║╚══██╔══╝    ██║    ██║██║██║ ██╔╝██║██╔══██╗██╔══██╗╚══██╔══╝██╔══██╗
    ██║     ██║   ██║██╔████╔██║██╔████╔██║██║   ██║       ██║ █╗ ██║██║█████╔╝ ██║██║  ██║███████║   ██║   ███████║
    ██║     ██║   ██║██║╚██╔╝██║██║╚██╔╝██║██║   ██║       ██║███╗██║██║██╔═██╗ ██║██║  ██║██╔══██║   ██║   ██╔══██║
    ╚██████╗╚██████╔╝██║ ╚═╝ ██║██║ ╚═╝ ██║██║   ██║       ╚███╔███╔╝██║██║  ██╗██║██████╔╝██║  ██║   ██║   ██║  ██║
     ╚═════╝ ╚═════╝ ╚═╝     ╚═╝╚═╝     ╚═╝╚═╝   ╚═╝        ╚══╝╚══╝ ╚═╝╚═╝  ╚═╝╚═╝╚═════╝ ╚═╝  ╚═╝   ╚═╝   ╚═╝  ╚═╝
 */

    if (!addStatements.isEmpty() || !deleteStatements.isEmpty()) {

      log.debug("Statements has been updated.");

      if (!addStatements.isEmpty()) {
        log.debug("Adding {} statements.", addStatements.size());
        for (Statement statement : addStatements) {
          log.trace("Adding statement:\n{}", statement);
        }
      }
      if (!deleteStatements.isEmpty()) {
        log.debug("Deleting {} statements.", deleteStatements.size());
        for (Statement statement : deleteStatements) {
          log.trace("{}", statement);
        }
      }

      if (!isDryRun()) {
        getWikiData().getDataEditor().updateStatements(naturvardsregistretObject.getWikiDataItem().getEntityId(),
            addStatements,
            deleteStatements,
            "Bot updated due to delta found compared to local data from Naturvårdsverket", Collections.emptyList());

        log.info("Committed statements diff to WikiData.");
      }

    } else {
      log.debug("No statements has been updated.");
    }
    log.trace("Done processing nvrid {}", naturvardsregistretObject.getNvrid());
  }

  protected void evaluateDelta(
      NaturvardsregistretObject naturvardsregistretObject,
      List<Statement> addStatements, List<Statement> deleteStatements
  ) throws Exception {

    // inception date
    {
      Statement existingInceptionDate = wikiData.findMostRecentPublishedStatement(naturvardsregistretObject.getWikiDataItem(), getWikiData().property("inception date"));
      if (existingInceptionDate == null
          || (!wikiData.toLocalDateTime((TimeValue) existingInceptionDate.getValue()).equals(wikiData.toLocalDateTime(inceptionDateValueFactory(naturvardsregistretObject))))) {
        addStatements.add(inceptionDateStatementFactory(naturvardsregistretObject));
        progressEntity.getCreatedClaims().add("inception date");
      }
    }

    // iunc cateogory
    {
      // todo lookup wikidata if "Not Applicable" is in use as a category!
      // todo see https://www.protectedplanet.net/c/wdpa-lookup-tables
      // not applicable seems to be set a null value link?
      // johannisberg is as null: https://www.wikidata.org/wiki/Q30180845
      String iuncCategoryValue = naturvardsregistretObject.getFeature().getProperty("IUCNKAT");
      if (iuncCategoryValue != null) {
        iuncCategoryValue = iuncCategoryValue.replaceFirst("^\\s*([^,]+).*", "$1").trim().toUpperCase();
        EntityIdValue iuncCategory = iuncCategories.get(iuncCategoryValue);
        if (iuncCategory == null) {
          log.warn("Unsupported IUNC category in feature: {}", naturvardsregistretObject.getFeature().getProperties());
          progressEntity.getWarnings().add("Unsupported IUNC category in feature: " + iuncCategoryValue);
        } else {
          // we managed to parse IUNC category from feature
          Statement existingIuncCategory = wikiData.findMostRecentPublishedStatement(naturvardsregistretObject.getWikiDataItem(), getWikiData().property("IUCN protected areas category"));
          if (existingIuncCategory == null) {
            log.trace("No previous IUNC category claim. Creating new without point in time.");
            addStatements.add(iuncCategoryStatementFactory(iuncCategory, naturvardsregistretObject));
            progressEntity.getCreatedClaims().add("iunc category");

          } else {
            log.trace("There is an existing IUNC category set at Wikidata");

            TimeValue existingIuncCategoryReferencePublishedDate = getReferencePublishedDate(existingIuncCategory);

            if (iuncCategory == WikiData.NULL_ENTITY_VALUE) {
              if (existingIuncCategory.getValue() != null) {
                log.trace("IUNC category has locally changed to NOT APPLICABLE.");
                if (!wikiData.hasQualifier(existingIuncCategory, wikiData.property("point in time"))) {
                  // update previous with point in time from retrieved date if not already set
                  if (existingIuncCategoryReferencePublishedDate == null) {
                    log.warn("No published date to use for point in time. Previous IUNC category will be left untouched.");
                    progressEntity.getWarnings().add("No published date to use for point in time. Previous IUNC category left untouched.");
                  } else {
                    addStatements.add(wikiData.asStatementBuilder(existingIuncCategory)
                        .withQualifier(new ValueSnakImpl(wikiData.property("point in time"), existingIuncCategoryReferencePublishedDate))
                        .build());
                    deleteStatements.add(existingIuncCategory);
                    progressEntity.getModifiedClaims().add("iunc category");
                  }
                }
                log.trace("Add new IUNC category without value but with point in time");
                addStatements.add(addNaturvardsregistretReferences(
                    naturvardsregistretObject, StatementBuilder
                        .forSubjectAndProperty(ItemIdValue.NULL, getWikiData().property("IUCN protected areas category"))
                        .withNoValue()
                ).build());
                progressEntity.getCreatedClaims().add("iunc category");

              } else {
                // no change
              }
            } else if (!iuncCategory.equals(existingIuncCategory.getValue())) {
              log.trace("IUNC category has locally changed to an applicable value.");
              if (!wikiData.hasQualifier(existingIuncCategory, wikiData.property("point in time"))) {
                // update previous with point in time from retrieved date if not already set
                if (existingIuncCategoryReferencePublishedDate == null) {
                  log.warn("No published date to use for point in time. Previous IUNC category will be left untouched.");
                  progressEntity.getWarnings().add("No published date to use for point in time. Previous IUNC category left untouched.");
                } else {
                  addStatements.add(wikiData.asStatementBuilder(existingIuncCategory)
                      .withQualifier(new ValueSnakImpl(wikiData.property("point in time"), existingIuncCategoryReferencePublishedDate))
                      .build());
                  deleteStatements.add(existingIuncCategory);
                  progressEntity.getModifiedClaims().add("iunc category");
                }
              }

              log.trace("Add new IUNC category with value and point in time");
              addStatements.add(addNaturvardsregistretReferences(
                  naturvardsregistretObject, StatementBuilder
                      .forSubjectAndProperty(ItemIdValue.NULL, getWikiData().property("IUCN protected areas category"))
                      .withQualifier(new ValueSnakImpl(wikiData.property("point in time"), wikiData.toTimeValue(naturvardsregistretObject.getPublishedDate())))
                      .withValue(iuncCategory)
              ).build());
              progressEntity.getCreatedClaims().add("iunc category");
            }
          }
        }
      }
    }

    // country
    {
      Statement existingCountry = wikiData.findMostRecentPublishedStatement(naturvardsregistretObject.getWikiDataItem(), getWikiData().property("country"));
      if (existingCountry == null
          || (!existingCountry.getValue().equals(getWikiData().entity("Sweden")))) {
        addStatements.add(countryStatementFactory(naturvardsregistretObject));
        progressEntity.getCreatedClaims().add("country");
      }
    }

    // operator
    {
      String featureOperatorValue = (String) naturvardsregistretObject.getFeature().getProperty("FORVALTARE");
      naturvardsregistretObject.setOperatorWikiDataItem(operatorsByNvrProperty.get(featureOperatorValue));
      if (naturvardsregistretObject.getOperatorWikiDataItem() == null) {
        log.warn("Unable to lookup operator Q for '{}' Operator claims will not be touched.", featureOperatorValue);
        progressEntity.getWarnings().add("Operator claims will not be touched. Unable to lookup operator listed in feature: " + featureOperatorValue);
      } else {
        Statement existingOperator = wikiData.findMostRecentPublishedStatement(naturvardsregistretObject.getWikiDataItem(), getWikiData().property("operator"));
        boolean existingOperatorHasDelta = existingOperator != null && !existingOperator.getValue().equals(naturvardsregistretObject.getOperatorWikiDataItem());
        if (existingOperator == null || existingOperatorHasDelta) {

          if (existingOperator != null) {

            //  if existing does not have point in time
            if (!wikiData.hasQualifier(existingOperator, wikiData.property("point in time"))) {

              // clone old and add point in time using
              TimeValue existingOperatorReferencePublishedDate = getReferencePublishedDate(existingOperator);
              if (existingOperatorReferencePublishedDate == null) {
                log.warn("Operator statement in {} exists but has no published date, so we don't know what to set as point in time.", naturvardsregistretObject.getNvrid());
                progressEntity.getWarnings().add("Previous operator statement exists but has no published date, so we don't know what to set point in time to");
                // todo now what?
              } else {
                addStatements.add(wikiData.asStatementBuilder(existingOperator)
                    .withQualifier(new ValueSnakImpl(wikiData.property("point in time"), existingOperatorReferencePublishedDate))
                    .build());
                deleteStatements.add(existingOperator);
                progressEntity.getModifiedClaims().add("operator");
              }
            } else {
              // old already has a point in time, no need to update old.
            }

            // add point in time to new
            addStatements.add(addNaturvardsregistretReferences(naturvardsregistretObject, StatementBuilder
                .forSubjectAndProperty(ItemIdValue.NULL, getWikiData().property("operator"))
                .withValue(naturvardsregistretObject.getOperatorWikiDataItem())
                .withQualifier(new ValueSnakImpl(wikiData.property("point in time"), wikiData.toTimeValue(naturvardsregistretObject.getPublishedDate())))
            ).build());
            progressEntity.getCreatedClaims().add("operator");

          } else {

            addStatements.add(operatorStatementFactory(naturvardsregistretObject));
            progressEntity.getCreatedClaims().add("operator");
          }
        }
      }
    }

    // municipality, can be multiple separated by comma
    // todo


    // area
    {
      Statement existingArea = wikiData.findStatementWithoutQualifier(naturvardsregistretObject.getWikiDataItem(), getWikiData().property("area"));
      boolean existingAreaHasDelta = existingArea != null && !existingArea.getValue().equals(areaValueFactory(naturvardsregistretObject));
      if (existingArea == null || existingAreaHasDelta) {
        addStatements.add(areaStatementFactory(naturvardsregistretObject));
        progressEntity.getCreatedClaims().add("area");
        if (existingAreaHasDelta) {
          deleteStatements.add(existingArea);
          progressEntity.getDeletedClaims().add("area");
        }
      }
    }

    {
      Statement existingLandArea = wikiData.findStatementByUniqueQualifier(naturvardsregistretObject.getWikiDataItem(), getWikiData().property("area"), getWikiData().property("applies to part"), getWikiData().entity("land"));
      boolean existingLandAreaHasDelta = existingLandArea != null && !existingLandArea.getValue().equals(areaLandValueFactory(naturvardsregistretObject));
      if (existingLandArea == null || existingLandAreaHasDelta) {
        addStatements.add(areaLandStatementFactory(naturvardsregistretObject));
        progressEntity.getCreatedClaims().add("area land");
        if (existingLandAreaHasDelta) {
          deleteStatements.add(existingLandArea);
          progressEntity.getDeletedClaims().add("area land");
        }
      }
    }

    {
      Statement existingForestArea = wikiData.findStatementByUniqueQualifier(naturvardsregistretObject.getWikiDataItem(), getWikiData().property("area"), getWikiData().property("applies to part"), getWikiData().entity("forest"));
      boolean existingForestAreaHasDelta = existingForestArea != null && !existingForestArea.getValue().equals(areaForestValueFactory(naturvardsregistretObject));
      if (existingForestArea == null || existingForestAreaHasDelta) {
        addStatements.add(areaForestStatementFactory(naturvardsregistretObject));
        progressEntity.getCreatedClaims().add("area forest");
        if (existingForestAreaHasDelta) {
          deleteStatements.add(existingForestArea);
          progressEntity.getDeletedClaims().add("area forest");
        }
      }
    }

    {
      Statement existingBodyOfWaterArea = wikiData.findStatementByUniqueQualifier(naturvardsregistretObject.getWikiDataItem(), getWikiData().property("area"), getWikiData().property("applies to part"), getWikiData().entity("body of water"));
      boolean exitingBodyOfWaterAreaHasDelta = existingBodyOfWaterArea != null && !existingBodyOfWaterArea.getValue().equals(areaBodyOfWaterValueFactory(naturvardsregistretObject));
      if (existingBodyOfWaterArea == null || exitingBodyOfWaterAreaHasDelta) {
        addStatements.add(areaBodyOfWaterStatementFactory(naturvardsregistretObject));
        progressEntity.getCreatedClaims().add("area water");
        if (exitingBodyOfWaterAreaHasDelta) {
          deleteStatements.add(existingBodyOfWaterArea);
          progressEntity.getDeletedClaims().add("area water");
        }
      }
    }

    System.currentTimeMillis();

    // todo requires REST API access. see https://api.protectedplanet.net/documentation
    // todo i have requested key to karl.wettin@wikimedia.se
    // wdpaid
//    Statement existingWdpaId = natureReserve.wikiDataItem.findStatement(property("wdpaid"));
//    if (existingWdpaId != null) {
//      if (!existingWdpaId.getValue().equals(wdpaidValueFactory(natureReserve))) {
//        deleteStatements.add(existingWdpaId);
//        addStatements.add(wdpaidStatementFactory(natureReserve));
//      }
//    } else {
//      addStatements.add(wdpaidStatementFactory(natureReserve));
//    }


    if (!naturvardsregistretObject.getFeature().getGeometry().accept(
        new GeometryStrategy(
            this,
            naturvardsregistretObject,
            addStatements, deleteStatements
        ))) {
      log.error("Unable to process geometry {}", naturvardsregistretObject.getFeature().getGeometry());
    }
  }


  protected StatementBuilder addNaturvardsregistretReferences(
      NaturvardsregistretObject naturvardsregistretObject,
      StatementBuilder statementBuilder
  ) {
    ReferenceBuilder referenceBuilder = ReferenceBuilder.newInstance();
    naturvardsregistretReferenceFactory(referenceBuilder, naturvardsregistretObject);
    retrievedReferenceFactory(referenceBuilder, naturvardsregistretObject);
    publishedReferenceFactory(referenceBuilder, naturvardsregistretObject);
    statedInReferenceFactory(referenceBuilder, naturvardsregistretObject);
    statementBuilder.withReference(referenceBuilder.build());
    return statementBuilder;
  }

  private Statement iuncCategoryStatementFactory(EntityIdValue iuncCategory, NaturvardsregistretObject naturvardsregistretObject) {
    StatementBuilder statementBuilder = StatementBuilder
        .forSubjectAndProperty(ItemIdValue.NULL, getWikiData().property("IUCN protected areas category"));
    if (WikiData.NULL_ENTITY_VALUE != iuncCategory) {
      statementBuilder.withValue(iuncCategory);
    }
    return addNaturvardsregistretReferences(naturvardsregistretObject, statementBuilder).build();
  }

  private Statement inceptionDateStatementFactory(NaturvardsregistretObject naturvardsregistretObject) {
    return addNaturvardsregistretReferences(naturvardsregistretObject, StatementBuilder
        .forSubjectAndProperty(ItemIdValue.NULL, getWikiData().property("inception date"))
        .withValue(inceptionDateValueFactory(naturvardsregistretObject))
    ).build();
  }

  private TimeValue inceptionDateValueFactory(NaturvardsregistretObject naturvardsregistretObject) {
    // todo I think inception date should be either IKRAFTDAT or if null URSGALLDAT or if null URBESLDAT, but historically we used URSBESLDAT

    boolean iAmRightAboutThis = false;

    String inceptionDateString;
    if (iAmRightAboutThis) {
      inceptionDateString = naturvardsregistretObject.getFeature().getProperty("IKRAFTDAT");
      if (inceptionDateString == null) {
        inceptionDateString = naturvardsregistretObject.getFeature().getProperty("URSGALLDAT");
      }
      if (inceptionDateString == null) {
        inceptionDateString = naturvardsregistretObject.getFeature().getProperty("URSBESLDAT");
      }
    } else {
      inceptionDateString = naturvardsregistretObject.getFeature().getProperty("URSBESLDAT");
      if (inceptionDateString == null) {
        inceptionDateString = naturvardsregistretObject.getFeature().getProperty("URSGALLDAT");
      }
      if (inceptionDateString == null) {
        inceptionDateString = naturvardsregistretObject.getFeature().getProperty("IKRAFTDAT");
      }

    }
    if (inceptionDateString == null) {
      throw new RuntimeException("No candidates for inception date found!");
    }
    LocalDate inceptionDate = LocalDate.parse(inceptionDateString, featureValueDateFormatter);
    return wikiData.toTimeValue(inceptionDate);
  }

  private Statement countryStatementFactory(NaturvardsregistretObject naturvardsregistretObject) {
    return addNaturvardsregistretReferences(naturvardsregistretObject, StatementBuilder
        .forSubjectAndProperty(ItemIdValue.NULL, getWikiData().property("country"))
        .withValue(getWikiData().entity("Sweden"))
    ).build();
  }

  private Statement operatorStatementFactory(NaturvardsregistretObject naturvardsregistretObject) {
    return addNaturvardsregistretReferences(naturvardsregistretObject, StatementBuilder
        .forSubjectAndProperty(ItemIdValue.NULL, getWikiData().property("operator"))
        .withValue(naturvardsregistretObject.getOperatorWikiDataItem())
    ).build();
  }

  private QuantityValue areaValueFactory(NaturvardsregistretObject naturvardsregistretObject, String property) {
    return new QuantityValueImpl(
        BigDecimal.valueOf(((Number) naturvardsregistretObject.getFeature().getProperty(property)).doubleValue()),
        null, null,
        getWikiData().entity("hectare").getIri()
    );
  }

  private Statement areaStatementFactory(NaturvardsregistretObject naturvardsregistretObject) {
    return addNaturvardsregistretReferences(naturvardsregistretObject, StatementBuilder
        .forSubjectAndProperty(ItemIdValue.NULL, getWikiData().property("area"))
        .withValue(areaValueFactory(naturvardsregistretObject))
    ).build();
  }

  private QuantityValue areaValueFactory(NaturvardsregistretObject naturvardsregistretObject) {
    return areaValueFactory(naturvardsregistretObject, "AREA_HA");
  }

  private Statement areaForestStatementFactory(NaturvardsregistretObject naturvardsregistretObject) {
    return addNaturvardsregistretReferences(naturvardsregistretObject, StatementBuilder
        .forSubjectAndProperty(ItemIdValue.NULL, getWikiData().property("area"))
        .withValue(areaForestValueFactory(naturvardsregistretObject))
        .withQualifier(new ValueSnakImpl(getWikiData().property("applies to part"), getWikiData().entity("forest")))
    ).build();
  }

  private QuantityValue areaForestValueFactory(NaturvardsregistretObject naturvardsregistretObject) {
    return areaValueFactory(naturvardsregistretObject, "SKOG_HA");
  }

  private Statement areaLandStatementFactory(NaturvardsregistretObject naturvardsregistretObject) {
    return addNaturvardsregistretReferences(naturvardsregistretObject, StatementBuilder
        .forSubjectAndProperty(ItemIdValue.NULL, getWikiData().property("area"))
        .withValue(areaLandValueFactory(naturvardsregistretObject))
        .withQualifier(new ValueSnakImpl(getWikiData().property("applies to part"), getWikiData().entity("land")))
    ).build();
  }

  private QuantityValue areaLandValueFactory(NaturvardsregistretObject naturvardsregistretObject) {
    return areaValueFactory(naturvardsregistretObject, "LAND_HA");
  }

  private Statement areaBodyOfWaterStatementFactory(NaturvardsregistretObject naturvardsregistretObject) {
    return addNaturvardsregistretReferences(naturvardsregistretObject, StatementBuilder
        .forSubjectAndProperty(ItemIdValue.NULL, getWikiData().property("area"))
        .withValue(areaBodyOfWaterValueFactory(naturvardsregistretObject))
        .withQualifier(new ValueSnakImpl(getWikiData().property("applies to part"), getWikiData().entity("body of water")))
    ).build();
  }

  private QuantityValue areaBodyOfWaterValueFactory(NaturvardsregistretObject naturvardsregistretObject) {
    return areaValueFactory(naturvardsregistretObject, "VATTEN_HA");
  }

  private void naturvardsregistretReferenceFactory(ReferenceBuilder referenceBuilder, NaturvardsregistretObject naturvardsregistretObject) {
    referenceBuilder.withPropertyValue(getWikiData().property("reference URL"), new StringValueImpl(
        "http://nvpub.vic-metria.nu/naturvardsregistret/rest/omrade/" + naturvardsregistretObject.getNvrid() + "/G%C3%A4llande"));
  }

  private void retrievedReferenceFactory(ReferenceBuilder referenceBuilder, NaturvardsregistretObject naturvardsregistretObject) {
    referenceBuilder.withPropertyValue(getWikiData().property("retrieved"), wikiData.toTimeValue(naturvardsregistretObject.getRetrievedDate()));
  }

  private void publishedReferenceFactory(ReferenceBuilder referenceBuilder, NaturvardsregistretObject naturvardsregistretObject) {
    referenceBuilder.withPropertyValue(getWikiData().property("publication date"), wikiData.toTimeValue(naturvardsregistretObject.getPublishedDate()));
  }

  private void statedInReferenceFactory(ReferenceBuilder referenceBuilder, NaturvardsregistretObject naturvardsregistretObject) {
    referenceBuilder.withPropertyValue(getWikiData().property("stated in"), getWikiData().entity("Protected Areas (Nature Reserves)"));
  }

  public TimeValue getReferencePublishedDate(Statement statement) {
    PropertyIdValue published = wikiData.property("publication date");
    for (Reference reference : statement.getReferences()) {
      for (Iterator<Snak> snakIterator = reference.getAllSnaks(); snakIterator.hasNext(); ) {
        Snak snak = snakIterator.next();
        if (published.getId().equals(snak.getPropertyId().getId())) {
          TimeValue snakTimeValue = (TimeValue) snak.getValue();
          return snakTimeValue;
        }
      }
    }
    return null;
  }


}
