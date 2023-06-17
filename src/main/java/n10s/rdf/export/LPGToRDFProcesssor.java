package n10s.rdf.export;

import n10s.graphconfig.GraphConfig;
import n10s.utils.InvalidNamespacePrefixDefinitionInDB;
import n10s.utils.UriUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.neo4j.graphdb.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static n10s.graphconfig.Params.*;
import static n10s.utils.UriUtils.translateUri;


public class LPGToRDFProcesssor extends ExportProcessor {

  private final Map<String, String> exportMappings;
  private final boolean exportOnlyMappedElems;

  public LPGToRDFProcesssor(GraphDatabaseService gds, Transaction tx,
      GraphConfig gc, Map<String, String> exportMappings,
      boolean mappedElemsOnly, boolean isRDFStarSerialisation) {
    super(tx,gds, gc);
    this.exportMappings = exportMappings;
    this.exportPropertiesInRels = isRDFStarSerialisation;
    this.exportOnlyMappedElems = mappedElemsOnly;


  }

  public Stream<Statement> streamLocalImplicitOntology() {
    Result res = tx.execute("CALL db.schema.visualization() ");
    Set<Statement> statements = new HashSet<>();
    Map<String, Object> next = res.next();
    List<Node> nodeList = (List<Node>) next.get("nodes");
    nodeList.forEach(node -> {
      String catName = node.getAllProperties().get("name").toString();
      // Resource and NamespacePrefix should be named _Resource... to avoid conflicts
      if (!catName.equals("Resource") && !catName.equals("_NsPrefDef")
          && !catName.equals("_n10sValidatorConfig") && !catName.equals("_MapNs")
          && !catName.equals("_MapDef") && !catName.equals("_GraphConfig")) {
        IRI subject = vf.createIRI(BASE_SCH_NS, catName);
        statements.add(vf.createStatement(subject, RDF.TYPE, OWL.CLASS));
        statements.add(vf
            .createStatement(subject, RDFS.LABEL, vf.createLiteral(catName)));
      }
    });

    List<Relationship> relationshipList = (List<Relationship>) next.get("relationships");
    for (Relationship r : relationshipList) {
      IRI relUri = vf.createIRI(BASE_SCH_NS, r.getType().name());
      statements.add(vf.createStatement(relUri, RDF.TYPE, OWL.OBJECTPROPERTY));
      statements.add(vf.createStatement(relUri, RDFS.LABEL,
          vf.createLiteral(r.getType().name())));
      String domainLabel =
          r.getStartNode().getLabels().iterator().next().name();
      // Resource should be named _Resource... to avoid conflicts
      if (!domainLabel.equals("Resource")) {
        statements.add(vf.createStatement(relUri, RDFS.DOMAIN,
            vf
                .createIRI(BASE_SCH_NS, domainLabel)));
      }
      String rangeLabel = r.getEndNode().getLabels().iterator().next().name();
      // Resource should be named _Resource... to avoid conflicts
      if (!rangeLabel.equals("Resource")) {
        statements.add(vf.createStatement(relUri, RDFS.RANGE,
            vf.createIRI(BASE_SCH_NS, rangeLabel)));
      }
    }

    res = tx.execute("call db.schema.nodeTypeProperties() yield nodeLabels, propertyName, propertyTypes \n" +
            " where propertyName is not null " +
            " return [x in nodeLabels where x <> 'Resource'] as domain, propertyName as pName, " +
            " replace(propertyTypes[0],\"Array\",\"\") as range ");
    while(res.hasNext()) {
      next = res.next();

      if(!next.get("pName").toString().equals("uri")) {
        IRI propUri = vf.createIRI(BASE_SCH_NS, next.get("pName").toString());
        statements.add(vf.createStatement(propUri, RDF.TYPE, OWL.DATATYPEPROPERTY));
        statements.add(vf.createStatement(propUri, RDFS.LABEL,
                vf.createLiteral(next.get("pName").toString())));
        List<String> domains = (List<String>) next.get("domain");
        for (String dom : domains) {
          statements.add(vf.createStatement(propUri, RDFS.DOMAIN,
                  vf.createIRI(BASE_SCH_NS, dom)));
        }
        statements.add(vf.createStatement(propUri, RDFS.RANGE,
                getXSDType(next.get("range").toString())));
      }
    }

    return statements.stream();
  }

  public Stream<Statement> streamLocalExplicitOntology(Map<String, Object> params) {

    return this.tx.execute(buildOntoQuery(params)).stream().map(
        triple ->
            vf.createStatement(vf.createIRI((String) triple.get("subject")),
                vf.createIRI((String) triple.get("predicate")),
                vf.createIRI((String) triple.get("object")))
    );
  }

  private String buildOntoQuery(Map<String, Object> params) {
    // TODO: this query has to be modified to use customized terms
    return " MATCH (rel)-[:DOMAIN]->(domain) RETURN '" + BASE_SCH_NS
        + "' + rel.name AS subject, '"
        + RDFS.DOMAIN + "' AS predicate, '" + BASE_SCH_NS + "' + domain.name AS object "
        + " UNION "
        + " MATCH (rel)-[:RANGE]->(range) RETURN '" + BASE_SCH_NS + "' + rel.name AS subject, '"
        + RDFS.RANGE + "' AS predicate, '" + BASE_SCH_NS + "' + range.name AS object "
        + " UNION "
        + " MATCH (child)-[:SCO]->(parent) RETURN '" + BASE_SCH_NS
        + "' + child.name AS subject, '"
        + RDFS.SUBCLASSOF + "' AS predicate, '" + BASE_SCH_NS + "' + parent.name AS object "
        + "  UNION "
        + " MATCH (child)-[:SPO]->(parent) RETURN '" + BASE_SCH_NS
        + "' + child.name AS subject, '"
        + RDFS.SUBPROPERTYOF + "' AS predicate, '" + BASE_SCH_NS + "' + parent.name AS object ";
  }

  public Stream<Statement> streamNodeById(Long nodeId, boolean streamContext) {
    Map<Long, IRI> ontologyEntitiesUris = new HashMap<>();
    Node node = this.tx.getNodeById(nodeId);
    Set<Statement> result = processNode(node, ontologyEntitiesUris, null);
    if (streamContext) {
      Iterable<Relationship> relationships = node.getRelationships();
      for (Relationship rel : relationships) {
        result.addAll(processRelationship(rel, ontologyEntitiesUris));
      }
    }
    return result.stream();
  }

  public Stream<Statement> streamNodeByUri(String nodeUri, boolean streamContext) {
    Map<Long, IRI> ontologyEntitiesUris = new HashMap<>();
    Node node = this.tx.findNode(Label.label("Resource"),"uri",nodeUri);
    Set<Statement> result = processNode(node, ontologyEntitiesUris, null);
    if (streamContext) {
      Iterable<Relationship> relationships = node.getRelationships();
      for (Relationship rel : relationships) {
        result.addAll(processRelationship(rel, ontologyEntitiesUris));
      }
    }
    return result.stream();
  }

  @Override
  protected boolean filterRelationship(Relationship rel, Map<Long, IRI> ontologyEntitiesUris) {
    return filterNode(rel.getStartNode(), ontologyEntitiesUris) || filterNode(rel.getEndNode(), ontologyEntitiesUris);
  }

  @Override
  protected boolean filterNode(Node node, Map<Long, IRI> ontologyEntitiesUris) {
    return node.hasLabel(Label.label("_MapDef")) || node.hasLabel(Label.label("_MapNs"))||
        node.hasLabel(Label.label("_NsPrefDef")) || node.hasLabel(Label.label("_n10sValidatorConfig"))
        || node.hasLabel(Label.label("_GraphConfig"));
  }

  @Override
  protected void processPropOnRel(Set<Statement> statementSet,
      Statement baseStatement, String key, Object propertyValueObject) {


      if (!exportOnlyMappedElems || exportMappings.containsKey(key)) {
        IRI predicate = (exportMappings.containsKey(key) ? vf.createIRI(exportMappings.get(key)) :
            vf.createIRI(BASE_SCH_NS, key));
        if (propertyValueObject instanceof Object[]) {
          for (Object o : (Object[]) propertyValueObject) {
            statementSet.add(vf.createStatement(vf.createTriple(
                baseStatement.getSubject(), baseStatement.getPredicate(), baseStatement.getObject()),
                predicate, createTypedLiteral(o)));
          }
        } else {
          statementSet.add(vf.createStatement(vf.createTriple(
              baseStatement.getSubject(), baseStatement.getPredicate(), baseStatement.getObject()),
              predicate, createTypedLiteral(propertyValueObject)));
        }
      }

  }

  @Override
  protected Set<Statement> processRelationship(Relationship rel, Map<Long, IRI> ontologyEntitiesUris) {

    Set<Statement> statements = new HashSet<>();

      if (!exportOnlyMappedElems || exportMappings.containsKey(rel.getType().name())) {
        Statement base = vf.createStatement(
            getResourceUri(rel.getStartNode()),

            exportMappings.containsKey(rel.getType().name()) ? vf
                .createIRI(exportMappings.get(rel.getType().name()))
                :
                    vf.createIRI(BASE_SCH_NS, rel.getType().name()),
                getResourceUri(rel.getEndNode()));
        statements.add(base);

        if(this.exportPropertiesInRels) {
          rel.getAllProperties()
                  .forEach((k, v) -> processPropOnRel(statements, base, k, v));
        }

      }

    return statements;

  }

  private IRI getUriforRelName(String name) {
    switch (name) {
      case "SCO":
        return RDFS.SUBCLASSOF;
      case "SPO":
        return RDFS.SUBPROPERTYOF;
      case "DOMAIN":
        return RDFS.DOMAIN;
      case "RANGE":
        return RDFS.RANGE;
      default:
        //This should not happen :)
        return RDFS.SUBCLASSOF;
    }
  }


  @Override
  protected  Set<Statement> processNode(Node node, Map<Long, IRI> ontologyEntitiesUris, String propNameFilter) {
    Set<Statement> statements = new HashSet<>();
    List<Label> nodeLabels = new ArrayList<>();
    node.getLabels().forEach(l -> { if(!l.name().equals("Resource")) nodeLabels.add(l); });
    //TODO: Note that we can be looking up by id (implicit uri) and returning an explicit uri --> confusing/inconsistent
    IRI subject = getResourceUri(node);

    if (propNameFilter == null || propNameFilter.equals(RDF.TYPE.stringValue())){
      for (Label label : nodeLabels) {
        if (!exportOnlyMappedElems || exportMappings.containsKey(label.name())) {
          statements.add(vf.createStatement(subject,
                  RDF.TYPE, exportMappings.containsKey(label.name()) ? vf
                          .createIRI(exportMappings.get(label.name()))
                          : vf.createIRI(BASE_SCH_NS, label.name())));
        }
      }
    }

    Map<String, Object> allProperties = node.getAllProperties();
    // Do not serialise uri as a property.
    // When present, it will be the triple subject.
    allProperties.remove("uri");

    for (String key : allProperties.keySet()) {
      if(propNameFilter == null || propNameFilter.equals(vf.createIRI(BASE_SCH_NS, key).stringValue())){
        if (!exportOnlyMappedElems || exportMappings.containsKey(key)) {
          IRI predicate = (exportMappings.containsKey(key) ? vf.createIRI(exportMappings.get(key)) :
                  vf.createIRI(BASE_SCH_NS, key));
          Object propertyValueObject = allProperties.get(key);
          addStatementsForPotentiallyMultivalLiteral(statements, subject, predicate, propertyValueObject);
        }
      }

    }
    return statements;
  }

  private void addStatementsForPotentiallyMultivalLiteral(Set<Statement> statements, IRI subject, IRI predicate, Object propertyValueObject) {
    if (propertyValueObject instanceof long[]) {
      for (int i = 0; i < ((long[]) propertyValueObject).length; i++) {
        Literal object = createTypedLiteral(((long[]) propertyValueObject)[i]);
        statements.add(
                vf.createStatement(subject, predicate, object));
      }
    } else if (propertyValueObject instanceof double[]) {
      for (int i = 0; i < ((double[]) propertyValueObject).length; i++) {
        Literal object = createTypedLiteral(((double[]) propertyValueObject)[i]);
        statements.add(
                vf.createStatement(subject, predicate, object));
      }
    } else if (propertyValueObject instanceof boolean[]) {
      for (int i = 0; i < ((boolean[]) propertyValueObject).length; i++) {
        Literal object = createTypedLiteral(((boolean[]) propertyValueObject)[i]);
        statements.add(
                vf.createStatement(subject, predicate, object));
      }
    } else if (propertyValueObject instanceof LocalDateTime[]) {
      for (int i = 0; i < ((LocalDateTime[]) propertyValueObject).length; i++) {
        Literal object = createTypedLiteral(((LocalDateTime[]) propertyValueObject)[i]);
        statements.add(
                vf.createStatement(subject, predicate, object));
      }
    } else if (propertyValueObject instanceof Object[]) {
        for (Object o : (Object[]) propertyValueObject) {
          statements.add(vf.createStatement(subject, predicate,
                  createTypedLiteral(o)));
        }

    } else {
      statements.add(vf.createStatement(subject, predicate,
              createTypedLiteral(propertyValueObject)));
    }
  }


  @Override
  public Stream<Statement> streamTriplesFromTriplePattern(TriplePattern tp)
          throws InvalidNamespacePrefixDefinitionInDB {
    //Do we take mappings into account when filtering by prop/label/etc? NO
    // When we query via cypher the mappings are applied to the results but not used in the query
    if (tp.getSubject() != null) {
      Set<Statement> allStatements = new HashSet<>();
      Node resource = getNodeByUri(tp.getSubject());
      if (resource != null) {
        String predicate = tp.getPredicate();
        if (tp.getObject() == null) {
          //labels and properties applying predicate filter
          allStatements.addAll(processNode(resource, null, predicate));
          //relationships
          Iterable<Relationship> relationships =
                  predicate == null ? resource.getRelationships(Direction.OUTGOING) : resource.getRelationships(
                          Direction.OUTGOING, RelationshipType.withName(vf.createIRI(predicate).getLocalName()));
          for (Relationship r : relationships) {
            allStatements.addAll(processRelationship(r, null));
          }
        } else {
          //filter on value (object)
          Value object = getValueFromTriplePatternObject(tp);
          allStatements.addAll(processNode(resource, null, predicate).stream()
                  .filter(st -> st.getObject().equals(object)).collect(Collectors.toSet()));

          //if filter on object  is of type literal then we  can skip the rels, it will be a prop
          if (!tp.getLiteral()) {
            Iterable<Relationship> relationships =
                    predicate == null ? resource.getRelationships(Direction.OUTGOING)
                            : resource.getRelationships(
                            Direction.OUTGOING, RelationshipType.withName(vf.createIRI(predicate).getLocalName()));
            //watch out, if filter on predicate is rdf:type, it will match things like ()-[:type]->({uri:$obj})
            //what are the chances?? TODO: create unit test
            for (Relationship r : relationships) {
              if (getResourceUri(r.getEndNode()).stringValue().equals(object.stringValue())) {
                allStatements.addAll(processRelationship(r, null));
              }
            }
          }
        }
      }
      return allStatements.stream();
    } else {
      //subject is null
      Set<Statement> allStatements = new HashSet<>();
      String predicate = null;
      try {
        //what if predicate is not a URI TODO: test
        predicate = tp.getPredicate() != null ? translateUri(tp.getPredicate(), tx, graphConfig) : null;
      } catch (UriUtils.UriNamespaceHasNoAssociatedPrefix e) {
        //graph is in shorten mode but the uri in the filter is not in use in the graph
        predicate = tp.getPredicate();
        // TODO: use the NOT_MATCHING_NS
        // ugly way of making the filter not return anything.
      }
      if (tp.getObject() == null) {
        //null,x,null
        Result result;
        if (predicate != null) {
          //null, pred, null
          if (tp.getPredicate().equals(RDF.TYPE.stringValue())) {
            result = tx.execute("MATCH (r) RETURN r");
            while (result.hasNext()) {
              Map<String, Object> next = result.next();
              Node node = (Node) next.get("r");
              for (Label label : node.getLabels()) {
                  //TODO: we also need to take into account the TYPES AS NODES here
                  // should we in the case of IGNORE (graphconfig!=null) query for nodes of type Resource???
                  if(graphConfig!=null){
                    if (!label.name().equals("Resource")&&!label.name().equals("_GraphConfig")) {
                      allStatements.add(vf.createStatement(getResourceUri(node),
                              RDF.TYPE, vf.createIRI(BASE_SCH_NS, label.name())));
                    }
                  } else {
                    if (!exportOnlyMappedElems || exportMappings.containsKey(label.name())) {
                      allStatements.add(vf.createStatement(getResourceUri(node),
                              RDF.TYPE, exportMappings.containsKey(label.name()) ? vf
                                      .createIRI(exportMappings.get(label.name()))
                                      : vf.createIRI(BASE_SCH_NS, label.name())));
                    }
                  }
              }
            }
            return allStatements.stream();
          } else {
            //CHECK IF predicate is <NONE>, in which case there's no point in running the query
            if (!predicate.equals(NOT_MATCHING_NS)) {
              result = tx.execute(String
                      .format("MATCH (s) WHERE s.`%s` is not null RETURN s, s.`%s` as o\n"
                                      + "UNION \n"
                                      + "MATCH (s)-[:`%s`]->(o) RETURN s, o",
                              predicate, predicate, predicate));

              while (result.hasNext()) {
                Map<String, Object> next = result.next();
                Node subjectNode = (Node) next.get("s");
                Object objectThing = next.get("o");
                if (!exportOnlyMappedElems || exportMappings.containsKey(predicate)) {
                  if (objectThing instanceof Node) {
                    allStatements.add(vf.createStatement(getResourceUri(subjectNode),
                            exportMappings.containsKey(predicate) ? vf
                                    .createIRI(exportMappings.get(predicate))
                                    : vf.createIRI(BASE_SCH_NS, predicate),
                            getResourceUri((Node) objectThing)));
                  } else {
                    addStatementsForPotentiallyMultivalLiteral(allStatements, getResourceUri(subjectNode), exportMappings.containsKey(predicate) ? vf
                            .createIRI(exportMappings.get(predicate))
                            : vf.createIRI(BASE_SCH_NS, predicate), objectThing);
                  }
                }
              }
            }
            return allStatements.stream();
          }
        } else {
          //no subject, no pred, no object: null, null, null -> return all triples
          //TODO: Exclude other control elements like mappings. Will be solved when all that stuff is moved to the
          // admin DB.
          result = tx.execute("MATCH (r) WHERE NOT r:_GraphConfig RETURN r\n"
                  + "UNION \n"
                  + "MATCH ()-[r]->() RETURN r");
          return result.stream().flatMap(row -> {
            Set<Statement> rowResult = new HashSet<>();
            Object r = row.get("r");
            if (r instanceof Node) {
              rowResult.addAll(processNode((Node) r, null, null));
            } else if (r instanceof Relationship) {
              rowResult.addAll(processRelationship((Relationship) r, null));
            }
            return rowResult.stream();
          });
        }
      } else {
        //filter on value (object)
        Value object = getValueFromTriplePatternObject(tp);
        Result result;
        Map<String, Object> params = new HashMap<>();
        if (predicate != null) {
          // null, pred, obj
          if (tp.getPredicate().equals(RDF.TYPE.stringValue())) {
            //TODO: Deal with types as nodes
            String objectAsLabel = null;
            if (object instanceof IRI) {
              objectAsLabel = (((IRI) object).getNamespace().equals(DEFAULT_BASE_SCH_NS)?((IRI) object).getLocalName():"____");
            } else {
              objectAsLabel = "____";
            }
            result = tx.execute(String.format("MATCH (r:`%s`) RETURN r", objectAsLabel));
            while(result.hasNext()){
              if (!exportOnlyMappedElems || exportMappings.containsKey(objectAsLabel)) {
                allStatements.add(vf.createStatement(getResourceUri((Node)result.next().get("r")),
                        RDF.TYPE, object));
              }
            }
            return allStatements.stream();
          } else {
            if (object instanceof IRI) {
              params.put("uri", object.stringValue());
              //query for relationships
              //TODO: This does not work on a pure LPG (TEST)
              result = tx.execute(String
                      .format("MATCH (:Resource)-[r:`%s`]->(o:Resource { uri:  $uri }) RETURN r",
                              predicate), params);

              while(result.hasNext()){
                if (!exportOnlyMappedElems || exportMappings.containsKey(predicate)) {
                  allStatements.add(vf.createStatement(getResourceUri((Node)result.next().get("r")),
                          exportMappings.containsKey(predicate) ? vf
                                  .createIRI(exportMappings.get(predicate))
                                  : vf.createIRI(BASE_SCH_NS, predicate), object));
                }
              }
              return allStatements.stream();

            } else {
              //it's a Literal
              params.put("propVal",
                      castValueFromXSDType((Literal) object));//translateLiteral((Literal)object, graphConfig));
              //TODO: what if hte value is an array?
              result = tx.execute(String
                        .format("MATCH (r) WHERE $propVal in r.`%s` + [] RETURN r",
                                predicate), params);
              while(result.hasNext()){
                if (!exportOnlyMappedElems || exportMappings.containsKey(predicate)) {
                  allStatements.add(vf.createStatement(getResourceUri((Node)result.next().get("r")),
                          exportMappings.containsKey(predicate) ? vf
                                  .createIRI(exportMappings.get(predicate))
                                  : vf.createIRI(BASE_SCH_NS, predicate), object));
                }
              }
              return allStatements.stream();
            }
          }


        } else {
          //null, null, obj
          if (object instanceof IRI) {
            //query for relationships
            Node objectNode = getNodeByUri(object.stringValue());
            params.put("objectNodeInternalId", objectNode.getId());
            result = tx.execute("MATCH ()-[r]->(o) WHERE id(o) = $objectNodeInternalId RETURN r", params);
            return result.stream().flatMap(row -> {
              Set<Statement> rowResult = new HashSet<>();
              Object r = row.get("r");
              rowResult.addAll(processRelationship((Relationship) r, null));
              return rowResult.stream();
            });
          } else {
            //it's a Literal
            params.put("propVal",
                    castValueFromXSDType((Literal) object));
            //this is expensive...
            result = tx.execute("MATCH (r) UNWIND keys(r) as propName \n"
                    + "WITH r, propName\n"
                    + "WHERE $propVal in [] + r[propName] \n"
                    + "RETURN r, propName", params);

            while (result.hasNext()) {
              Map<String, Object> next = result.next();
              if (!exportOnlyMappedElems || exportMappings.containsKey(predicate)) {
                allStatements.add(vf.createStatement(getResourceUri((Node) next.get("r")),
                        exportMappings.containsKey((String)next.get("propName")) ? vf
                                .createIRI(exportMappings.get((String)next.get("propName")))
                                : vf.createIRI(BASE_SCH_NS, (String)next.get("propName")),
                        object));
              }
            }
            return allStatements.stream();
          }

        }
//        //refactor with previous section
//        String finalPredicate1 = predicate;
//        return result.stream().flatMap(row -> {
//          Set<Statement> rowResult = new HashSet<>();
//          Object r = row.get("r");
//          if(r instanceof Node){
//            rowResult.addAll(processNode((Node)r, null,
//                    (finalPredicate1!=null?finalPredicate1:(String)row.get("propName"))));
//          } else if(r instanceof Relationship){
//            rowResult.add(processRelationship((Relationship)r,null));
//          }
//          return rowResult.stream();
//        }).filter(
//                st ->
//                        st.getObject().equals(object));
//        //post filtering on the generated statements.
//      }
      }
    }
  }


  private Node getNodeByUri(String uri) {
    // what if it is a mixed graph where one part is the result of importing RDF using the 'IGNORE' setup
    // in this case there may be Resources with uris. Should we expect consistency?
    // this would mean the rest of the graph follows the same approach and nodes are
    // created using the n10s.add methods
    if(this.graphConfig != null){
      return tx.findNode(Label.label("Resource"), "uri", uri);
    } else{
      try{
        return tx.getNodeById(getNodeIdFromUri(uri));
      } catch (NumberFormatException e){
        //local part of uri is not a long
        return null;
      }
    }
  }

  private long getNodeIdFromUri(String subject) {
    //this throws numberFormatException
    return Long.parseLong(subject.substring(BASE_INDIV_NS.length()));
  }

  private IRI getResourceUri(Node node) {

    String explicituri = (String)node.getProperty("uri", null);
    return (explicituri==null?vf.createIRI(BASE_INDIV_NS, String.valueOf(node.getId())):
            vf.createIRI(explicituri));
  }

}
