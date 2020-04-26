package se.wikimedia.wle.naturvardsverket;

import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.Getter;
import lombok.Setter;
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
import java.time.LocalDateTime;
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

  protected abstract boolean hasAreas(NaturvardsregistretObject object);

  public abstract String commonGeoshapeArticleNameFactory(NaturvardsregistretObject object);

  /**
   * Files to be processed
   */
  protected abstract File[] getNaturvardsregistretGeoJsonFiles();

  /**
   * Q describing e.g. nature reserve, national park, etc
   */
  protected abstract String getNaturvardsregistretObjectTypeEntityId();

  protected abstract String getDescription(NaturvardsregistretObject object, String language);

  protected abstract Collection<String> getSupportedDescriptionLanguages();

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

  @Setter
  private boolean doGeometryDeltaEvaluation = true;

  /** It set, then only previously processed will be re-executed whether or not not it succeeded previous execution. */
  @Setter
  private Long executePreviouslyExecutedWithSuccessStartedBefore = 1587918274951L;

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

      boolean debugExit = false;

      log.info("Processing entities...");
      for (Feature feature : featureCollection.getFeatures()) {
        // filter out null value properties
        feature.getProperties().entrySet().removeIf(property -> property.getValue() == null);

        String beslutstatus = feature.getProperty("BESLSTATUS");
        if (!"Gällande".equalsIgnoreCase(beslutstatus)) {
          log.warn("Status is not active, skipping entry");
          continue;
        }

        String nvrid = (String) feature.getProperty("NVRID");
        if (nvrid == null) {
          log.error("NVRID missing in {}", getObjectMapper().writeValueAsString(feature));
          continue;
        }

        Progress.Entity previousExecution = progress.getProcessed().get(nvrid);

        if (previousExecution != null) {
          if (executePreviouslyExecutedWithSuccessStartedBefore != null && previousExecution.getEpochStarted() < executePreviouslyExecutedWithSuccessStartedBefore) {
            log.info("{} succeeded last run, but that was way back in the past. Will be processed again.", nvrid);
          } else if (previousExecution.getError() == null) {
            log.info("{} is was previously processed without error. Will be skipped", nvrid);
            continue;
          } else {
            log.info("{} was previously processed with errors. Will be processed again.", nvrid);
          }
        } else {
          log.info("{} was never processed before. Will be processed now.", nvrid);
        }

        // process
        {
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
          if (debugExit) {
            return;
          }
        }
      }
    }
  }

  private Map<String, EntityIdValue> iucnCategories = new HashMap<>();


  private Map<String, EntityIdValue> operatorsByNvrProperty = new HashMap<>();


  private void initializeWikiData() throws MediaWikiApiErrorException, IOException {
    log.info("Initializing WikiData properties...");

    getWikiData().getNamedEntities().put("instance of", getWikiData().getEntityIdValue("P31"));

    getWikiData().getNamedEntities().put("inception date", getWikiData().getEntityIdValue("P571"));

    getWikiData().getNamedEntities().put("IUCN protected areas category", getWikiData().getEntityIdValue("P814"));


    // , Områden som ej kan klassificeras enligt IUCN: s system.
    iucnCategories.put("0", WikiData.NULL_ENTITY_VALUE);
    // , Strikt naturreservat (Strict Nature Reserve)
    iucnCategories.put("IA", getWikiData().getEntityIdValue("Q14545608"));
    // , Vildmarksområde (Wilderness Area)
    iucnCategories.put("IB", getWikiData().getEntityIdValue("Q14545620"));
    //, Nationalpark (National Park)
    iucnCategories.put("II", getWikiData().getEntityIdValue("Q14545628"));
    // , Naturmonument (Natural Monument)
    iucnCategories.put("III", getWikiData().getEntityIdValue("Q14545633"));
    //  Habitat/Artskyddsområde (Habitat/Species Management Area)
    iucnCategories.put("IV", getWikiData().getEntityIdValue("Q14545639"));
    // Skyddat landskap/havsområde (Protected Landscape/Seascape)
    iucnCategories.put("V", getWikiData().getEntityIdValue("Q14545646"));

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

    log.debug("Find unique WikiData item matching (Naturvårdsregistret object id).");
    String sparqlQuery = "SELECT ?item WHERE { ?item wdt:P3613 ?value. FILTER (?value IN (\""
        + naturvardsregistretObject.getNvrid() + "\")) SERVICE wikibase:label { bd:serviceParam wikibase:language \"[AUTO_LANGUAGE],en\". }} LIMIT 2";

    naturvardsregistretObject.setWikiDataObjectKey(wikiData.getSingleObject(sparqlQuery));

    if (naturvardsregistretObject.getWikiDataObjectKey() == null) {
      log.debug("Creating new WikiData item as there is none describing nvrid {}", naturvardsregistretObject.getNvrid());
      ItemDocumentBuilder builder = ItemDocumentBuilder.forItemId(ItemIdValue.NULL);
      builder.withStatement(
          addNaturvardsregistretReferences(naturvardsregistretObject, StatementBuilder
              .forSubjectAndProperty(ItemIdValue.NULL, getWikiData().property("instance of"))
              .withValue(wikiData.getEntityIdValue(getNaturvardsregistretObjectTypeEntityId()))
          ).build());
      builder.withStatement(
          addNaturvardsregistretReferences(naturvardsregistretObject, StatementBuilder
              .forSubjectAndProperty(ItemIdValue.NULL,
                  getWikiData().property("nvrid"))
              .withValue(new StringValueImpl(naturvardsregistretObject.getNvrid()))
          ).build());

      for (String language : getSupportedDescriptionLanguages()) {
        builder.withLabel(naturvardsregistretObject.getName(), language);
        builder.withDescription(getDescription(naturvardsregistretObject, language), language);
      }

      if (!isDryRun()) {
        getWikiData().getDataEditor().setMaxLag(10);
        getWikiData().getDataEditor().setMaxLagMaxRetries(1000);
        getWikiData().getDataEditor().setMaxLagFirstWaitTime(6000); // 1000 * 6 seconds = 100 minutes.
        getWikiData().getDataEditor().setMaxLagBackOffFactor(1d);
        naturvardsregistretObject.setWikiDataItem(getWikiData().getDataEditor().createItemDocument(
            builder.build(),
            "Created by bot from data supplied by Naturvårdsverket",
            null
        ));
        naturvardsregistretObject.setWikiDataObjectKey(naturvardsregistretObject.getWikiDataItem().getEntityId().getId());
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
        getWikiData().getDataEditor().setMaxLag(10);
        getWikiData().getDataEditor().setMaxLagMaxRetries(1000);
        getWikiData().getDataEditor().setMaxLagFirstWaitTime(6000); // 1000 * 6 seconds = 100 minutes.
        getWikiData().getDataEditor().setMaxLagBackOffFactor(1d);
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

    // todo labels and descriptions?

    // inception date
    {
      // there should only be one inception date.
      // remove any that is not of the value from the delta.
      // add if current is missing.

      TimeValue inceptionDate = inceptionDateValueFactory(naturvardsregistretObject);
      LocalDateTime inceptionLocalDate = wikiData.toLocalDateTime(inceptionDate);
      StatementGroup statements = naturvardsregistretObject.getWikiDataItem().findStatementGroup(getWikiData().property("inception date"));
      if (statements == null) {
        addStatements.add(inceptionDateStatementFactory(naturvardsregistretObject));
        progressEntity.getCreatedClaims().add("inception date");
      } else {
        boolean foundMatchingExistingInceptionDateWithReferences = false;
        for (Statement existingInceptionDate : statements) {
          if (!wikiData.toLocalDateTime((TimeValue) existingInceptionDate.getValue()).equals(inceptionLocalDate)) {
            deleteStatements.add(existingInceptionDate);
            progressEntity.getDeletedClaims().add("inception date");
          } else if (existingInceptionDate.getReferences() == null || existingInceptionDate.getReferences().isEmpty()) {
            // The value is correct, but there is no references to where it came from. Remove that to make place for one with references.
            deleteStatements.add(existingInceptionDate);
            progressEntity.getModifiedClaims().add("inception date");
          } else {
            foundMatchingExistingInceptionDateWithReferences = true;
          }
        }
        if (!foundMatchingExistingInceptionDateWithReferences) {
          addStatements.add(inceptionDateStatementFactory(naturvardsregistretObject));
          progressEntity.getCreatedClaims().add("inception date");
        }
      }

    }

    // iucn cateogory
    {
      // todo lookup wikidata if "Not Applicable" is in use as a category!
      // todo see https://www.protectedplanet.net/c/wdpa-lookup-tables
      // not applicable seems to be set a null value link?
      // johannisberg is as null: https://www.wikidata.org/wiki/Q30180845
      String iucnCategoryValue = naturvardsregistretObject.getFeature().getProperty("IUCNKAT");
      if (iucnCategoryValue != null) {
        iucnCategoryValue = iucnCategoryValue.replaceFirst("^\\s*([^,]+).*", "$1").trim().toUpperCase();
        EntityIdValue iucn = iucnCategories.get(iucnCategoryValue);
        if (iucn == null) {
          log.warn("Unsupported IUCN category in feature: {}", naturvardsregistretObject.getFeature().getProperties());
          progressEntity.getWarnings().add("Unsupported IUCN category in feature: " + iucnCategoryValue);
        } else {
          // we managed to parse IUCN category from feature
          Statement existingIucnCategory = wikiData.findMostRecentPublishedStatement(naturvardsregistretObject.getWikiDataItem(), getWikiData().property("IUCN protected areas category"));
          TimeValue existingIucnCategoryReferencePublishedDate = getReferencePublishedDate(existingIucnCategory);
          if (existingIucnCategoryReferencePublishedDate != null
              && wikiData.toLocalDateTime(existingIucnCategoryReferencePublishedDate).isAfter(naturvardsregistretObject.getPublishedDate().atTime(0, 0))) {
            log.info("IUCN publication date is fresher at Wikidata than local publish date. Skipping.");
            progressEntity.getWarnings().add("IUCN publication date is fresher at Wikidata.");

          } else {

            if (existingIucnCategory == null) {
              log.trace("No previous IUCN category claim. Creating new without point in time.");
              addStatements.add(iucnCategoryStatementFactory(iucn, naturvardsregistretObject));
              progressEntity.getCreatedClaims().add("iucn category");

            } else {
              log.trace("There is an existing IUCN category set at Wikidata");

              if (iucn == WikiData.NULL_ENTITY_VALUE) {
                if (existingIucnCategory.getValue() != null) {
                  log.trace("IUCN category has locally changed to NOT APPLICABLE.");
                  if (!wikiData.hasQualifier(existingIucnCategory, wikiData.property("point in time"))) {
                    // update previous with point in time from retrieved date if not already set
                    if (existingIucnCategoryReferencePublishedDate == null) {
                      log.warn("No published date to use for point in time. Previous IUCN category will be left untouched.");
                      progressEntity.getWarnings().add("No published date to use for point in time. Previous IUCN category left untouched.");
                    } else {
                      addStatements.add(wikiData.asStatementBuilder(existingIucnCategory)
                          .withQualifier(new ValueSnakImpl(wikiData.property("point in time"), existingIucnCategoryReferencePublishedDate))
                          .build());
                      deleteStatements.add(existingIucnCategory);
                      progressEntity.getModifiedClaims().add("iucn category");
                    }
                  }
                  log.trace("Add new IUCN category without value but with point in time");
                  addStatements.add(addNaturvardsregistretReferences(
                      naturvardsregistretObject, StatementBuilder
                          .forSubjectAndProperty(ItemIdValue.NULL, getWikiData().property("IUCN protected areas category"))
                          .withNoValue()
                  ).build());
                  progressEntity.getCreatedClaims().add("iucn category");

                } else {
                  // no change
                }
              } else if (!iucn.equals(existingIucnCategory.getValue())) {
                log.trace("IUCN category has locally changed to an applicable value.");
                if (!wikiData.hasQualifier(existingIucnCategory, wikiData.property("point in time"))) {
                  // update previous with point in time from retrieved date if not already set
                  if (existingIucnCategoryReferencePublishedDate == null) {
                    log.warn("No published date to use for point in time. Previous IUCN category will be left untouched.");
                    progressEntity.getWarnings().add("No published date to use for point in time. Previous IUCN category left untouched.");
                  } else {
                    addStatements.add(wikiData.asStatementBuilder(existingIucnCategory)
                        .withQualifier(new ValueSnakImpl(wikiData.property("point in time"), existingIucnCategoryReferencePublishedDate))
                        .build());
                    deleteStatements.add(existingIucnCategory);
                    progressEntity.getModifiedClaims().add("iucn category");
                  }
                }

                log.trace("Add new IUCN category with value and point in time");
                addStatements.add(addNaturvardsregistretReferences(
                    naturvardsregistretObject, StatementBuilder
                        .forSubjectAndProperty(ItemIdValue.NULL, getWikiData().property("IUCN protected areas category"))
                        .withQualifier(new ValueSnakImpl(wikiData.property("point in time"), wikiData.toTimeValue(naturvardsregistretObject.getPublishedDate())))
                        .withValue(iucn)
                ).build());
                progressEntity.getCreatedClaims().add("iucn category");
              }
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
        TimeValue existingOperatorReferencePublishedDate = getReferencePublishedDate(existingOperator);
        if (existingOperatorReferencePublishedDate != null
            && wikiData.toLocalDateTime(existingOperatorReferencePublishedDate).isAfter(naturvardsregistretObject.getPublishedDate().atTime(0, 0))) {
          log.info("Operator publish date at Wikidata is more fresh than local. Skipping.");
          progressEntity.getWarnings().add("Operator publication date is fresher at Wikidata.");

        } else {

          boolean existingOperatorHasDelta = existingOperator != null && !existingOperator.getValue().equals(naturvardsregistretObject.getOperatorWikiDataItem());
          if (existingOperator == null || existingOperatorHasDelta) {

            if (existingOperator != null) {


              //  if existing does not have point in time
              if (!wikiData.hasQualifier(existingOperator, wikiData.property("point in time"))) {

                // clone old and add point in time using
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
            }
          } else {
            // no change
          }
        }
      }
    }


    // municipality, can be multiple separated by comma
    // todo


    // area
    if (!hasAreas(naturvardsregistretObject)) {
      // todo remove this part, it's a bugfix due to bot adding when it shouldn't.
      Statement existingArea = wikiData.findStatementWithoutQualifier(naturvardsregistretObject.getWikiDataItem(), getWikiData().property("area"));
      if (existingArea != null) {
        deleteStatements.add(existingArea);
        progressEntity.getDeletedClaims().add("area");
      }
      Statement existingLandArea = wikiData.findStatementByUniqueQualifier(naturvardsregistretObject.getWikiDataItem(), getWikiData().property("area"), getWikiData().property("applies to part"), getWikiData().entity("land"));
      if (existingLandArea != null) {
        deleteStatements.add(existingLandArea);
        progressEntity.getDeletedClaims().add("area land");
      }
      Statement existingForestArea = wikiData.findStatementByUniqueQualifier(naturvardsregistretObject.getWikiDataItem(), getWikiData().property("area"), getWikiData().property("applies to part"), getWikiData().entity("forest"));
      if (existingForestArea != null) {
        deleteStatements.add(existingForestArea);
        progressEntity.getDeletedClaims().add("area forest");
      }
      Statement existingBodyOfWaterArea = wikiData.findStatementByUniqueQualifier(naturvardsregistretObject.getWikiDataItem(), getWikiData().property("area"), getWikiData().property("applies to part"), getWikiData().entity("body of water"));
      if (existingBodyOfWaterArea != null) {
        deleteStatements.add(existingBodyOfWaterArea);
        progressEntity.getDeletedClaims().add("area water");
      }
    } else {
      {
        Statement existingArea = wikiData.findStatementWithoutQualifier(naturvardsregistretObject.getWikiDataItem(), getWikiData().property("area"));
        TimeValue existingAreaReferencePublishDate = getReferencePublishedDate(existingArea);
        if (existingAreaReferencePublishDate != null
            && wikiData.toLocalDateTime(existingAreaReferencePublishDate).isAfter(naturvardsregistretObject.getPublishedDate().atTime(0, 0))) {
          log.info("Area published date is fresher at Wikidata than in local data. Skipping.");
          progressEntity.getWarnings().add("Area publication date is fresher at Wikidata.");
        } else {
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
      }

      {
        Statement existingLandArea = wikiData.findStatementByUniqueQualifier(naturvardsregistretObject.getWikiDataItem(), getWikiData().property("area"), getWikiData().property("applies to part"), getWikiData().entity("land"));
        TimeValue existingAreaLandReferencePublishDate = getReferencePublishedDate(existingLandArea);
        if (existingAreaLandReferencePublishDate != null
            && wikiData.toLocalDateTime(existingAreaLandReferencePublishDate).isAfter(naturvardsregistretObject.getPublishedDate().atTime(0, 0))) {
          log.info("Area land published date is fresher at Wikidata than in local data. Skipping.");
          progressEntity.getWarnings().add("Area land publication date is fresher at Wikidata.");

        } else {
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
      }

      {
        Statement existingForestArea = wikiData.findStatementByUniqueQualifier(naturvardsregistretObject.getWikiDataItem(), getWikiData().property("area"), getWikiData().property("applies to part"), getWikiData().entity("forest"));
        TimeValue existingAreaForestReferencePublishDate = getReferencePublishedDate(existingForestArea);
        if (existingAreaForestReferencePublishDate != null
            && wikiData.toLocalDateTime(existingAreaForestReferencePublishDate).isAfter(naturvardsregistretObject.getPublishedDate().atTime(0, 0))) {
          log.info("Area forest published date is fresher at Wikidata than in local data. Skipping.");
          progressEntity.getWarnings().add("Area forest publication date is fresher at Wikidata.");

        } else {
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
      }

      {
        Statement existingBodyOfWaterArea = wikiData.findStatementByUniqueQualifier(naturvardsregistretObject.getWikiDataItem(), getWikiData().property("area"), getWikiData().property("applies to part"), getWikiData().entity("body of water"));
        TimeValue existingAreaBodyOfWaterReferencePublishDate = getReferencePublishedDate(existingBodyOfWaterArea);
        if (existingAreaBodyOfWaterReferencePublishDate != null
            && wikiData.toLocalDateTime(existingAreaBodyOfWaterReferencePublishDate).isAfter(naturvardsregistretObject.getPublishedDate().atTime(0, 0))) {
          log.info("Area body of water published date is fresher at Wikidata than in local data. Skipping.");
          progressEntity.getWarnings().add("Area body of water publication date is fresher at Wikidata.");

        } else {
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


    if (doGeometryDeltaEvaluation) {
      naturvardsregistretObject.getFeature().getGeometry().accept(
          new GeometryStrategy(
              this,
              naturvardsregistretObject,
              addStatements, deleteStatements
          ));
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

  private Statement iucnCategoryStatementFactory(EntityIdValue iucnCategory, NaturvardsregistretObject naturvardsregistretObject) {
    StatementBuilder statementBuilder = StatementBuilder
        .forSubjectAndProperty(ItemIdValue.NULL, getWikiData().property("IUCN protected areas category"));
    if (WikiData.NULL_ENTITY_VALUE != iucnCategory) {
      statementBuilder.withValue(iucnCategory);
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

    boolean iAmRightAboutThis = true;

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
    if (statement == null) {
      return null;
    }
    PropertyIdValue published = wikiData.property("publication date");
    if (published == null) {
      return null;
    }
    for (Reference reference : statement.getReferences()) {
      for (Iterator<Snak> snakIterator = reference.getAllSnaks(); snakIterator.hasNext(); ) {
        Snak snak = snakIterator.next();
        if (snak instanceof ValueSnak) {
          ValueSnak valueSnak = (ValueSnak) snak;
          if (published.getId().equals(snak.getPropertyId().getId())) {
            TimeValue snakTimeValue = (TimeValue) valueSnak.getValue();
            return snakTimeValue;
          }
        }
      }
    }
    return null;
  }


}
