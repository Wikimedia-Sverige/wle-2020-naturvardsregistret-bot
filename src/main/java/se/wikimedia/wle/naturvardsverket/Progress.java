package se.wikimedia.wle.naturvardsverket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import lombok.Data;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Data
public class Progress {

  private static class Counters {
    private Map<String, AtomicInteger> counters = new HashMap<>();
    public void increment(String key) {
      AtomicInteger counter = counters.get(key);
      if (counter == null) {
        counter = new AtomicInteger();
        counters.put(key, counter);
      }
      counter.incrementAndGet();
    }
    @Override
    public String toString() {
      List<Map.Entry<String, AtomicInteger>> ordered = new ArrayList<>(counters.entrySet());
      ordered.sort(new Comparator<Map.Entry<String, AtomicInteger>>() {
        @Override
        public int compare(Map.Entry<String, AtomicInteger> o1, Map.Entry<String, AtomicInteger> o2) {
          return o2.getValue().get() - o1.getValue().get();
        }
      });
      StringBuilder sb = new StringBuilder();
      for (Map.Entry<String, AtomicInteger> counter : ordered) {
        sb.append(counter.getKey());
        sb.append("\t");
        sb.append(counter.getValue().get());
        sb.append("\n");
      }
      return sb.toString();
    }
  }

  public static void main(String[] args) throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    Progress progress = objectMapper.readValue(new File("data/progress/NaturalMonumentBot.json"), Progress.class);

    Counters counters = new Counters();
    Set<String> counterKeys = new HashSet<>();
    for (Progress.Entity entity : progress.getProcessed().values()) {
      counterKeys.clear();
      if (entity.getError() != null) {
        counterKeys.add("Failed to process");
      }
      while (entity != null) {
        if (entity.isCreatedCommonsGeoshape()) {
          counterKeys.add("Created Commons geoshape");
        }
        if (entity.isCreatedWikidata()) {
          counterKeys.add("Created Wikidata item");
        }
        for (String claim : entity.getCreatedClaims()) {
          counterKeys.add("Modified Wikidata claim " + claim);
        }
        for (String claim : entity.getModifiedClaims()) {
          counterKeys.add("Modified Wikidata claim " + claim);
        }
        entity = entity.getPreviousExecution();
      }
      for (String key : counterKeys) {
        counters.increment(key);
      }
      counters.increment("Item processed");
    }

    System.out.println(counters);
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
