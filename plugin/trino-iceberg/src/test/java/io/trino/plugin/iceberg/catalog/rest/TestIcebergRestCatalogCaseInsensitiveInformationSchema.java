/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.iceberg.catalog.rest;

import io.airlift.http.server.testing.TestingHttpServer;
import io.trino.plugin.iceberg.IcebergQueryRunner;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.QueryRunner;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.jdbc.JdbcCatalog;
import org.apache.iceberg.rest.DelegatingRestSessionCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

import static com.google.common.io.MoreFiles.deleteRecursively;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static io.trino.plugin.iceberg.IcebergSchemaProperties.LOCATION_PROPERTY;
import static io.trino.plugin.iceberg.catalog.rest.RestCatalogTestUtils.backendCatalog;
import static io.trino.testing.TestingNames.randomNameSuffix;
import static java.nio.file.Files.createDirectories;
import static java.util.Locale.ENGLISH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
final class TestIcebergRestCatalogCaseInsensitiveInformationSchema
        extends AbstractTestQueryFramework
{
    private static final String SCHEMA = "InformationSchemaTest_" + randomNameSuffix();
    private static final String LOWERCASE_SCHEMA = SCHEMA.toLowerCase(ENGLISH);
    private static final Namespace NAMESPACE = Namespace.of(SCHEMA);

    private JdbcCatalog backend;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        Path warehouseLocation = Files.createTempDirectory(null);
        closeAfterClass(() -> deleteRecursively(warehouseLocation, ALLOW_INSECURE));

        backend = closeAfterClass((JdbcCatalog) backendCatalog(warehouseLocation));

        DelegatingRestSessionCatalog delegatingCatalog = DelegatingRestSessionCatalog.builder()
                .delegate(backend)
                .build();

        TestingHttpServer testServer = delegatingCatalog.testServer();
        testServer.start();
        closeAfterClass(testServer::stop);

        return IcebergQueryRunner.builder(LOWERCASE_SCHEMA)
                .setBaseDataDir(Optional.of(warehouseLocation))
                .addIcebergProperty("iceberg.catalog.type", "rest")
                .addIcebergProperty("iceberg.rest-catalog.uri", testServer.getBaseUrl().toString())
                .addIcebergProperty("iceberg.rest-catalog.case-insensitive-name-matching", "true")
                .addIcebergProperty("iceberg.register-table-procedure.enabled", "true")
                .build();
    }

    @BeforeAll
    void setup()
    {
        backend.createNamespace(NAMESPACE);
        assertThat(computeActual("SHOW SCHEMAS").getOnlyColumnAsSet())
                .containsExactlyInAnyOrder(
                        "information_schema",
                        "tpch",
                        LOWERCASE_SCHEMA,
                        "system");

        // Verify our test schema is created and appears in the schema list
        assertThat(computeActual("SHOW SCHEMAS").getOnlyColumnAsSet())
                .contains(LOWERCASE_SCHEMA);

        assertQuery("SELECT * FROM information_schema.schemata",
                        """
                        VALUES
                        ('iceberg', 'information_schema'),
                        ('iceberg', 'system'),
                        ('iceberg', '%s'),
                        ('iceberg', 'tpch')
                        """.formatted(LOWERCASE_SCHEMA));
    }

    @Test
    void testInformationSchemaTablesWithCaseInsensitiveNames()
    {
        Map<String, String> namespaceMetadata = backend.loadNamespaceMetadata(NAMESPACE);
        String namespaceLocation = namespaceMetadata.get(LOCATION_PROPERTY);
        createDir(namespaceLocation);

        String suffix = randomNameSuffix();

        // Create tables with names that differ only in case - should all resolve to the same lowercase name
        String table1Name = "CaseInsensitiveTable_" + suffix;
        String table2Name = "caseinsensitivetable_" + suffix;
        String table3Name = "CASEINSENSITIVETABLE_" + suffix;
        String expectedTableName = table1Name.toLowerCase(ENGLISH);

        String tableLocation = namespaceLocation + "/" + expectedTableName;

        // Create the first table
        assertUpdate("CREATE TABLE \"" + table1Name + "\" WITH (location = '" + tableLocation + "') AS SELECT BIGINT '1' id, VARCHAR 'table1' name", 1);

        // Verify it appears in information_schema with lowercase name
        assertQuery("SELECT table_catalog, table_schema, table_name, table_type FROM information_schema.tables WHERE table_schema = '" + LOWERCASE_SCHEMA + "' AND table_name = '" + expectedTableName + "'",
                "VALUES ('iceberg', '" + LOWERCASE_SCHEMA + "', '" + expectedTableName + "', 'BASE TABLE')");

        // Verify we can query the table using any case variation
        assertQuery("SELECT * FROM \"" + table1Name + "\"", "VALUES (1, 'table1')");
        assertQuery("SELECT * FROM \"" + table2Name + "\"", "VALUES (1, 'table1')");
        assertQuery("SELECT * FROM \"" + table3Name + "\"", "VALUES (1, 'table1')");

        // Test information_schema.columns shows consistent lowercase names
        assertQuery("SELECT table_catalog, table_schema, table_name, column_name, data_type FROM information_schema.columns WHERE table_schema = '" + LOWERCASE_SCHEMA + "' AND table_name = '" + expectedTableName + "' ORDER BY ordinal_position",
                "VALUES " +
                "('iceberg', '" + LOWERCASE_SCHEMA + "', '" + expectedTableName + "', 'id', 'bigint'), " +
                "('iceberg', '" + LOWERCASE_SCHEMA + "', '" + expectedTableName + "', 'name', 'varchar')");

        // Attempting to create another table with different case should fail (same table)
        assertQueryFails("CREATE TABLE \"" + table2Name + "\" AS SELECT BIGINT '2' id", ".*already exists.*");

        // Clean up
        assertUpdate("DROP TABLE \"" + table1Name + "\"");
    }

    @Test
    void testInformationSchemaViewsWithCaseInsensitiveNames()
    {
        Map<String, String> namespaceMetadata = backend.loadNamespaceMetadata(NAMESPACE);
        String namespaceLocation = namespaceMetadata.get(LOCATION_PROPERTY);
        createDir(namespaceLocation);

        String suffix = randomNameSuffix();

        // Create views with names that differ only in case
        String view1Name = "CaseInsensitiveView_" + suffix;
        String view2Name = "caseinsensitiveview_" + suffix;
        String expectedViewName = view1Name.toLowerCase(ENGLISH);

        // Create the first view
        assertUpdate("CREATE VIEW \"" + view1Name + "\" AS SELECT BIGINT '100' id, VARCHAR 'view1' type");

        // Verify it appears in information_schema with lowercase name
        assertQuery("SELECT table_catalog, table_schema, table_name, table_type FROM information_schema.tables WHERE table_schema = '" + LOWERCASE_SCHEMA + "' AND table_name = '" + expectedViewName + "'",
                "VALUES ('iceberg', '" + LOWERCASE_SCHEMA + "', '" + expectedViewName + "', 'VIEW')");

        // Test information_schema.views
        assertQuery("SELECT table_catalog, table_schema, table_name FROM information_schema.views WHERE table_schema = '" + LOWERCASE_SCHEMA + "' AND table_name = '" + expectedViewName + "'",
                "VALUES ('iceberg', '" + LOWERCASE_SCHEMA + "', '" + expectedViewName + "')");

        // Verify we can query the view using any case variation
        assertQuery("SELECT * FROM \"" + view1Name + "\"", "VALUES (100, 'view1')");
        assertQuery("SELECT * FROM \"" + view2Name + "\"", "VALUES (100, 'view1')");

        // Attempting to create another view with different case should fail (same view)
        assertQueryFails("CREATE VIEW \"" + view2Name + "\" AS SELECT BIGINT '200' id", ".*already exists.*");

        // Clean up
        assertUpdate("DROP VIEW \"" + view1Name + "\"");
    }

    @Test
    void testInformationSchemaColumnsWithCaseInsensitiveNames()
    {
        Map<String, String> namespaceMetadata = backend.loadNamespaceMetadata(NAMESPACE);
        String namespaceLocation = namespaceMetadata.get(LOCATION_PROPERTY);
        createDir(namespaceLocation);

        String tableName = "column_case_test_" + randomNameSuffix();
        String tableLocation = namespaceLocation + "/" + tableName;

        // Create table with mixed case column names - should all be normalized to lowercase
        assertUpdate("CREATE TABLE \"" + tableName + "\" (" +
                "\"MixedCaseId\" bigint, " +
                "\"UPPERCASE_NAME\" varchar, " +
                "\"lowercase_value\" double, " +
                "regular_column boolean) " +
                "WITH (location = '" + tableLocation + "')");

        // Test information_schema.columns shows all column names in lowercase
        assertQuery("SELECT column_name, data_type, is_nullable FROM information_schema.columns " +
                "WHERE table_schema = '" + LOWERCASE_SCHEMA + "' AND table_name = '" + tableName + "' " +
                "ORDER BY ordinal_position",
                "VALUES " +
                "('mixedcaseid', 'bigint', 'YES'), " +
                "('uppercase_name', 'varchar', 'YES'), " +
                "('lowercase_value', 'double', 'YES'), " +
                "('regular_column', 'boolean', 'YES')");

        // Verify we can query columns using any case variation
        assertQuery("SELECT \"MixedCaseId\", \"UPPERCASE_NAME\", \"lowercase_value\", regular_column FROM \"" + tableName + "\" LIMIT 0",
                "SELECT NULL, NULL, NULL, NULL WHERE FALSE");
        assertQuery("SELECT \"mixedcaseid\", \"uppercase_name\", \"LOWERCASE_VALUE\", REGULAR_COLUMN FROM \"" + tableName + "\" LIMIT 0",
                "SELECT NULL, NULL, NULL, NULL WHERE FALSE");

        // Clean up
        assertUpdate("DROP TABLE \"" + tableName + "\"");
    }

    @Test
    void testInformationSchemaSchemataWithCaseInsensitiveNames()
    {
        // Test that information_schema.schemata correctly reports schema names in lowercase

        // Check that our test schema appears correctly in information_schema.schemata
        assertQuery("SELECT catalog_name, schema_name FROM information_schema.schemata WHERE schema_name = '" + LOWERCASE_SCHEMA + "'",
                "VALUES ('iceberg', '" + LOWERCASE_SCHEMA + "')");

        // Verify schema name consistency between SHOW SCHEMAS and information_schema.schemata
        var showSchemasResult = computeActual("SHOW SCHEMAS").getOnlyColumnAsSet();
        var informationSchemaResult = computeActual("SELECT schema_name FROM information_schema.schemata WHERE catalog_name = 'iceberg'").getOnlyColumnAsSet();

        assertThat(showSchemasResult).isEqualTo(informationSchemaResult);

        // Test that all schema names are lowercase
        for (Object schemaName : showSchemasResult) {
            String schema = schemaName.toString();
            assertThat(schema).isEqualTo(schema.toLowerCase(ENGLISH));
        }

        // Test that we can access the schema using different case variations
        if (!LOWERCASE_SCHEMA.equals("information_schema") && !LOWERCASE_SCHEMA.equals("system")) {
            computeActual("SHOW TABLES IN \"" + LOWERCASE_SCHEMA + "\"");
            computeActual("SHOW TABLES IN \"" + LOWERCASE_SCHEMA.toUpperCase(ENGLISH) + "\"");
        }
    }

    @Test
    void testInformationSchemaTablePrivilegesWithCaseInsensitiveNames()
    {
        Map<String, String> namespaceMetadata = backend.loadNamespaceMetadata(NAMESPACE);
        String namespaceLocation = namespaceMetadata.get(LOCATION_PROPERTY);
        createDir(namespaceLocation);

        String originalTableName = "PrivilegeTest_" + randomNameSuffix();
        String expectedTableName = originalTableName.toLowerCase(ENGLISH);
        String tableLocation = namespaceLocation + "/" + expectedTableName;

        // Create a table to test privileges
        assertUpdate("CREATE TABLE \"" + originalTableName + "\" WITH (location = '" + tableLocation + "') AS SELECT BIGINT '1' id", 1);

        // Test information_schema.table_privileges shows lowercase table name
        var privilegesResult = computeActual("SELECT table_catalog, table_schema, table_name FROM information_schema.table_privileges WHERE table_schema = '" + LOWERCASE_SCHEMA + "' AND table_name = '" + expectedTableName + "'");

        if (privilegesResult.getRowCount() > 0) {
            // Verify that all privilege entries use lowercase for the table name
            for (var row : privilegesResult.getMaterializedRows()) {
                String reportedTableName = (String) row.getField(2);
                assertThat(reportedTableName).isEqualTo(expectedTableName);
                assertThat(reportedTableName).isEqualTo(reportedTableName.toLowerCase(ENGLISH));
            }
        }

        // Clean up
        assertUpdate("DROP TABLE \"" + originalTableName + "\"");
    }

    @Test
    void testInformationSchemaConsistencyAcrossQueries()
    {
        Map<String, String> namespaceMetadata = backend.loadNamespaceMetadata(NAMESPACE);
        String namespaceLocation = namespaceMetadata.get(LOCATION_PROPERTY);
        createDir(namespaceLocation);

        String suffix = randomNameSuffix();
        String originalTableName = "ConsistencyTest_" + suffix;
        String originalViewName = "ConsistencyView_" + suffix;
        String expectedTableName = originalTableName.toLowerCase(ENGLISH);
        String expectedViewName = originalViewName.toLowerCase(ENGLISH);

        String tableLocation = namespaceLocation + "/" + expectedTableName;

        // Create table and view with mixed case names
        assertUpdate("CREATE TABLE \"" + originalTableName + "\" WITH (location = '" + tableLocation + "') AS SELECT BIGINT '1' id, VARCHAR 'test' name", 1);
        assertUpdate("CREATE VIEW \"" + originalViewName + "\" AS SELECT * FROM \"" + originalTableName + "\"");

        // Test consistency between different information_schema queries

        // 1. SHOW TABLES vs information_schema.tables
        var showTablesResult = computeActual("SHOW TABLES IN \"" + LOWERCASE_SCHEMA + "\"").getOnlyColumnAsSet();
        var infoSchemaTablesResult = computeActual("SELECT table_name FROM information_schema.tables WHERE table_schema = '" + LOWERCASE_SCHEMA + "' AND table_type IN ('BASE TABLE', 'VIEW')").getOnlyColumnAsSet();

        assertThat(showTablesResult).isEqualTo(infoSchemaTablesResult);
        assertThat(showTablesResult).contains(expectedTableName, expectedViewName);

        // 2. information_schema.tables vs information_schema.columns consistency
        var tablesWithColumns = computeActual("SELECT DISTINCT table_name FROM information_schema.columns WHERE table_schema = '" + LOWERCASE_SCHEMA + "'").getOnlyColumnAsSet();
        var allTables = computeActual("SELECT table_name FROM information_schema.tables WHERE table_schema = '" + LOWERCASE_SCHEMA + "' AND table_type = 'BASE TABLE'").getOnlyColumnAsSet();

        // Every table should have columns
        for (Object tableName : allTables) {
            assertThat(tablesWithColumns).contains(tableName);
        }

        // 3. information_schema.tables vs information_schema.views consistency for our specific test objects
        var viewsFromTables = computeActual("SELECT table_name FROM information_schema.tables WHERE table_schema = '" + LOWERCASE_SCHEMA + "' AND table_type = 'VIEW' AND table_name = '" + expectedViewName + "'").getOnlyColumnAsSet();
        var viewsFromViews = computeActual("SELECT table_name FROM information_schema.views WHERE table_schema = '" + LOWERCASE_SCHEMA + "' AND table_name = '" + expectedViewName + "'").getOnlyColumnAsSet();

        assertThat(viewsFromTables).isEqualTo(viewsFromViews);

        // 4. Verify all names are consistently lowercase
        for (Object name : showTablesResult) {
            String nameStr = name.toString();
            assertThat(nameStr).isEqualTo(nameStr.toLowerCase(ENGLISH));
        }

        // Clean up
        assertUpdate("DROP VIEW \"" + originalViewName + "\"");
        assertUpdate("DROP TABLE \"" + originalTableName + "\"");
    }

    private static void createDir(String absoluteDirPath)
    {
        Path path = Paths.get(URI.create(absoluteDirPath).getPath());
        try {
            createDirectories(path);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot create %s directory".formatted(absoluteDirPath), e);
        }
    }
}
