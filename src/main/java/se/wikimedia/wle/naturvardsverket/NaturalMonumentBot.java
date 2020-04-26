package se.wikimedia.wle.naturvardsverket;

import org.wikidata.wdtk.datamodel.interfaces.EntityIdValue;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class NaturalMonumentBot extends AbstractNaturvardsregistretBot {

  public static void main(String[] args) throws Exception {
    NaturalMonumentBot bot = new NaturalMonumentBot();
    bot.open();
    try {
      bot.execute();
    } finally {
      bot.close();
    }
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
    String lan = (String)object.getFeature().getProperties().get("LAN");
    lan = lan.replaceFirst("Län", "län");
    if ("sv".equals(language)) {
      return "naturminne i " + lan;
    } else if ("en".equals(language)) {
      return "natural monument in " + lan + ", Sweden";
    } else {
      throw new IllegalArgumentException("Unsupported language: " + language);
    }
  }


  @Override
  public String[] getCommonsArticleCategories(NaturvardsregistretObject object) {
    return new String[]{
        "Map data of natural monuments of Sweden|"+object.getName()
    };
  }

  @Override
  protected String getNaturvardsregistretObjectTypeSourceUrl() {
    return "https://metadatakatalogen.naturvardsverket.se/metadatakatalogen/GetMetaDataById?id=c6b02e88-8084-4b3f-8a7d-33e5d45349c4";
  }

  @Override
  protected File[] getNaturvardsregistretGeoJsonFiles() {
    return new File[]{
        new File("data/4326/naturminne_polygon.geojson"),
        new File("data/4326/naturminne_punkt.geojson"),
    };
  }

  /**
   * @return Q-nature reserve
   */
  protected String getNaturvardsregistretObjectTypeEntityId() {
    return "Q23790";
  }

  @Override
  public String commonGeoshapeArticleNameFactory(NaturvardsregistretObject object) {
    return "Data:"
        + (isSandbox() ? "Sandbox/KarlWettin-WMSE" : "")
        + "/Sweden/Natural monuments"
        + "/" + object.getPublishedDate().getYear()
        + "/" + normalizeArticleNameForCommons(object.getName())
        + "/" + object.getNvrid()
        + ".map";
  }

}
