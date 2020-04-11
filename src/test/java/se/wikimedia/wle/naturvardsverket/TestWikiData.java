package se.wikimedia.wle.naturvardsverket;

import org.junit.Assert;
import org.junit.Test;
import org.wikidata.wdtk.datamodel.helpers.StatementBuilder;
import org.wikidata.wdtk.datamodel.implementation.EntityIdValueImpl;
import org.wikidata.wdtk.datamodel.implementation.ItemIdValueImpl;
import org.wikidata.wdtk.datamodel.interfaces.NoValueSnak;
import org.wikidata.wdtk.datamodel.interfaces.PropertyIdValue;
import org.wikidata.wdtk.datamodel.interfaces.SomeValueSnak;
import org.wikidata.wdtk.datamodel.interfaces.Statement;

public class TestWikiData {

  @Test
  public void testAsStatementBuilder() throws Exception {
    WikiData wikiData = new WikiData(null, null, null, null, null);

    {
      Statement created = StatementBuilder
          .forSubjectAndProperty(ItemIdValueImpl.NULL, (PropertyIdValue) EntityIdValueImpl.fromId("P41", "http://www.wikidata.org/entity/"))
          .withNoValue()
          .build();

      Statement clone = wikiData.asStatementBuilder(created).build();

      Assert.assertTrue(clone.getMainSnak() instanceof NoValueSnak);
    }

    {
      Statement created = StatementBuilder
          .forSubjectAndProperty(ItemIdValueImpl.NULL, (PropertyIdValue) EntityIdValueImpl.fromId("P41", "http://www.wikidata.org/entity/"))
          .withSomeValue()
          .build();

      Statement clone = wikiData.asStatementBuilder(created).build();

      Assert.assertTrue(clone.getMainSnak() instanceof SomeValueSnak);
    }


    System.currentTimeMillis();
  }

}
