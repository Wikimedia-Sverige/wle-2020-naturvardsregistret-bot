package se.wikimedia.wle.naturvardsverket;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Getter;
import lombok.Setter;
import net.sourceforge.jwbf.core.actions.HttpActionClient;
import net.sourceforge.jwbf.mediawiki.bots.MediaWikiBot;
import org.locationtech.jts.geom.GeometryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public abstract class AbstractBot {

  private Logger log = LoggerFactory.getLogger(getClass());

  /**
   * If true, then
   * all creation will be in sandbox rather than real commons pages,
   * nor will anything be updated at WikiData.
   */
  @Getter
  @Setter
  private boolean sandbox = true;

  @Getter
  @Setter
  private boolean dryRun = true;

  @Getter
  @Setter
  private boolean downloadReferencedWikiDataEntityIdValues = true;

  private String username;
  private String password;
  private String emailAddress;

  private String userAgent;
  private String userAgentVersion;

  public AbstractBot(String userAgent, String userAgentVersion) {

    if (Pattern.compile("[ /]").matcher(userAgent).find()) {
      throw new RuntimeException("User-Agent must not contain spaces or slashes");
    }

    this.userAgent = userAgent;
    this.userAgentVersion = userAgentVersion;
  }

  protected abstract void execute() throws Exception;

  @Getter
  protected MediaWikiBot wikiBot;

  @Getter
  protected WikiData wikiData;

  @Getter
  private ObjectMapper objectMapper;

  @Getter
  private GeometryFactory geometryFactory;

  public void open() throws Exception {
    log.debug("Opening bot {}", getClass().getSimpleName());
    if (username == null) {
      username = System.getenv("mwse-bot.username");
      if (username == null) {
        throw new NullPointerException("Missing environment variable mwse-bot.username");
      }
    }
    if (password == null) {
      password = System.getenv("mwse-bot.password");
      if (password == null) {
        throw new NullPointerException("Missing environment variable mwse-bot.password");
      }
    }
    if (emailAddress == null) {
      emailAddress = System.getenv("mwse-bot.email");
      if (emailAddress == null) {
        throw new NullPointerException("Missing environment variable mwse-bot.email");
      }
    }

    HttpActionClient client = HttpActionClient.builder() //
        .withUrl("https://commons.wikimedia.org/w/") //
        .withUserAgent(userAgent, userAgentVersion, emailAddress) //
        .withRequestsPerUnit(10, TimeUnit.MINUTES) //
        .build();

    wikiBot = new MediaWikiBot(client);
    wikiBot.login(username, password);

    wikiData = new WikiData(userAgent, userAgentVersion, emailAddress, username, password);
    wikiData.setDefaultRequireEntityIdValue(downloadReferencedWikiDataEntityIdValues);
    wikiData.open();


    objectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .enable(SerializationFeature.INDENT_OUTPUT);

    geometryFactory = new GeometryFactory();

    log.info("Opened bot {} using WikiMedia account {} <mailto:{}>", getClass().getSimpleName(), getUsername(), getEmailAddress());

  }

  public void close() throws Exception {
    wikiData.close();
  }

  public static String normalizeArticleNameForCommons(String input) {
    input = input.trim();
    input = input.replaceAll("\\p{Punct}", "_");
    return input;
  }



  public String getUsername() {
    return username;
  }

  public String getEmailAddress() {
    return emailAddress;
  }
}
