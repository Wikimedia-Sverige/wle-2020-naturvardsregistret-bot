package se.wikimedia.wle.naturvardsverket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

  private static Logger log = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
    runBot(new NatureReserveBot());
  }

  private static void runBot(AbstractBot bot) throws Exception {
    log.info("Executing bot {} using WikiMedia account {} <mailto:{}>", bot.getClass().getSimpleName(), bot.getUsername(), bot.getEmailAddress());
    bot.open();
    try {
      bot.execute();
    } finally {
      bot.close();
    }

  }

}

