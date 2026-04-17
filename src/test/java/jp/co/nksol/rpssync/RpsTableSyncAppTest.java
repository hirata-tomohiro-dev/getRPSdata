package jp.co.nksol.rpssync;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class RpsTableSyncAppTest {
    private RpsTableSyncAppTest() {
    }

    public static void main(String[] args) throws Exception {
        testParseQuery();
        testParseTables();
        testParseDetail();
        testSanitizeFileComponent();
        System.out.println("RpsTableSyncAppTest: OK");
    }

    private static void testParseQuery() throws Exception {
        String url = "http://example.local/column/show_table?description=%E5%8F%96%E6%AC%A1%E6%98%8E%E7%B4%B0&amp;instance=KYKDB&schema=KYKOWNER&table_name=AGENT_DETAIL&version=1.2";
        assertEquals("AGENT_DETAIL", RpsTableSyncApp.parseQuery(url).get("table_name"), "table_name");
        assertEquals("KYKOWNER", RpsTableSyncApp.parseQuery(url).get("schema"), "schema");
    }

    private static void testParseTables() throws Exception {
        String html = loadFixture("table-list.html");
        List<RpsTableSyncApp.TableInfo> tables = RpsTableSyncApp.parseTables(html, "http://10.202.168.238:13010", "KYKOWNER");
        assertEquals(2, tables.size(), "table count");
        assertEquals("AGENT_DETAIL", tables.get(0).tableName(), "first table");
        assertEquals("CUSTOMER_MST", tables.get(1).tableName(), "second table");
    }

    private static void testParseDetail() {
        String html = loadFixture("detail.html");
        RpsTableSyncApp.DetailPayload payload = RpsTableSyncApp.parseDetail(html);
        assertContains(payload.header(), "AGENT_DETAIL", "detail header");
        assertContains(payload.tableHtml(), "<table class=\"std_hover\">", "detail table");
    }

    private static void testSanitizeFileComponent() {
        assertEquals("AGENT_DETAIL_1.2", RpsTableSyncApp.sanitizeFileComponent("AGENT_DETAIL/1.2"), "sanitize");
    }

    private static String loadFixture(String resourceName) {
        try (var stream = RpsTableSyncAppTest.class.getResourceAsStream("/fixtures/" + resourceName)) {
            if (stream == null) {
                throw new IllegalStateException("Fixture not found: " + resourceName);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " expected=[" + expected + "] actual=[" + actual + "]");
        }
    }

    private static void assertContains(String actual, String expectedFragment, String label) {
        if (actual == null || !actual.contains(expectedFragment)) {
            throw new AssertionError(label + " expected fragment=[" + expectedFragment + "] actual=[" + actual + "]");
        }
    }
}
