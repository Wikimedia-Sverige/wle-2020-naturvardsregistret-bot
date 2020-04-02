package se.wikimedia.wle.naturvardsverket;

import org.wikidata.wdtk.datamodel.interfaces.EntityIdValue;

import java.io.File;

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

  private String[] commonsArticleCategories = new String[]{
      "Map data of Sweden",
      "Map data of protected areas of Sweden",
      "Map data of natural monuments of Sweden"
  };

  @Override
  public String[] getCommonsArticleCategories() {
    return commonsArticleCategories;
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
        + "/" + object.getName()
        + "/" + object.getNvrid()
        + ".map";
  }

}
