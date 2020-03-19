package se.wikimedia.wle.naturvardsverket;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import org.geojson.Feature;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.wikidata.wdtk.datamodel.interfaces.EntityIdValue;
import org.wikidata.wdtk.datamodel.interfaces.ItemDocument;

import java.time.LocalDate;

@Data
public class NaturvardsregistretObject {
  private Feature feature;

  // index of above

  private LocalDate retrievedDate;
  private LocalDate publishedDate;

  private String nvrid;
  private String name;

  // created from above

  private EntityIdValue operatorWikiDataItem;

  private String wikiDataObjectKey;
  private ItemDocument wikiDataItem;


}
