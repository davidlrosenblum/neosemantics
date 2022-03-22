package n10s.nsprefixes;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import n10s.result.NamespacePrefixesResult;
import n10s.utils.InvalidNamespacePrefixDefinitionInDB;
import n10s.utils.NamespacePrefixConflictException;
import n10s.utils.NsPrefixMap;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

public class NsPrefixDefProcedures {

  @Context
  public GraphDatabaseService db;

  @Context
  public Transaction tx;

  @Context
  public Log log;

  @Procedure(mode = Mode.WRITE)
  @Description("Adds namespace - prefix pair definition")
  public Stream<NamespacePrefixesResult> add(@Name("prefix") String prefix,
      @Name("ns") String ns)
      throws InvalidNamespacePrefixDefinitionInDB, NamespacePrefixConflictException {
    NsPrefixMap map = new NsPrefixMap(tx, true);
    map.add(prefix, ns);
    map.flushToDB(tx);

    return map.getPrefixToNs().entrySet().stream()
        .map(n -> new NamespacePrefixesResult(n.getKey(), n.getValue()));

  }

  @Procedure(mode = Mode.WRITE)
  @Description("removes namespace prefix (by prefix)")
  public Stream<NamespacePrefixesResult> remove(@Name("prefix") String prefix)
      throws InvalidNamespacePrefixDefinitionInDB, NsPrefixOperationNotAllowed {
    if (!graphIsEmpty()) {
      throw new NsPrefixOperationNotAllowed("A namespace prefix definition cannot be removed "
          + "when the graph is non-empty.");
    } else if (mappingsDefinedOnNamespace(prefix)){
      throw new NsPrefixOperationNotAllowed("This namespace prefix definition cannot be removed "
          + "because mapping definitions based on it exist. Remove mappings first and try again.");
    }
    NsPrefixMap map = new NsPrefixMap(tx, true);
    map.removePrefix(prefix);
    map.flushToDB(tx);

    return map.getPrefixToNs().entrySet().stream()
        .map(n -> new NamespacePrefixesResult(n.getKey(), n.getValue()));

  }

  private boolean mappingsDefinedOnNamespace(String prefix) {
    Map<String,Object> params = new HashMap<>();
    params.put("prefix", prefix);
    return ((Long)tx
        .execute("MATCH (n:`_MapNs` { _prefix: $prefix }) RETURN count(n) as ct", params).next().get("ct")) >  0;
  }

  @Procedure(mode = Mode.WRITE)
  @Description("removes all namespace prefixes")
  public Stream<NamespacePrefixesResult> removeAll()
      throws InvalidNamespacePrefixDefinitionInDB, NsPrefixOperationNotAllowed {
    if (!graphIsEmpty()) {
      throw new NsPrefixOperationNotAllowed("Namespace prefix definitions cannot be removed "
          + "when the graph is non-empty.");
    }

    ResourceIterator<Node> namespacePrefixDefinitionNodes = tx
        .findNodes(Label.label("_NsPrefDef"));

    if (namespacePrefixDefinitionNodes.hasNext()) {
      namespacePrefixDefinitionNodes.next().delete();
    }

    return Stream.empty();

  }

  @Procedure(mode = Mode.WRITE)
  @Description("Adds namespaces from a prefix declaration header fragment")
  public Stream<NamespacePrefixesResult> addFromText(
      @Name("prefix") String textFragment)
      throws InvalidNamespacePrefixDefinitionInDB, NamespacePrefixConflictException {

    //Try Turtle fragment
    Pattern turtleNamespaceDefinitionRegex =
        Pattern.compile("(?i)@prefix (\\S+)\\:\\s*<(\\S*)>", Pattern.MULTILINE);
    if (tryExtractNsDefinitions(textFragment, turtleNamespaceDefinitionRegex)) {
      return list();
    }

    //Try RDF/XML fragment
    Pattern rdfxmlNamespaceDefinitionRegex =
        Pattern.compile("xmlns:(\\S+)\\s*=\\s*\\\"(\\S*)\\\"", Pattern.MULTILINE);
    if (tryExtractNsDefinitions(textFragment, rdfxmlNamespaceDefinitionRegex)) {
      return list();
    }
    //try sparql
    Pattern sparqlNamespaceDefinitionRegex =
        Pattern.compile("(?i)prefix\\s+(\\S+)\\:\\s+<(\\S*)>", Pattern.MULTILINE);
    if (tryExtractNsDefinitions(textFragment, sparqlNamespaceDefinitionRegex)) {
      return list();
    }

    // unclear how to make it safe with jsonld while keeping it simple

    return list();

  }

  private boolean tryExtractNsDefinitions(@Name("prefix") String textFragment,
      Pattern pattern)
      throws InvalidNamespacePrefixDefinitionInDB, NamespacePrefixConflictException {
    Matcher m;
    m = pattern.matcher(textFragment);
    while (m.find()) {
      add(m.group(1).replace("-", "_"), m.group(2));
    }
    return m.matches();
  }

  @Procedure(mode = Mode.READ)
  @Description("Lists all existing namespace prefix definitions")
  public Stream<NamespacePrefixesResult> list() {

    return tx
        .execute("MATCH (n:_NsPrefDef) \n" +
            "UNWIND keys(n) AS prefix\n" +
            "RETURN prefix, n[prefix] AS namespace").stream().map(
            n -> new NamespacePrefixesResult((String) n.get("prefix"),
                (String) n.get("namespace")));

  }

  private boolean graphIsEmpty() {
    return !tx.execute("match (r:Resource) return id(r) limit 1").hasNext();
  }

  private class NsPrefixOperationNotAllowed extends Exception {

    public NsPrefixOperationNotAllowed(String message) {
      super(message);
    }
  }

}
