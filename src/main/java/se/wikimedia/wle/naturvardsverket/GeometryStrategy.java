package se.wikimedia.wle.naturvardsverket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.sourceforge.jwbf.core.contentRep.Article;
import org.geojson.*;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikidata.wdtk.datamodel.helpers.StatementBuilder;
import org.wikidata.wdtk.datamodel.implementation.GlobeCoordinatesValueImpl;
import org.wikidata.wdtk.datamodel.implementation.StringValueImpl;
import org.wikidata.wdtk.datamodel.interfaces.*;
import org.wololo.jts2geojson.GeoJSONReader;

import java.util.List;

/**
 * Accept returns false if unable to handle contained geometry
 */
public class GeometryStrategy implements GeoJsonObjectVisitor<Void> {

  private Logger log = LoggerFactory.getLogger(getClass());

  private AbstractNaturvardsregistretBot bot;
  private NaturvardsregistretObject naturvardsregistretObject;
  private List<Statement> addStatements;
  private List<Statement> deleteStatements;

  public GeometryStrategy(
      AbstractNaturvardsregistretBot bot,
      NaturvardsregistretObject naturvardsregistretObject,
      List<Statement> addStatements, List<Statement> deleteStatements
  ) {
    this.naturvardsregistretObject = naturvardsregistretObject;
    this.addStatements = addStatements;
    this.deleteStatements = deleteStatements;
    this.bot = bot;
  }

  @Override
  public Void visit(org.geojson.Point point) {
    try {
      org.locationtech.jts.geom.Point jtsPoint = (org.locationtech.jts.geom.Point) new GeoJSONReader().read(bot.getObjectMapper().writeValueAsString(point));

      // allow 1m diff
      processSingleCoordinateLocation(0.001d, jtsPoint);

      return null;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Void visit(MultiPoint multiPoint) {
    try {
      org.locationtech.jts.geom.MultiPoint jtsMultiPoint = (org.locationtech.jts.geom.MultiPoint)new GeoJSONReader().read(bot.getObjectMapper().writeValueAsString(multiPoint));

      org.locationtech.jts.geom.Point centroid = calculateContainedCentroid(jtsMultiPoint);

      // allow 1m diff
      processSingleCoordinateLocation(0.001d, centroid);

      // todo add a commons article containing all points? produce a convex hull?

      return null;

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Void visit(Polygon polygon) {
    try {
      org.locationtech.jts.geom.Polygon jtsPolygon = (org.locationtech.jts.geom.Polygon)new GeoJSONReader().read(bot.getObjectMapper().writeValueAsString(polygon));

      org.locationtech.jts.geom.Point centroid = calculateContainedCentroid(jtsPolygon);

      // allow 100 meter diff
      processSingleCoordinateLocation(0.1d, centroid);

      if (!jtsPolygon.intersects(centroid)) {
        throw new RuntimeException("Centroid is not inside of the polygon!");
      }

      createOrPossiblyUpdateCommonGeoshapeArticle(naturvardsregistretObject, jtsPolygon, centroid);
      return null;

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Void visit(MultiPolygon multiPolygon) {
    try {
      org.locationtech.jts.geom.MultiPolygon jtsMultiPolygon = (org.locationtech.jts.geom.MultiPolygon)new GeoJSONReader().read(bot.getObjectMapper().writeValueAsString(multiPolygon));

      org.locationtech.jts.geom.Point centroid = calculateContainedCentroid(jtsMultiPolygon);

      // allow 100 meter diff
      processSingleCoordinateLocation(0.1d, centroid);

      createOrPossiblyUpdateCommonGeoshapeArticle(naturvardsregistretObject, jtsMultiPolygon, centroid);
      return null;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Void visit(GeometryCollection geometryCollection) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Void visit(FeatureCollection featureCollection) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Void visit(Feature feature) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Void visit(MultiLineString multiLineString) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Void visit(LineString lineString) {
    throw new UnsupportedOperationException();
  }

  private void processSingleCoordinateLocation(double coordinateLocationKilometerLeaniency, org.locationtech.jts.geom.Point point) {
    GlobeCoordinatesValue coordinateLocationValue = coordinateLocationValueFactory(point);
    Statement existingCoordinateLocation = bot.getWikiData().findMostRecentPublishedStatement(naturvardsregistretObject.getWikiDataItem(), bot.getWikiData().property("coordinate location"));
    if (existingCoordinateLocation == null) {
      addStatements.add(coordinateLocationStatementFactory(coordinateLocationValue));
    } else {

      TimeValue existingCoordinateReferencePublishedDate = bot.getReferencePublishedDate(existingCoordinateLocation);
      if (existingCoordinateReferencePublishedDate != null
          && bot.getWikiData().toLocalDateTime(existingCoordinateReferencePublishedDate).isAfter(naturvardsregistretObject.getPublishedDate().atTime(0, 0))) {
        log.info("Coordinate published date is fresher at Wikidata than local. Skipping.");
        bot.getProgressEntity().getWarnings().add("Coordinate published date is fresher at Wikidata than local.");

      } else {
        // Be a bit leanient with the coordinate. Compare distance, if less than 100 meters then no need to update
        GlobeCoordinatesValue existingCoordinate = (GlobeCoordinatesValue) existingCoordinateLocation.getValue();
        double kmDistanceBetweenExistingAndLocalCoordinate = ArcDistance.arcDistance(existingCoordinate, coordinateLocationValue);
        if (kmDistanceBetweenExistingAndLocalCoordinate > coordinateLocationKilometerLeaniency) {
          // remove the old coordinate
          deleteStatements.add(existingCoordinateLocation);
          bot.getProgressEntity().getDeletedClaims().add("coordinate");
          log.debug("Will add a new coordinate location. Local data coordinate is {} meters away from existing location in WikiData.", String.format("%f", kmDistanceBetweenExistingAndLocalCoordinate * 1000));
          addStatements.add(coordinateLocationStatementFactory(coordinateLocationValue));
          bot.getProgressEntity().getCreatedClaims().add("coordinate");
        } else {
          log.debug("Will not add new coordinate location. Local data coordinate is only {} meters away from existing location in WikiData.", String.format("%f", kmDistanceBetweenExistingAndLocalCoordinate * 1000));
        }
      }
    }
  }

  private Statement coordinateLocationStatementFactory(GlobeCoordinatesValue coordinatesValue) {
    return bot.addNaturvardsregistretReferences(naturvardsregistretObject, StatementBuilder
        .forSubjectAndProperty(ItemIdValue.NULL, bot.getWikiData().property("coordinate location"))
        .withValue(coordinatesValue)
    ).build();
  }

  private GlobeCoordinatesValue coordinateLocationValueFactory(org.locationtech.jts.geom.Point point) {
    return new GlobeCoordinatesValueImpl(point.getY(), point.getX(),
        GlobeCoordinatesValue.PREC_HUNDRED_MICRO_DEGREE, GlobeCoordinatesValue.GLOBE_EARTH);
  }



  private void createOrPossiblyUpdateCommonGeoshapeArticle(
      NaturvardsregistretObject naturvardsregistretObject,
      Geometry geometry,
      org.locationtech.jts.geom.Point centroid
  ) throws Exception {
/*
     ██████╗ ██████╗ ███╗   ███╗███╗   ███╗ ██████╗ ███╗   ██╗███████╗
    ██╔════╝██╔═══██╗████╗ ████║████╗ ████║██╔═══██╗████╗  ██║██╔════╝
    ██║     ██║   ██║██╔████╔██║██╔████╔██║██║   ██║██╔██╗ ██║███████╗
    ██║     ██║   ██║██║╚██╔╝██║██║╚██╔╝██║██║   ██║██║╚██╗██║╚════██║
    ╚██████╗╚██████╔╝██║ ╚═╝ ██║██║ ╚═╝ ██║╚██████╔╝██║ ╚████║███████║
     ╚═════╝ ╚═════╝ ╚═╝     ╚═╝╚═╝     ╚═╝ ╚═════╝ ╚═╝  ╚═══╝╚══════╝

     ██████╗ ███████╗ ██████╗ ███████╗██╗  ██╗ █████╗ ██████╗ ███████╗
    ██╔════╝ ██╔════╝██╔═══██╗██╔════╝██║  ██║██╔══██╗██╔══██╗██╔════╝
    ██║  ███╗█████╗  ██║   ██║███████╗███████║███████║██████╔╝█████╗
    ██║   ██║██╔══╝  ██║   ██║╚════██║██╔══██║██╔══██║██╔═══╝ ██╔══╝
    ╚██████╔╝███████╗╚██████╔╝███████║██║  ██║██║  ██║██║     ███████╗
     ╚═════╝ ╚══════╝ ╚═════╝ ╚══════╝╚═╝  ╚═╝╚═╝  ╚═╝╚═╝     ╚══════╝
 */

    ObjectNode commonsGeoshapeObject = bot.getObjectMapper().createObjectNode();


    commonsGeoshapeObject.put("license", "CC0-1.0");
    commonsGeoshapeObject.put("sources", "Naturvårdsverket (Swedish Environmental Protection Agency), " + bot.getNaturvardsregistretObjectTypeSourceUrl());

    ObjectNode descriptionNode = bot.getObjectMapper().createObjectNode();
    commonsGeoshapeObject.set("description", descriptionNode);
    descriptionNode.put("sv", naturvardsregistretObject.getName());

    commonsGeoshapeObject.put("longitude", centroid.getX());
    commonsGeoshapeObject.put("latitude", centroid.getY());
    commonsGeoshapeObject.put("zoom", evaluateZoom(geometry));
    commonsGeoshapeObject.set("data", bot.getObjectMapper().readValue(bot.getObjectMapper().writeValueAsString(naturvardsregistretObject.getFeature()), JsonNode.class));

    String commonsGeoshapeObjectJson = bot.getObjectMapper().writeValueAsString(commonsGeoshapeObject);

    String commonsGeoshapeArticleName = bot.commonGeoshapeArticleNameFactory(naturvardsregistretObject);


    Statement existingGeoshape = bot.getWikiData().findMostRecentPublishedStatement(naturvardsregistretObject.getWikiDataItem(), bot.getWikiData().property("geoshape"));
    TimeValue existingGeoShapeReferencePublishedDate = bot.getReferencePublishedDate(existingGeoshape);
    if (existingGeoShapeReferencePublishedDate != null
        && bot.getWikiData().toLocalDateTime(existingGeoShapeReferencePublishedDate).isAfter(naturvardsregistretObject.getPublishedDate().atTime(0, 0))) {
        log.info("Geoshape publish date is fresher at Wikidata than local. Skipping.");
        bot.getProgressEntity().getWarnings().add("Geoshape publish date is fresher at Wikidata than local.");
    } else {

      if (existingGeoshape != null
          && !existingGeoshape.getValue().equals(geoshapeValueFactory(commonsGeoshapeArticleName))) {
        log.debug("Download previously existing Commons geoshape article.");
        Article commonsGeoShapeArticle = bot.getWikiBot().getArticle(commonsGeoshapeArticleName);
        if (commonsGeoShapeArticle.getRevisionId().isEmpty()) {
          log.warn("WikiData points at a non existing geoshape at Commons");
          createOrPossiblyUpdateCommonGeoshapeArticle(commonsGeoshapeObject, commonsGeoshapeObjectJson, commonsGeoshapeArticleName);
        } else {
          ObjectNode existingCommonsObject = bot.getObjectMapper().readValue(commonsGeoShapeArticle.getText(), ObjectNode.class);
          if (!commonsGeoshapeObject.equals(existingCommonsObject)) {
            log.debug("Current Commons geoshape article is not up to date.");
            createOrPossiblyUpdateCommonGeoshapeArticle(commonsGeoshapeObject, commonsGeoshapeObjectJson, commonsGeoshapeArticleName);
            addStatements.add(geoshapeStatementFactory(naturvardsregistretObject, commonsGeoshapeArticleName));
            bot.getProgressEntity().getCreatedClaims().add("geoshape");
          } else {
            log.debug("Current Commons geoshape article is up to date. No need to update.");
          }
        }
      } else if (existingGeoshape != null){
        log.debug("Existing Commons geoshape article in WS item with the same name as we would give it");
        createOrPossiblyUpdateCommonGeoshapeArticle(commonsGeoshapeObject, commonsGeoshapeObjectJson, commonsGeoshapeArticleName);
      } else {
        log.debug("No existing Commons geoshape article in WD item.");
        createOrPossiblyUpdateCommonGeoshapeArticle(commonsGeoshapeObject, commonsGeoshapeObjectJson, commonsGeoshapeArticleName);
        addStatements.add(geoshapeStatementFactory(naturvardsregistretObject, commonsGeoshapeArticleName));
        bot.getProgressEntity().getCreatedClaims().add("geoshape");
      }
    }
  }

  private void createOrPossiblyUpdateCommonGeoshapeArticle(
      ObjectNode commonsGeoshapeObject,
      String commonsGeoshapeObjectJson,
      String commonsGeoshapeArticleName
  ) {

    log.trace("Handle geoshape article in Commons");
    Article commonsGeoShapeArticle = bot.getWikiBot().getArticle(commonsGeoshapeArticleName);
    if (commonsGeoShapeArticle.getRevisionId().isEmpty()) {
      log.debug("Creating new Commons article {}", commonsGeoshapeArticleName);
      commonsGeoShapeArticle.setText(commonsGeoshapeObjectJson);
      commonsGeoShapeArticle.setEditSummary("Initial creation using data from Naturvårdsverket.");
      if (!bot.isDryRun()) {
        commonsGeoShapeArticle.save();
        log.info("Committed new Commons article {}", commonsGeoshapeArticleName);
      }
      bot.getProgressEntity().setCreatedCommonsGeoshape(true);
    } else {
      log.debug("Already existing Commons article {}", commonsGeoshapeArticleName);
      log.debug("Checking for diff between remote and local data...");
      ObjectNode existingCommonsObject;
      try {
        existingCommonsObject = bot.getObjectMapper().readValue(commonsGeoShapeArticle.getText(), ObjectNode.class);
      } catch (Exception ioe) {
        log.warn("Invalid JSON object in Commons article {}", commonsGeoshapeArticleName);
        existingCommonsObject = null;
        // todo error rather than replace it?! Perhaps at least an alternative commit message in commons?
      }
      if (commonsGeoshapeObject.equals(existingCommonsObject)) {
        log.debug("No changes to {}", commonsGeoshapeArticleName);
      } else {
        log.debug("Updating {}", commonsGeoshapeArticleName);
        commonsGeoShapeArticle.setText(commonsGeoshapeObjectJson);
        commonsGeoShapeArticle.setEditSummary("Updated using data from Naturvårdsverket due to detected difference with local data.");
        if (!bot.isDryRun()) {
          commonsGeoShapeArticle.save();
          log.info("Committed update of Commons article {}", commonsGeoshapeArticleName);
        }
        bot.getProgressEntity().setUpdatedCommonsGeoshape(true);
      }
    }


    log.trace("Handle categories in geoshape discussion page on Commons.");
    String commonsGeoShapeArticleTalkText;
    {
      StringBuilder commonsGeoShapeArticleTalkTextBuilder = new StringBuilder(1024);
      for (String category : bot.getCommonsArticleCategories(naturvardsregistretObject)) {
        commonsGeoShapeArticleTalkTextBuilder.append("[[Category:").append(category).append("]]\n");
      }
      commonsGeoShapeArticleTalkText = commonsGeoShapeArticleTalkTextBuilder.toString().trim();
    }

    String commonsGeoshapeArticleTalkName = commonsGeoshapeArticleName.replaceFirst("Data:", "Data_talk:");
    Article commonsGeoShapeArticleTalk = bot.getWikiBot().getArticle(commonsGeoshapeArticleTalkName);
    if (commonsGeoShapeArticleTalk.getRevisionId().isEmpty()) {
      log.debug("Creating new Commons article {}", commonsGeoshapeArticleTalkName);
      commonsGeoShapeArticleTalk.setText(commonsGeoShapeArticleTalkText);
      commonsGeoShapeArticleTalk.setEditSummary("Initial creation using data from Naturvårdsverket.");
      if (!bot.isDryRun()) {
        commonsGeoShapeArticleTalk.save();
        log.info("Committed new Commons article {}", commonsGeoshapeArticleTalkName);
      }

    } else {
      if (!commonsGeoShapeArticleTalkText.equals(commonsGeoShapeArticleTalk.getText())) {
        // todo This will replace any categories which was added by third parties!
        // todo I.e. we need to actually parse and find delta!
        log.debug("Updating {}", commonsGeoshapeArticleTalkName);
        commonsGeoShapeArticleTalk.setText(commonsGeoShapeArticleTalkText);
        commonsGeoShapeArticleTalk.setEditSummary("Updated using data from Naturvårdsverket due to detected difference with local data.");
        if (!bot.isDryRun()) {
          commonsGeoShapeArticleTalk.save();
          log.info("Committed update of Commons article {}", commonsGeoshapeArticleTalkName);
        }

      } else {
        log.debug("No changes to {}", commonsGeoshapeArticleTalkName);
      }
    }
  }


  private Statement geoshapeStatementFactory(NaturvardsregistretObject naturvardsregistretObject, String commonsGeoshapeArticleName) {
    return bot.addNaturvardsregistretReferences(naturvardsregistretObject, StatementBuilder
        .forSubjectAndProperty(ItemIdValue.NULL, bot.getWikiData().property("geoshape"))
        .withValue(geoshapeValueFactory(commonsGeoshapeArticleName))
    ).build();
  }

  private StringValueImpl geoshapeValueFactory(String commonsGeoshapeArticleName) {
    return new StringValueImpl(commonsGeoshapeArticleName);
  }

  private int evaluateZoom(Geometry geometry) {
    int zoom;
    org.locationtech.jts.geom.Polygon envelope = (org.locationtech.jts.geom.Polygon) geometry.getEnvelope();
    double kmDiagonal = ArcDistance.arcDistance(envelope.getCoordinates()[0].y, envelope.getCoordinates()[0].x, envelope.getCoordinates()[2].y, envelope.getCoordinates()[2].x);

    // so this is some crappy hard coded stuff
    if (kmDiagonal < 1) {
      zoom = 13;
    } else if (kmDiagonal < 4) {
      zoom = 12;
    } else if (kmDiagonal < 16) {
      zoom = 11;
    } else if (kmDiagonal < 64) {
      zoom = 10;
    } else if (kmDiagonal < 256) {
      zoom = 9;
    } else if (kmDiagonal < 1024) {
      zoom = 8;
    } else {
      zoom = 7;
      log.warn("Developer error: Too large area to come up with a good zoom value");
    }
    return zoom;
  }

  private org.locationtech.jts.geom.Point calculateContainedCentroid(Geometry geometry) {
    org.locationtech.jts.geom.Point centroid = geometry.getCentroid();
    if (!geometry.contains(centroid) && !geometry.intersects(centroid)) {
      // find closest vertex
      double closestDistance = Double.MAX_VALUE;
      org.locationtech.jts.geom.Point closestPoint = centroid;
      for (Coordinate coordinate : geometry.getCoordinates()) {
        org.locationtech.jts.geom.Point point = bot.getGeometryFactory().createPoint(coordinate);
        double distance = centroid.distance(point);
        if (distance < closestDistance) {
          closestDistance = distance;
          closestPoint = point;
        }
      }
      if (!geometry.intersects(closestPoint) && !geometry.contains(closestPoint)) {
        throw new RuntimeException("Unable to find a centroid inside of the geometry!");
      }
      centroid = closestPoint;
    }
    return centroid;
  }

}
