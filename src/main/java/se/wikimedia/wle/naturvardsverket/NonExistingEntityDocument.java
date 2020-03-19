package se.wikimedia.wle.naturvardsverket;

import org.wikidata.wdtk.datamodel.interfaces.EntityDocument;
import org.wikidata.wdtk.datamodel.interfaces.EntityIdValue;

public class NonExistingEntityDocument implements EntityDocument {

  @Override
  public EntityIdValue getEntityId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getRevisionId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public EntityDocument withRevisionId(long newRevisionId) {
    throw new UnsupportedOperationException();
  }
}
