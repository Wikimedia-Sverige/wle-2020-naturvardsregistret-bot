package se.wikimedia.wle.naturvardsverket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

  private static Logger log = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
//    runBot(new NatureReserveBot());
//    runBot(new NationalParkBot());
    runBot(new NaturalMonumentBot());
  }

  private static void runBot(AbstractNaturvardsregistretBot bot) throws Exception {
    bot.setDryRun(false);
    bot.setSandbox(false);
    // todo do ensure when running!
    bot.setDownloadReferencedWikiDataEntityIdValues(false);
    bot.open();
    try {
      bot.execute();
    } finally {
      bot.close();
    }

  }

}

