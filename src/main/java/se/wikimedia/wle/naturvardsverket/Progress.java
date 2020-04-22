package se.wikimedia.wle.naturvardsverket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import lombok.Data;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class Progress {

  public static void main(String[] args) throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    Progress progress = objectMapper.readValue(new File("data/progress/NatureReserveBot.json"), Progress.class);
    System.currentTimeMillis();

  }

  public void add(Entity entity) {
    processed.put(entity.getNvrid(), entity);
  }

  private Map<String, Entity> processed = new HashMap<>();

  @Data
  public static class Entity {
    private String nvrid;

    private String wikidataIdentity;

    private boolean skipped;

    private Long epochStarted;
    private Long epochEnded;

    private boolean createdWikidata;

    private List<String> createdClaims = new ArrayList<>();
    private List<String> modifiedClaims = new ArrayList<>();
    private List<String> deletedClaims = new ArrayList<>();

    private boolean createdCommonsGeoshape;
    private boolean updatedCommonsGeoshape;

    private List<String> warnings = new ArrayList<>();

    private String error;

    private Entity previousExecution;

  }

}
