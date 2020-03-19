package se.wikimedia.wle.naturvardsverket;

import org.wikidata.wdtk.datamodel.interfaces.EntityIdValue;
import org.wikidata.wdtk.datamodel.interfaces.ValueVisitor;

public class NonExistingEntityIdValue implements EntityIdValue {

  @Override
  public String getEntityType() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getSiteIri() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getIri() {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T accept(ValueVisitor<T> valueVisitor) {
    throw new UnsupportedOperationException();
  }
}
