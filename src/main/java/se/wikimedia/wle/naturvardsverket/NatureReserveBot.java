package se.wikimedia.wle.naturvardsverket;

import org.locationtech.jts.geom.Point;

import java.io.File;

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

  private String[] commonsArticleCategories = new String[]{
      "Map data of Sweden",
      "Map data of protected areas of Sweden",
      "Map data of nature reserves of Sweden"
  };

  @Override
  public String[] getCommonsArticleCategories() {
    return commonsArticleCategories;
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
