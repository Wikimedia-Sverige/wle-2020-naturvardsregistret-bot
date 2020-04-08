package se.wikimedia.wle.naturvardsverket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.Setter;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikidata.wdtk.datamodel.helpers.Datamodel;
import org.wikidata.wdtk.datamodel.helpers.StatementBuilder;
import org.wikidata.wdtk.datamodel.implementation.EntityIdValueImpl;
import org.wikidata.wdtk.datamodel.implementation.TimeValueImpl;
import org.wikidata.wdtk.datamodel.interfaces.*;
import org.wikidata.wdtk.wikibaseapi.BasicApiConnection;
import org.wikidata.wdtk.wikibaseapi.WikibaseDataEditor;
import org.wikidata.wdtk.wikibaseapi.WikibaseDataFetcher;
import org.wikidata.wdtk.wikibaseapi.apierrors.MediaWikiApiErrorException;

import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.*;

public class WikiData {

  private ObjectMapper objectMapper = new ObjectMapper();
  private Logger log = LoggerFactory.getLogger(getClass());

  private CloseableHttpClient client;

  private String userAgent;
  private String username;
  private String password;
  private String userAgentVersion;
  private String emailAddress;

  public WikiData(
      String userAgent, String userAgentVersion, String emailAddress,
      String username, String password
  ) {
    this.userAgent = userAgent;
    this.userAgentVersion = userAgentVersion;
    this.emailAddress = emailAddress;
    this.username = username;
    this.password = password;
  }

  /**
   * If true, then we ensure that the entities actually exists in WikiData.
   */
  @Getter
  @Setter
  private boolean defaultRequireEntityIdValue = true;

  @Getter
  private WikibaseDataEditor dataEditor;

  @Getter
  private WikibaseDataFetcher dataFetcher;

  private BasicApiConnection connection;

  private Map<String, EntityIdValue> wikiDataProperties = new HashMap<>();

  public static final EntityIdValue NULL_ENTITY_VALUE = new NonExistingEntityIdValue();
  public static final EntityDocument NULL_ENTITY = new NonExistingEntityDocument();

  public void open() throws Exception {

    client = HttpClientBuilder.create().setUserAgent(userAgent + "/" + userAgentVersion + "(" + emailAddress + ")").build();

    connection = BasicApiConnection.getWikidataApiConnection();
    connection.login(username, password);

    dataEditor = new WikibaseDataEditor(connection, Datamodel.SITE_WIKIDATA);
    dataEditor.setEditAsBot(true);
//    dataEditor.disableEditing(); // do no actual edits
    // dataEditor.setRemainingEdits(5); // do at most 5 (test) edits

    dataFetcher = new WikibaseDataFetcher(connection, Datamodel.SITE_WIKIDATA);
    // Do not retrieve data that we don't care about here:
    dataFetcher.getFilter().excludeAllLanguages();
    dataFetcher.getFilter().excludeAllSiteLinks();

  }

  public void close() throws Exception {
    client.close();
    connection.logout();
  }

  @Getter
  private Map<String, EntityIdValue> namedEntities = new HashMap<>();

  public EntityIdValue entity(String name) {
    EntityIdValue entityIdValue = namedEntities.get(name);
    if (entityIdValue == null) {
      throw new NullPointerException("No entity registered with name " + name);
    }
    return entityIdValue;
  }

  public PropertyIdValue property(String name) {
    return (PropertyIdValue) entity(name);
  }


  public EntityIdValue getEntityIdValue(String id) throws MediaWikiApiErrorException, IOException {
    return getEntityIdValue(id, defaultRequireEntityIdValue);
  }

  protected EntityIdValue getEntityIdValue(String id, boolean required) throws MediaWikiApiErrorException, IOException {
    if (!id.trim().equals(id)) {
      log.warn("Whitespaces detected in '{}'", id, new RuntimeException("Developer typo!"));
      id = id.trim();
    }
    EntityIdValue entityIdValue = wikiDataProperties.get(id);
    if (entityIdValue == null) {
      if (!required) {
        entityIdValue = EntityIdValueImpl.fromId(id, "http://www.wikidata.org/entity/");
        wikiDataProperties.put(id, entityIdValue);
      } else {
        EntityDocument entity = getEntityDocument(id, required);
        if (entity != null) {
          entityIdValue = entity.getEntityId();
        } else {
          entityIdValue = NULL_ENTITY_VALUE;
        }
      }
    }
    return entityIdValue;
  }

  protected EntityDocument getEntityDocument(String id, boolean required) throws MediaWikiApiErrorException, IOException {
    log.debug("Fetching WikiData entity {}", id);
    EntityDocument entityDocument = dataFetcher.getEntityDocument(id);
    if (entityDocument == null) {
      if (required) {
        throw new NullPointerException(id + " is not an existing entity id");
      }
      log.warn("Non existing entity id {}", id);
      return null;
    }
    wikiDataProperties.put(id, entityDocument.getEntityId());
    if (log.isDebugEnabled()) {
      String label;
      if (entityDocument instanceof LabeledStatementDocument) {
        LabeledStatementDocument labeledStatementDocument = (LabeledStatementDocument) entityDocument;
        if (labeledStatementDocument.getLabels() != null && !labeledStatementDocument.getLabels().isEmpty()) {
          if (labeledStatementDocument.getLabels().containsKey("en")) {
            label = labeledStatementDocument.getLabels().get("en").getText();
          } else {
            label = labeledStatementDocument.getLabels().entrySet().iterator().next().getValue().getText();
          }
        } else {
          label = entityDocument.getEntityId().getId();
        }
      } else {
        label = entityDocument.getEntityId().getId();
      }
      log.debug("Fetched WikiData entry {}: {}", id, label);
    }
    return entityDocument;
  }


  public ObjectNode query(String sparql) throws IOException {
    log.trace("Executing SPARQL query {}", sparql);

    String url = "http://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(sparql, "UTF8");

    CloseableHttpResponse response = client.execute(new HttpGet(url));
    try {
      if (response.getStatusLine().getStatusCode() != 200) {
        log.error("Wikidata response {}", response.getStatusLine());
        return null;
      }
      return objectMapper.readValue(response.getEntity().getContent(), ObjectNode.class);
    } finally {
      response.close();
    }

  }

  public boolean assertSingleObjectResponse(String sparql) throws IOException {
    ObjectNode response = query(sparql);

    /*

    {
  "head" : {
    "vars" : [ "item" ]
  },
  "results" : {
    "bindings" : [ {
      "item" : {
        "type" : "uri",
        "value" : "http://www.wikidata.org/entity/Q30180845"
      }
    } ]
  }
}
     */

    return response.get("results").get("bindings").size() == 1;

  }

  public static class MultipleResponsesException extends RuntimeException {
    public MultipleResponsesException(String message) {
      super(message);
    }
  }

  public String getSingleObject(String sparql) throws IOException {
    ObjectNode response = query(sparql);

    /*

    {
  "head" : {
    "vars" : [ "item" ]
  },
  "results" : {
    "bindings" : [ {
      "item" : {
        "type" : "uri",
        "value" : "http://www.wikidata.org/entity/Q30180845"
      }
    } ]
  }
}
     */

    if (response.get("results").get("bindings").size() == 0) {
      return null;
    } else if (response.get("results").get("bindings").size() > 1) {
      throw new MultipleResponsesException("More than a single result!\n" + sparql + "\n" + objectMapper.writeValueAsString(response));
    } else {
      String uri = response.get("results").get("bindings").get(0).get("item").get("value").textValue();
      return uri.substring(uri.lastIndexOf("Q"));
    }


  }

  public String findSingleObjectByUniqueLabel(String label, String lang) throws Exception {
    return getSingleObject("SELECT ?item ?itemLabel " +
        "WHERE {" +
        "  ?item rdfs:label \"" + label + "\"@" + lang + ". " +
        "} limit 2");
  }

  public TimeValue toTimeValue(LocalDate localDate) {
    return new TimeValueImpl(localDate.getYear(), (byte) localDate.getMonthValue(), (byte) localDate.getDayOfMonth(),
        (byte) 0, (byte) 0, (byte) 0,
        TimeValue.PREC_DAY, 0, 0, 60, TimeValue.CM_GREGORIAN_PRO);
  }

  public Statement findMostRecentPublishedStatement(ItemDocument wikiDataItem, PropertyIdValue property) {
    StatementGroup statements = wikiDataItem.findStatementGroup(property);
    if (statements == null) {
      return null;
    }
    if (statements.size() > 1) {
      PropertyIdValue published = property("publication date");
      Statement mostRecentStatement = null;
      LocalDateTime mostRecentStatementLocalDateTime = null;
      for (Statement statement : statements) {
        for (Reference reference : statement.getReferences()) {
          for (Iterator<Snak> snakIterator = reference.getAllSnaks(); snakIterator.hasNext(); ) {
            Snak snak = snakIterator.next();
            if (published.getId().equals(snak.getPropertyId().getId())) {
              TimeValue snakTimeValue = (TimeValue) snak.getValue();
              LocalDateTime snakDateTime = toLocalDateTime(snakTimeValue);
              if (mostRecentStatementLocalDateTime == null || snakDateTime.isAfter(mostRecentStatementLocalDateTime)) {
                mostRecentStatementLocalDateTime = snakDateTime;
                mostRecentStatement = statement;
                break;
              }
            }
          }
        }
        if (mostRecentStatement == null) {
          // in case no published qualifier, select the most recently added
          mostRecentStatement = statement;
        }
      }
      return mostRecentStatement;
    } else {
      return statements.getStatements().get(0);
    }
  }

  public boolean hasQualifier(Statement statement, PropertyIdValue property) {
    for (Reference reference : statement.getReferences()) {
      for (Iterator<Snak> snakIterator = reference.getAllSnaks(); snakIterator.hasNext(); ) {
        Snak snak = snakIterator.next();
        if (property.getId().equals(snak.getPropertyId().getId())) {
          return true;
        }
      }
    }
    return false;
  }

  // todo findMostRecentStatementBtUniqueQualifier
  public Statement findStatementByUniqueQualifier(ItemDocument wikiDataItem, PropertyIdValue propertyIdValue, PropertyIdValue qualifierPropertyId, EntityIdValue entityIdValue) {
    StatementGroup statements = wikiDataItem.findStatementGroup(propertyIdValue);
    if (statements == null) {
      return null;
    }
    Set<Statement> matches = new HashSet<>(statements.size());
    for (Statement statement : statements) {
      for (Iterator<Snak> iterator = statement.getAllQualifiers(); iterator.hasNext(); ) {
        Snak snack = iterator.next();
        if (qualifierPropertyId.equals(snack.getPropertyId())
            && entityIdValue.equals(snack.getValue())) {
          matches.add(statement);
          break;
        }
      }
    }
    if (matches.isEmpty()) {
      return null;
    } else if (matches.size() == 1) {
      return matches.iterator().next();
    } else {
      throw new RuntimeException("Multiple statements in group match id " + propertyIdValue);
    }
  }

  // todo findMostRecentStatementWithoutQualifier
  public Statement findStatementWithoutQualifier(ItemDocument wikiDataItem, PropertyIdValue propertyIdValue) {
    StatementGroup statements = wikiDataItem.findStatementGroup(propertyIdValue);
    if (statements == null) {
      return null;
    }
    Set<Statement> matches = new HashSet<>(statements.size());
    for (Statement statement : statements) {
      if (statement.getQualifiers().isEmpty()) {
        matches.add(statement);
      }
    }
    if (matches.isEmpty()) {
      return null;
    } else if (matches.size() == 1) {
      return matches.iterator().next();
    } else {
      throw new RuntimeException("Multiple statements in group is without qualifiers");
    }
  }

  public LocalDateTime toLocalDateTime(TimeValue timeValue) {
    timeValue = timeValue.toGregorian();
    StringBuilder sb = new StringBuilder(10);
    if (timeValue.getPrecision() >= TimeValue.PREC_YEAR) {
      sb.append(String.valueOf(timeValue.getYear()));
    }
    if (timeValue.getPrecision() >= TimeValue.PREC_MONTH) {
      if (sb.length() > 0) {
        sb.append("-");
      }
      sb.append(String.valueOf(timeValue.getMonth()));
    }
    if (timeValue.getPrecision() >= TimeValue.PREC_DAY) {
      if (sb.length() > 0) {
        sb.append("-");
      }
      sb.append(String.valueOf(timeValue.getDay()));
    }
    if (timeValue.getPrecision() >= TimeValue.PREC_HOUR) {
      if (sb.length() > 0) {
        sb.append(" ");
      }
      sb.append(String.valueOf(timeValue.getHour()));
    }
    if (timeValue.getPrecision() >= TimeValue.PREC_MINUTE) {
      if (sb.length() > 0) {
        sb.append(":");
      }
      sb.append(String.valueOf(timeValue.getMinute()));
    }
    if (timeValue.getPrecision() >= TimeValue.PREC_SECOND) {
      if (sb.length() > 0) {
        sb.append(":");
      }
      sb.append(String.valueOf(timeValue.getSecond()));
    }
    DateTimeFormatter dateTimeFormatter;
    if (timeValue.getPrecision() == TimeValue.PREC_YEAR) {
      dateTimeFormatter = new DateTimeFormatterBuilder()
          .appendPattern("yyyy")
          .parseDefaulting(ChronoField.MONTH_OF_YEAR, 0)
          .parseDefaulting(ChronoField.DAY_OF_MONTH, 0)
          .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
          .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
          .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
          .toFormatter();
    } else if (timeValue.getPrecision() == TimeValue.PREC_MONTH) {
      dateTimeFormatter = new DateTimeFormatterBuilder()
          .appendPattern("yyyy-M")
          .parseDefaulting(ChronoField.DAY_OF_MONTH, 0)
          .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
          .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
          .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
          .toFormatter();
    } else if (timeValue.getPrecision() == TimeValue.PREC_DAY) {
      dateTimeFormatter = new DateTimeFormatterBuilder()
          .appendPattern("yyyy-M-d")
          .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
          .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
          .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
          .toFormatter();
    } else if (timeValue.getPrecision() == TimeValue.PREC_HOUR) {
      dateTimeFormatter = new DateTimeFormatterBuilder()
          .appendPattern("yyyy-M-d H")
          .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
          .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
          .toFormatter();
    } else if (timeValue.getPrecision() == TimeValue.PREC_MINUTE) {
      dateTimeFormatter = new DateTimeFormatterBuilder()
          .appendPattern("yyyy-M-d H:m")
          .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
          .toFormatter();
    } else if (timeValue.getPrecision() >= TimeValue.PREC_SECOND) {
      dateTimeFormatter = new DateTimeFormatterBuilder()
          .appendPattern("yyyy-M-d H:m:s")
          .toFormatter();
    } else {
      throw new RuntimeException("Unsupported time value precision " + timeValue.getPrecision());
    }
    return LocalDateTime.parse(sb.toString(), dateTimeFormatter);
  }
  /** converts an existing statement to a builder, allowing for adding qualifiers, references etc and add it again. */
  public StatementBuilder asStatementBuilder(Statement statement) {
    StatementBuilder statementBuilder = StatementBuilder.forSubjectAndProperty(ItemIdValue.NULL, statement.getMainSnak().getPropertyId());
    if (statement.getQualifiers() != null && !statement.getQualifiers().isEmpty()) {
      statementBuilder.withQualifiers(statement.getQualifiers());
    }
    if (statement.getReferences() != null && !statement.getReferences().isEmpty()) {
      statementBuilder.withReferences(statement.getReferences());
    }
    if (statement.getValue() != null) {
      statementBuilder.withValue(statement.getValue());
    }
    if (statement.getRank() != null) {
      statementBuilder.withRank(statement.getRank());
    }
    return statementBuilder;
  }

}
