package se.wikimedia.wle.naturvardsverket;

import org.locationtech.jts.geom.Point;
import org.wikidata.wdtk.datamodel.interfaces.EntityIdValue;
import org.wikidata.wdtk.datamodel.interfaces.PropertyIdValue;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class NatureReserveBot extends AbstractNaturvardsregistretBot {

  public static void main(String[] args) throws Exception {
    NatureReserveBot bot = new NatureReserveBot();
    bot.open();
    try {
      bot.execute();
    } finally {
      bot.close();
    }
  }

  @Override
  public String[] getCommonsArticleCategories(NaturvardsregistretObject object) {
    return new String[]{
        "Map data of nature reserves of Sweden|"+object.getName()
    };
  }

  @Override
  protected String getNaturvardsregistretObjectTypeSourceUrl() {
    return "https://metadatakatalogen.naturvardsverket.se/metadatakatalogen/GetMetaDataById?id=2921b01a-0baf-4702-a89f-9c5626c97844";
  }

  @Override
  protected File[] getNaturvardsregistretGeoJsonFiles() {
    return new File[]{
        new File("data/4326/naturreservat.geojson")
    };
  }

  /**
   * @return Q-nature reserve
   */
  protected String getNaturvardsregistretObjectTypeEntityId() {
    return "Q179049";
  }

  private Set<String> supportedDescriptionLanguages = new HashSet<>(Arrays.asList(
      "sv", "en"
  ));

  @Override
  protected Collection<String> getSupportedDescriptionLanguages() {
    return supportedDescriptionLanguages;
  }

  @Override
  protected String getDescription(NaturvardsregistretObject object, String language) {
    if ("sv".equals(language)) {
      return "naturreservat i " + (String)object.getFeature().getProperties().get("LAN");
    } else if ("en".equals(language)) {
      return "nature reserve in " + (String)object.getFeature().getProperties().get("LAN") + ", Sweden";
    } else {
      throw new IllegalArgumentException("Unsupported language: " + language);
    }
  }


  @Override
  public String commonGeoshapeArticleNameFactory(NaturvardsregistretObject object) {
    return "Data:"
        + (isSandbox() ? "Sandbox/KarlWettin-WMSE" : "")
        + "/Sweden/Nature reserves"
        + "/" + object.getPublishedDate().getYear()
        + "/" + object.getName()
        + "/" + object.getNvrid()
        + ".map";
  }

}
