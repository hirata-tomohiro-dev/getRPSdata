package jp.co.nksol.rpssync;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RpsTableSyncApp {
    private static final CookieManager COOKIE_MANAGER = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private static final Pattern AUTH_TOKEN_PATTERN = Pattern.compile(
            "name\\s*=\\s*\"authenticity_token\"\\s+value\\s*=\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DETAIL_LINK_PATTERN = Pattern.compile(
            "<a\\s+href=\"([^\"]*/column/show_table\\?[^\"]+)\"[^>]*>([^<]+)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HEADER_PATTERN = Pattern.compile("<h2>(.*?)</h2>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TABLE_PATTERN = Pattern.compile("(<table\\s+class=\"std_hover\".*?</table>)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final String DEFAULT_TABLE_LIST_PATH =
            "/table/list?utf8=%E2%9C%93&pm=ent&keyword_db=&keyword_object_name=&keyword_description=&keyword_group_name=&search_condition%5Btype%5D=REFLECT&submit=%E5%85%A8%E4%BB%B6%E8%A1%A8%E7%A4%BA";
    private static final String DEFAULT_SCHEMA_SET_PATH = "/schema/set_schema";
    private static final String DEFAULT_RESULT_DIR = "result";
    private static final String DEFAULT_PG_SCHEMA = "public";
    private static final String DEFAULT_PG_TABLE = "rps_table_inventory";

    private RpsTableSyncApp() {
    }

    public static void main(String[] args) throws Exception {
        CookieHandler.setDefault(COOKIE_MANAGER);

        Path configPath = args.length > 0
                ? Paths.get(args[0]).toAbsolutePath().normalize()
                : Paths.get("config", "rps-sync.properties").toAbsolutePath().normalize();
        Config config = Config.load(configPath);

        System.out.println("config=" + configPath);
        System.out.println("resultDir=" + config.resultDir());
        System.out.println("maxTables=" + config.maxTables());
        if (config.postgresEnabled()) {
            System.out.println("postgresTarget=" + config.pgSchema() + "." + config.pgTable());
        }

        SyncReport report = sync(config);

        System.out.println("tables.discovered=" + report.discoveredTables());
        System.out.println("tables.selected=" + report.selectedTables());
        System.out.println("tables.saved=" + report.savedTables());
        System.out.println("tables.failed=" + report.failedTables());
        System.out.println("summaryTsv=" + report.summaryFile());
        if (report.failureFile() != null) {
            System.out.println("failureTsv=" + report.failureFile());
        }
        System.out.println("completedAt=" + OffsetDateTime.now());
    }

    static SyncReport sync(Config config) throws Exception {
        Files.createDirectories(config.resultDir());
        Path debugDir = config.resultDir().resolve("debug");
        Path detailDir = config.resultDir().resolve("details");
        Files.createDirectories(debugDir);
        Files.createDirectories(detailDir);

        String loginHtml = get(config.loginUrl(), config);
        writeText(debugDir.resolve("_debug_login.html"), loginHtml);

        String authenticityToken = extract(loginHtml, AUTH_TOKEN_PATTERN);
        if (authenticityToken.isBlank()) {
            throw new IllegalStateException("authenticity_token was not found in login response.");
        }

        sleep(config.sleepMillis());

        String schemaBody = "schema=" + encode(config.schema())
                + "&utf8=" + encode("✓")
                + "&authenticity_token=" + encode(authenticityToken);
        String schemaHtml = post(config.schemaSetUrl(), schemaBody, config);
        writeText(debugDir.resolve("_debug_set_schema.html"), schemaHtml);

        sleep(config.sleepMillis());

        String listHtml = get(config.tableListUrl(), config);
        writeText(debugDir.resolve("_debug_table_list.html"), listHtml);

        List<TableInfo> discoveredTables = parseTables(listHtml, config.baseUrl(), config.schema());
        if (discoveredTables.isEmpty()) {
            String head = listHtml.substring(0, Math.min(1000, listHtml.length()));
            throw new IllegalStateException("No table links were detected in the table list response.\n" + head);
        }
        List<TableInfo> tables = selectTables(discoveredTables, config.maxTables());

        List<TableSnapshot> snapshots = new ArrayList<>();
        List<FailureRecord> failures = new ArrayList<>();

        try (DatabaseClient databaseClient = config.postgresEnabled() ? new DatabaseClient(config) : null) {
            for (TableInfo table : tables) {
                try {
                    sleep(config.sleepMillis());

                    String detailUrl = buildDetailUrl(config.baseUrl(), table);
                    String detailHtml = get(detailUrl, config);
                    DetailPayload detailPayload = parseDetail(detailHtml);

                    Path detailFile = detailDir.resolve(buildDetailFileName(table));
                    writeDetailFile(detailFile, detailPayload, detailHtml);

                    TableSnapshot snapshot = new TableSnapshot(
                            table.schema(),
                            table.instance(),
                            table.tableName(),
                            table.version(),
                            table.description(),
                            detailUrl,
                            detailPayload.header(),
                            detailPayload.tableHtml(),
                            config.resultDir().relativize(detailFile).toString().replace('\\', '/'),
                            OffsetDateTime.now().format(TS_FORMATTER));
                    snapshots.add(snapshot);

                    if (databaseClient != null) {
                        databaseClient.upsert(snapshot);
                    }

                    System.out.println("saved=" + table.tableName());
                } catch (Exception ex) {
                    failures.add(new FailureRecord(
                            table.tableName(),
                            table.version(),
                            safeMessage(ex)));
                    System.out.println("error=" + table.tableName() + " " + safeMessage(ex));
                }
            }
        }

        Path summaryFile = config.resultDir().resolve("table_inventory.tsv");
        writeSummary(summaryFile, snapshots);

        Path failureFile = null;
        if (!failures.isEmpty()) {
            failureFile = config.resultDir().resolve("failed_tables.tsv");
            writeFailures(failureFile, failures);
        }

        return new SyncReport(discoveredTables.size(), tables.size(), snapshots.size(), failures.size(), summaryFile, failureFile);
    }

    static List<TableInfo> parseTables(String html, String baseUrl, String fallbackSchema) throws Exception {
        List<TableInfo> tables = new ArrayList<>();
        Matcher matcher = DETAIL_LINK_PATTERN.matcher(html);
        while (matcher.find()) {
            String href = matcher.group(1);
            String fullUrl = href.startsWith("http") ? href : baseUrl + href;
            Map<String, String> query = parseQuery(fullUrl);
            String tableName = query.getOrDefault("table_name", "").trim();
            if (tableName.isEmpty()) {
                continue;
            }
            tables.add(new TableInfo(
                    tableName,
                    query.getOrDefault("version", "").trim(),
                    query.getOrDefault("instance", "").trim(),
                    query.getOrDefault("schema", fallbackSchema).trim(),
                    query.getOrDefault("description", "").trim()));
        }
        return uniqueTables(tables);
    }

    static Map<String, String> parseQuery(String url) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        String normalized = url.replace("&amp;", "&");
        int question = normalized.indexOf('?');
        if (question < 0 || question == normalized.length() - 1) {
            return result;
        }
        String query = normalized.substring(question + 1);
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                result.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    static DetailPayload parseDetail(String html) {
        return new DetailPayload(
                extract(html, HEADER_PATTERN),
                extract(html, TABLE_PATTERN));
    }

    static String sanitizeFileComponent(String value) {
        if (value == null || value.isBlank()) {
            return "unnamed";
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        sanitized = sanitized.replaceAll("_+", "_");
        sanitized = sanitized.replaceAll("^_+", "");
        sanitized = sanitized.replaceAll("_+$", "");
        return sanitized.isBlank() ? "unnamed" : sanitized;
    }

    static String buildDetailFileName(TableInfo table) {
        String version = table.version().isBlank() ? "no_version" : table.version();
        return sanitizeFileComponent(table.tableName() + "_" + version) + ".txt";
    }

    static List<TableInfo> selectTables(List<TableInfo> tables, int maxTables) {
        if (maxTables <= 0 || tables.size() <= maxTables) {
            return new ArrayList<>(tables);
        }
        return new ArrayList<>(tables.subList(0, maxTables));
    }

    private static String buildDetailUrl(String baseUrl, TableInfo table) {
        return baseUrl + "/column/show_table"
                + "?description=" + encode(table.description())
                + "&instance=" + encode(table.instance())
                + "&pm=ent"
                + "&schema=" + encode(table.schema())
                + "&table_name=" + encode(table.tableName())
                + "&version=" + encode(table.version());
    }

    private static List<TableInfo> uniqueTables(List<TableInfo> source) {
        Map<String, TableInfo> unique = new LinkedHashMap<>();
        for (TableInfo table : source) {
            unique.put(table.schema() + "|" + table.instance() + "|" + table.tableName() + "|" + table.version(), table);
        }
        return new ArrayList<>(unique.values());
    }

    private static void writeDetailFile(Path path, DetailPayload payload, String detailHtml) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            writer.println(payload.header());
            writer.println();
            writer.println(payload.tableHtml());
            writer.println();
            writer.println("----- RAW_HTML -----");
            writer.println(detailHtml);
        }
    }

    private static void writeSummary(Path path, List<TableSnapshot> snapshots) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("source_schema\tinstance_name\ttable_name\tversion\tdescription\tdetail_url\tdetail_header\tdetail_text_file\tsynced_at");
            writer.newLine();
            for (TableSnapshot snapshot : snapshots) {
                writer.write(String.join("\t",
                        tsv(snapshot.sourceSchema()),
                        tsv(snapshot.instanceName()),
                        tsv(snapshot.tableName()),
                        tsv(snapshot.version()),
                        tsv(snapshot.description()),
                        tsv(snapshot.detailUrl()),
                        tsv(snapshot.detailHeader()),
                        tsv(snapshot.detailTextFile()),
                        tsv(snapshot.syncedAt())));
                writer.newLine();
            }
        }
    }

    private static void writeFailures(Path path, List<FailureRecord> failures) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("table_name\tversion\terror");
            writer.newLine();
            for (FailureRecord failure : failures) {
                writer.write(String.join("\t",
                        tsv(failure.tableName()),
                        tsv(failure.version()),
                        tsv(failure.error())));
                writer.newLine();
            }
        }
    }

    private static String tsv(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String get(String url, Config config) throws Exception {
        HttpURLConnection connection = open(url, "GET", config);
        return readResponse(connection);
    }

    private static String post(String url, String body, Config config) throws Exception {
        HttpURLConnection connection = open(url, "POST", config);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(connection);
    }

    private static HttpURLConnection open(String url, String method, Config config) throws Exception {
        Proxy proxy = config.proxyHost() == null || config.proxyHost().isBlank()
                ? Proxy.NO_PROXY
                : new Proxy(Proxy.Type.HTTP, new InetSocketAddress(config.proxyHost(), config.proxyPort()));
        URL target = URI.create(url).toURL();
        HttpURLConnection connection = (HttpURLConnection) target.openConnection(proxy);
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(config.connectTimeoutMillis());
        connection.setReadTimeout(config.readTimeoutMillis());
        connection.setRequestProperty("User-Agent", "RpsTableSyncApp/1.0");
        connection.setRequestProperty("Accept-Charset", StandardCharsets.UTF_8.name());
        return connection;
    }

    private static String readResponse(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        }
    }

    private static void writeText(Path path, String body) throws IOException {
        Files.createDirectories(path.getParent());
        try (OutputStreamWriter writer = new OutputStreamWriter(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            writer.write(body);
        }
    }

    private static String extract(String source, Pattern pattern) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static void sleep(long sleepMillis) {
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeMessage(Exception exception) {
        return exception.getClass().getSimpleName() + ": " + Objects.toString(exception.getMessage(), "");
    }

    static final class DatabaseClient implements AutoCloseable {
        private final Connection connection;
        private final PreparedStatement upsertStatement;

        DatabaseClient(Config config) throws Exception {
            Class.forName("org.postgresql.Driver");
            this.connection = openConnection(config);
            ensureTable(connection, config);
            this.upsertStatement = connection.prepareStatement(buildUpsertSql(config));
        }

        void upsert(TableSnapshot snapshot) throws SQLException {
            upsertStatement.setString(1, snapshot.sourceSchema());
            upsertStatement.setString(2, snapshot.instanceName());
            upsertStatement.setString(3, snapshot.tableName());
            upsertStatement.setString(4, snapshot.version());
            upsertStatement.setString(5, snapshot.description());
            upsertStatement.setString(6, snapshot.detailUrl());
            upsertStatement.setString(7, snapshot.detailHeader());
            upsertStatement.setString(8, snapshot.detailTableHtml());
            upsertStatement.setString(9, snapshot.detailTextFile());
            upsertStatement.executeUpdate();
        }

        @Override
        public void close() throws SQLException {
            if (upsertStatement != null) {
                upsertStatement.close();
            }
            if (connection != null) {
                connection.close();
            }
        }

        private static Connection openConnection(Config config) throws SQLException {
            DriverManager.setLoginTimeout(Math.max(1, config.pgConnectTimeoutSeconds()));
            String url = "jdbc:postgresql://" + config.pgHost() + ":" + config.pgPort() + "/" + config.pgDatabase()
                    + "?sslmode=" + encodeUrl(config.pgSslMode())
                    + "&ApplicationName=RpsTableSyncApp";
            Properties props = new Properties();
            props.setProperty("user", config.pgUser());
            props.setProperty("password", config.pgPassword());
            props.setProperty("connectTimeout", String.valueOf(config.pgConnectTimeoutSeconds()));
            props.setProperty("socketTimeout", String.valueOf(config.pgSocketTimeoutSeconds()));
            return DriverManager.getConnection(url, props);
        }

        private static void ensureTable(Connection connection, Config config) throws SQLException {
            String qualified = qualifiedName(config.pgSchema(), config.pgTable());
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS " + quoteIdentifier(config.pgSchema()));
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS %s (
                            source_schema text NOT NULL,
                            instance_name text NOT NULL,
                            table_name text NOT NULL,
                            version text NOT NULL DEFAULT '',
                            description text NOT NULL DEFAULT '',
                            detail_url text NOT NULL DEFAULT '',
                            detail_header text NOT NULL DEFAULT '',
                            detail_table_html text NOT NULL DEFAULT '',
                            detail_text_file text NOT NULL DEFAULT '',
                            synced_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            PRIMARY KEY (source_schema, instance_name, table_name, version)
                        )
                        """.formatted(qualified));
            }
        }

        private static String buildUpsertSql(Config config) {
            String qualified = qualifiedName(config.pgSchema(), config.pgTable());
            return """
                    INSERT INTO %s (
                        source_schema,
                        instance_name,
                        table_name,
                        version,
                        description,
                        detail_url,
                        detail_header,
                        detail_table_html,
                        detail_text_file,
                        synced_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (source_schema, instance_name, table_name, version)
                    DO UPDATE SET
                        description = EXCLUDED.description,
                        detail_url = EXCLUDED.detail_url,
                        detail_header = EXCLUDED.detail_header,
                        detail_table_html = EXCLUDED.detail_table_html,
                        detail_text_file = EXCLUDED.detail_text_file,
                        synced_at = CURRENT_TIMESTAMP
                    """.formatted(qualified);
        }

        private static String qualifiedName(String schema, String table) {
            return quoteIdentifier(schema) + "." + quoteIdentifier(table);
        }

        private static String quoteIdentifier(String identifier) {
            if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Unsafe SQL identifier: " + identifier);
            }
            return "\"" + identifier + "\"";
        }

        private static String encodeUrl(String value) {
            return value.replace(" ", "%20");
        }
    }

    static final class Config {
        private final Path configPath;
        private final String baseUrl;
        private final String loginUrl;
        private final String schema;
        private final String schemaSetUrl;
        private final String tableListUrl;
        private final String proxyHost;
        private final int proxyPort;
        private final long sleepMillis;
        private final int connectTimeoutMillis;
        private final int readTimeoutMillis;
        private final Path resultDir;
        private final int maxTables;
        private final boolean postgresEnabled;
        private final String pgHost;
        private final int pgPort;
        private final String pgDatabase;
        private final String pgUser;
        private final String pgPassword;
        private final String pgSchema;
        private final String pgTable;
        private final String pgSslMode;
        private final int pgConnectTimeoutSeconds;
        private final int pgSocketTimeoutSeconds;

        private Config(
                Path configPath,
                String baseUrl,
                String loginUrl,
                String schema,
                String schemaSetUrl,
                String tableListUrl,
                String proxyHost,
                int proxyPort,
                long sleepMillis,
                int connectTimeoutMillis,
                int readTimeoutMillis,
                Path resultDir,
                int maxTables,
                boolean postgresEnabled,
                String pgHost,
                int pgPort,
                String pgDatabase,
                String pgUser,
                String pgPassword,
                String pgSchema,
                String pgTable,
                String pgSslMode,
                int pgConnectTimeoutSeconds,
                int pgSocketTimeoutSeconds) {
            this.configPath = configPath;
            this.baseUrl = baseUrl;
            this.loginUrl = loginUrl;
            this.schema = schema;
            this.schemaSetUrl = schemaSetUrl;
            this.tableListUrl = tableListUrl;
            this.proxyHost = proxyHost;
            this.proxyPort = proxyPort;
            this.sleepMillis = sleepMillis;
            this.connectTimeoutMillis = connectTimeoutMillis;
            this.readTimeoutMillis = readTimeoutMillis;
            this.resultDir = resultDir;
            this.maxTables = maxTables;
            this.postgresEnabled = postgresEnabled;
            this.pgHost = pgHost;
            this.pgPort = pgPort;
            this.pgDatabase = pgDatabase;
            this.pgUser = pgUser;
            this.pgPassword = pgPassword;
            this.pgSchema = pgSchema;
            this.pgTable = pgTable;
            this.pgSslMode = pgSslMode;
            this.pgConnectTimeoutSeconds = pgConnectTimeoutSeconds;
            this.pgSocketTimeoutSeconds = pgSocketTimeoutSeconds;
        }

        static Config load(Path path) throws IOException {
            if (!Files.exists(path)) {
                throw new IOException("Config file was not found: " + path);
            }
            Properties properties = new Properties();
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }

            String loginUrl = require(properties, "rps.loginUrl", "RPS_LOGIN_URL");
            String baseUrl = firstNonBlank(properties.getProperty("rps.baseUrl"), baseUrlFrom(loginUrl));
            String schema = firstNonBlank(properties.getProperty("rps.schema"), "KYKOWNER");
            String schemaSetUrl = combineUrl(baseUrl,
                    firstNonBlank(properties.getProperty("rps.schemaSetPath"), DEFAULT_SCHEMA_SET_PATH));
            String tableListUrl = firstNonBlank(properties.getProperty("rps.tableListUrl"),
                    combineUrl(baseUrl, DEFAULT_TABLE_LIST_PATH));
            String proxyHost = firstNonBlank(properties.getProperty("rps.proxyHost"), "");
            int proxyPort = parseInt(properties.getProperty("rps.proxyPort"), 8080);
            long sleepMillis = parseLong(properties.getProperty("rps.sleepMillis"), 100L);
            int connectTimeoutMillis = parseInt(properties.getProperty("rps.connectTimeoutMillis"), 15000);
            int readTimeoutMillis = parseInt(properties.getProperty("rps.readTimeoutMillis"), 15000);
            Path resultDir = path.getParent().getParent().resolve(
                    firstNonBlank(properties.getProperty("result.dir"), DEFAULT_RESULT_DIR)).normalize();
            int maxTables = parseInt(properties.getProperty("sync.maxTables"), 0);
            if (maxTables < 0) {
                throw new IllegalArgumentException("sync.maxTables must be 0 or greater.");
            }

            boolean postgresEnabled = Boolean.parseBoolean(firstNonBlank(properties.getProperty("pg.enabled"), "true"));
            String pgHost = firstNonBlank(properties.getProperty("pg.host"), "127.0.0.1");
            int pgPort = parseInt(properties.getProperty("pg.port"), 5432);
            String pgDatabase = firstNonBlank(properties.getProperty("pg.database"), "postgres");
            String pgUser = firstNonBlank(properties.getProperty("pg.user"), "postgres");
            String pgPassword = firstNonBlank(properties.getProperty("pg.password"), System.getenv("PG_PASSWORD"));
            String pgSchema = firstNonBlank(properties.getProperty("pg.schema"), DEFAULT_PG_SCHEMA);
            String pgTable = firstNonBlank(properties.getProperty("pg.table"), DEFAULT_PG_TABLE);
            String pgSslMode = firstNonBlank(properties.getProperty("pg.sslmode"), "disable");
            int pgConnectTimeoutSeconds = parseInt(properties.getProperty("pg.connectTimeoutSeconds"), 10);
            int pgSocketTimeoutSeconds = parseInt(properties.getProperty("pg.socketTimeoutSeconds"), 30);

            if (postgresEnabled) {
                if (pgHost.isBlank()) {
                    throw new IllegalArgumentException("pg.host is required when pg.enabled=true");
                }
                if (pgPassword == null || pgPassword.isBlank()) {
                    throw new IllegalArgumentException("pg.password or PG_PASSWORD is required when pg.enabled=true");
                }
            }

            return new Config(
                    path,
                    baseUrl,
                    loginUrl,
                    schema,
                    schemaSetUrl,
                    tableListUrl,
                    proxyHost,
                    proxyPort,
                    sleepMillis,
                    connectTimeoutMillis,
                    readTimeoutMillis,
                    resultDir,
                    maxTables,
                    postgresEnabled,
                    pgHost,
                    pgPort,
                    pgDatabase,
                    pgUser,
                    pgPassword,
                    pgSchema,
                    pgTable,
                    pgSslMode,
                    pgConnectTimeoutSeconds,
                    pgSocketTimeoutSeconds);
        }

        private static String require(Properties properties, String propertyKey, String envKey) {
            String value = firstNonBlank(properties.getProperty(propertyKey), System.getenv(envKey));
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(propertyKey + " or " + envKey + " must be configured.");
            }
            return value;
        }

        private static String firstNonBlank(String first, String second) {
            if (first != null && !first.isBlank()) {
                return first.trim();
            }
            return second == null ? "" : second.trim();
        }

        private static String baseUrlFrom(String url) {
            URI uri = URI.create(url);
            StringBuilder builder = new StringBuilder();
            builder.append(uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() > 0) {
                builder.append(":").append(uri.getPort());
            }
            return builder.toString();
        }

        private static String combineUrl(String baseUrl, String path) {
            if (path.startsWith("http://") || path.startsWith("https://")) {
                return path;
            }
            if (path.startsWith("/")) {
                return baseUrl + path;
            }
            return baseUrl + "/" + path;
        }

        private static int parseInt(String value, int defaultValue) {
            return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
        }

        private static long parseLong(String value, long defaultValue) {
            return value == null || value.isBlank() ? defaultValue : Long.parseLong(value.trim());
        }

        String baseUrl() {
            return baseUrl;
        }

        String loginUrl() {
            return loginUrl;
        }

        String schema() {
            return schema;
        }

        String schemaSetUrl() {
            return schemaSetUrl;
        }

        String tableListUrl() {
            return tableListUrl;
        }

        String proxyHost() {
            return proxyHost;
        }

        int proxyPort() {
            return proxyPort;
        }

        long sleepMillis() {
            return sleepMillis;
        }

        int connectTimeoutMillis() {
            return connectTimeoutMillis;
        }

        int readTimeoutMillis() {
            return readTimeoutMillis;
        }

        Path resultDir() {
            return resultDir;
        }

        int maxTables() {
            return maxTables;
        }

        boolean postgresEnabled() {
            return postgresEnabled;
        }

        String pgHost() {
            return pgHost;
        }

        int pgPort() {
            return pgPort;
        }

        String pgDatabase() {
            return pgDatabase;
        }

        String pgUser() {
            return pgUser;
        }

        String pgPassword() {
            return pgPassword;
        }

        String pgSchema() {
            return pgSchema;
        }

        String pgTable() {
            return pgTable;
        }

        String pgSslMode() {
            return pgSslMode;
        }

        int pgConnectTimeoutSeconds() {
            return pgConnectTimeoutSeconds;
        }

        int pgSocketTimeoutSeconds() {
            return pgSocketTimeoutSeconds;
        }
    }

    record TableInfo(String tableName, String version, String instance, String schema, String description) {
    }

    record DetailPayload(String header, String tableHtml) {
    }

    record TableSnapshot(
            String sourceSchema,
            String instanceName,
            String tableName,
            String version,
            String description,
            String detailUrl,
            String detailHeader,
            String detailTableHtml,
            String detailTextFile,
            String syncedAt) {
    }

    record FailureRecord(String tableName, String version, String error) {
    }

    record SyncReport(
            int discoveredTables,
            int selectedTables,
            int savedTables,
            int failedTables,
            Path summaryFile,
            Path failureFile) {
    }
}
