package se.wikimedia.wle.naturvardsverket;

import java.io.File;

public class NationalParkBot extends AbstractNaturvardsregistretBot {

  public static void main(String[] args) throws Exception {
    NationalParkBot bot = new NationalParkBot();
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
      "Map data of national parks of Sweden"
  };

  @Override
  public String[] getCommonsArticleCategories() {
    return commonsArticleCategories;
  }

  @Override
  protected String getNaturvardsregistretObjectTypeSourceUrl() {
    return "https://metadatakatalogen.naturvardsverket.se/metadatakatalogen/GetMetaDataById?id=bfc33845-ffb9-4835-8355-76af3773d4e0";
  }

  @Override
  protected File[] getNaturvardsregistretGeoJsonFiles() {
    return new File[]{
        new File("data/4326/nationalparker.geojson")
    };
  }

  /**
   * @return Q-national park
   */
  protected String getNaturvardsregistretObjectTypeEntityId() {
    return "Q46169";
  }

  @Override
  public String commonGeoshapeArticleNameFactory(NaturvardsregistretObject object) {
    return "Data:"
        + (isSandbox() ? "Sandbox/KarlWettin-WMSE" : "")
        + "/Sweden/National parks"
        + "/" + object.getPublishedDate().getYear()
        + "/" + object.getName()
        + "/" + object.getNvrid()
        + ".map";
  }

}
