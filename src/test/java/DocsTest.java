import static org.junit.Assert.assertFalse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.exceptions.KernelException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;
import org.neo4j.kernel.api.procedure.GlobalProcedures;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ConfigurationBuilder;

public class DocsTest {

  private static Neo4j db;

  @Before
  public void setUp() throws Exception {

    db = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();

    Set<Class<?>> allClasses = allClasses();
    assertFalse(allClasses.isEmpty());

    for (Class<?> klass : allClasses) {
      if (!klass.getName().endsWith("Test")) {
        registerProcedure(db.defaultDatabaseService(), klass);
      }
    }

    new File("build/generated-documentation").mkdirs();
  }

  public static void registerProcedure(GraphDatabaseService db, Class<?>... procedures) {
    GlobalProcedures globalProcedures = ((GraphDatabaseAPI) db).getDependencyResolver()
        .resolveDependency(GlobalProcedures.class);
    for (Class<?> procedure : procedures) {
      try {
        globalProcedures.registerProcedure(procedure);
        globalProcedures.registerFunction(procedure);
        globalProcedures.registerAggregationFunction(procedure);
      } catch (KernelException e) {
        throw new RuntimeException("while registering " + procedure, e);
      }
    }
  }

  static class Row {

    private String type;
    private String name;
    private String signature;
    private String description;

    public Row(String type, String name, String signature, String description) {
      this.type = type;
      this.name = name;
      this.signature = signature;
      this.description = description;
    }
  }

  @Test
  public void generateDocs() {
    // given
    List<Row> rows = new ArrayList<>();

    List<Row> procedureRows = db.defaultDatabaseService().executeTransactionally(
        "show procedures YIELD signature, name, description WHERE name STARTS WITH 'n10s' RETURN 'procedure' AS type, name, description, signature ORDER BY signature",
        Collections.emptyMap(),
        result -> result.stream().map(record -> new Row(
            record.get("type").toString(),
            record.get("name").toString(),
            record.get("signature").toString(),
            record.get("description").toString())
        ).collect(Collectors.toList()));
    rows.addAll(procedureRows);

    List<Row> functionRows = db.defaultDatabaseService().executeTransactionally(
        "show functions YIELD signature, name, description WHERE name STARTS WITH 'n10s' RETURN 'function' AS type, name, description, signature ORDER BY signature",
        Collections.emptyMap(),
        result -> result.stream().map(record -> new Row(
            record.get("type").toString(),
            record.get("name").toString(),
            record.get("signature").toString(),
            record.get("description").toString())
        ).collect(Collectors.toList()));

    rows.addAll(functionRows);

    try (Writer writer = new OutputStreamWriter(
        new FileOutputStream(new File("docs/modules/ROOT/examples/documentation.csv")),
        StandardCharsets.UTF_8)) {
      writer.write("¦type¦qualified name¦signature¦description\n");
      for (Row row : rows) {
        writer.write(
            String.format("¦%s¦%s¦%s¦%s\n", row.type, row.name, row.signature, row.description));
      }

    } catch (Exception e) {
      throw new RuntimeException(e.getMessage(), e);
    }

    Map<String, List<Row>> collect = rows.stream().collect(Collectors.groupingBy(value -> {
      String[] parts = value.name.split("\\.");
      parts = Arrays.copyOf(parts, parts.length - 1);
      return String.join(".", parts);
    }));

    for (Map.Entry<String, List<Row>> record : collect.entrySet()) {
      try (Writer writer = new OutputStreamWriter(new FileOutputStream(
          new File(String.format("docs/modules/ROOT/examples/%s.csv", record.getKey()))),
          StandardCharsets.UTF_8)) {
        writer.write("¦type¦qualified name¦signature¦description\n");
        for (Row row : record.getValue()) {
          writer.write(
              String.format("¦%s¦%s¦%s¦%s\n", row.type, row.name, row.signature, row.description));
        }

      } catch (Exception e) {
        throw new RuntimeException(e.getMessage(), e);
      }
    }

    for (Row row : rows) {
      try (Writer writer = new OutputStreamWriter(new FileOutputStream(
          new File(String.format("docs/modules/ROOT/examples/%s.csv", row.name))),
          StandardCharsets.UTF_8)) {
        writer.write("¦type¦qualified name¦signature¦description\n");

        writer.write(
            String.format("¦%s¦%s¦%s¦%s\n", row.type, row.name, row.signature, row.description));
      } catch (Exception e) {
        throw new RuntimeException(e.getMessage(), e);
      }
    }

    for (Map.Entry<String, List<Row>> record : collect.entrySet()) {
      try (Writer writer = new OutputStreamWriter(new FileOutputStream(
          new File(String.format("docs/modules/ROOT/examples/%s-lite.csv", record.getKey()))),
          StandardCharsets.UTF_8)) {
        writer.write("¦signature\n");
        for (Row row : record.getValue()) {
          writer.write(String.format("¦%s\n", row.signature));
        }

      } catch (Exception e) {
        throw new RuntimeException(e.getMessage(), e);
      }
    }

    for (Row row : rows) {
      try (Writer writer = new OutputStreamWriter(new FileOutputStream(
          new File(String.format("docs/modules/ROOT/examples/%s-lite.csv", row.name))),
          StandardCharsets.UTF_8)) {
        writer.write("¦signature\n");

        writer.write(String.format("¦%s\n", row.signature));
      } catch (Exception e) {
        throw new RuntimeException(e.getMessage(), e);
      }
    }

  }

  private Set<Class<?>> allClasses() {
    Reflections reflections = new Reflections(new ConfigurationBuilder()
        .forPackages("n10s")
        .setScanners(new SubTypesScanner(false))
        .filterInputsBy(
            input -> !input.endsWith("Test.class") && !input.endsWith("Result.class") && !input
                .contains("$"))
    );

    return reflections.getSubTypesOf(Object.class);
  }

}