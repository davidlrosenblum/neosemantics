package n10s.experimental;

import n10s.graphconfig.RDFParserConfig;
import n10s.rdf.load.DirectStatementLoader;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.logging.Log;

public class PlainJsonStatementLoader extends DirectStatementLoader {

  PlainJsonStatementLoader(GraphDatabaseService db, Transaction tx, RDFParserConfig conf,
      Log l) {
    super(db, tx, conf, l);
  }

  @Override
  public void endRDF() throws RDFHandlerException {
    try {
      this.runPartialTx(tx);
      totalTriplesMapped += mappedTripleCounter;
      log.debug("JSON Import complete: " + totalTriplesMapped + "  statements imported");
    } catch (Exception e) {
      log.error("Error importing JSON: " + e.getMessage());
    }
  }

}
