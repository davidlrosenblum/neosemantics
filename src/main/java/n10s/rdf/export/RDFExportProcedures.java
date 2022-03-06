package n10s.rdf.export;

import static n10s.graphconfig.GraphConfig.GRAPHCONF_VOC_URI_IGNORE;
import static n10s.graphconfig.GraphConfig.GRAPHCONF_VOC_URI_MAP;
import static n10s.mapping.MappingUtils.getExportMappingsFromDB;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import n10s.graphconfig.GraphConfig;
import n10s.graphconfig.GraphConfig.GraphConfigNotFound;
import n10s.rdf.RDFProcedures;
import n10s.result.StreamedStatement;
import n10s.utils.InvalidNamespacePrefixDefinitionInDB;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Triple;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

public class RDFExportProcedures extends RDFProcedures {

  @Context
  public GraphDatabaseService db;

  @Context
  public Transaction tx;

  @Context
  public Log log;


  @Procedure(mode = Mode.READ)
  @Description(
      "Executes a cypher query returning graph elements (nodes,rels) and serialises "
          + "the output as triples.")
  public Stream<StreamedStatement> cypher(@Name("cypher") String cypher,
      @Name(value = "params", defaultValue = "{}") Map<String, Object> props)
      throws InvalidNamespacePrefixDefinitionInDB {

    ExportProcessor proc;

    //by default we return props in rels as rdf-star
    boolean returnPropsInRels = (props.containsKey("includeRelProperties")? (boolean)props.get("includeRelProperties"):true);
    GraphConfig gc = getGraphConfig(tx);
    if (gc == null || gc.getHandleVocabUris() == GRAPHCONF_VOC_URI_IGNORE
            || gc.getHandleVocabUris() == GRAPHCONF_VOC_URI_MAP) {
      proc = new LPGToRDFProcesssor(db, tx, gc,
          getExportMappingsFromDB(db), props.containsKey("mappedElemsOnly") &&
          props.get("mappedElemsOnly").equals(true), returnPropsInRels);
    } else {
      proc = new LPGRDFToRDFProcesssor(db, tx, gc,  returnPropsInRels);
    }
    return proc.streamTriplesFromCypher(cypher,
        (props.containsKey("cypherParams") ? (Map<String, Object>) props.get("cypherParams") :
            new HashMap<>())).map(st -> new StreamedStatement(
        st.getSubject().stringValue(), st.getPredicate().stringValue(),
        st.getObject().stringValue(), st.getObject() instanceof Literal,
        (st.getObject() instanceof Literal ? ((Literal) st.getObject()).getDatatype().stringValue()
            : null),
        (st.getObject() instanceof Literal ? ((Literal) st.getObject()).getLanguage().orElse(null)
            : null),
        (st.getSubject() instanceof Triple ? Stream.of(((Triple)st.getSubject()).getSubject().stringValue(),
                ((Triple)st.getSubject()).getPredicate().stringValue(),
                ((Triple)st.getSubject()).getObject().stringValue())
                .collect(Collectors.toList())
                    : null)
    ));

  }


  @Procedure(mode = Mode.READ)
  @Description(
      "Returns the triples matching the spo pattern passed as parameter.")
  public Stream<StreamedStatement> spo(@Name("subject") String subject,
      @Name("predicate") String predicate, @Name("object") String object,
      @Name(value = "isLiteral", defaultValue = "false") Boolean isLiteral, @Name(value = "literalType",
          defaultValue = "http://www.w3.org/2001/XMLSchema#string") String literalType,
      @Name(value = "literalLang", defaultValue = "null") String literalLang,
      @Name(value = "params", defaultValue = "{}") Map<String,Object> props)
      throws InvalidNamespacePrefixDefinitionInDB {

    ExportProcessor proc;

    boolean rdfstar = props.containsKey("includeRelProperties") && props.get("includeRelProperties").equals(true);

    GraphConfig gc = getGraphConfig(tx);
    if (gc == null || gc.getHandleVocabUris() == GRAPHCONF_VOC_URI_IGNORE
            || gc.getHandleVocabUris() == GRAPHCONF_VOC_URI_MAP) {
      proc = new LPGToRDFProcesssor(db, tx, gc,
          getExportMappingsFromDB(db), props.containsKey("mappedElemsOnly") &&
          props.get("mappedElemsOnly").equals(true), rdfstar);
    } else {
      proc = new LPGRDFToRDFProcesssor(db, tx, gc, rdfstar);
    }
    return proc.streamTriplesFromTriplePattern( new TriplePattern( subject, predicate, object,
            (isLiteral==null?false:isLiteral), (literalType==null?"http://www.w3.org/2001/XMLSchema#string":literalType),
            (literalLang==null || literalLang.equals("null")?null:literalLang))).map(st -> new StreamedStatement(
        st.getSubject().stringValue(), st.getPredicate().stringValue(),
        st.getObject().stringValue(), st.getObject() instanceof Literal,
        (st.getObject() instanceof Literal ? ((Literal) st.getObject()).getDatatype().stringValue()
            : null),
        (st.getObject() instanceof Literal ? ((Literal) st.getObject()).getLanguage().orElse(null)
            : null)
    ));

  }


  private GraphConfig getGraphConfig(Transaction tx) {
    GraphConfig result = null;
    try {
      result = new GraphConfig(tx);
    } catch (GraphConfigNotFound graphConfigNotFound) {
      //it's an LPG (no RDF import config)
    }
    return result;
  }

}

