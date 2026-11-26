package br.api.universum.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.server.environment.MultipleJGitEnvironmentRepository;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "encrypt.key=study-only-encryption-key-not-for-production")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ConfigServerIntegrationTests {
    private static final LocalConfigRepository REPOSITORY = createRepository();
    private static final String ADMIN = "study-admin";
    private static final String PASSWORD = "study-only-admin-password";
    private final HttpClient http = HttpClient.newHttpClient();
    @LocalServerPort int port;
    @Autowired ApplicationContext context;

    private static LocalConfigRepository createRepository() {
        try {
            return new LocalConfigRepository();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("CONFIG_GIT_URI", REPOSITORY::uri);
        properties.add("CONFIG_GIT_USERNAME", () -> "study-git-user");
        properties.add("CONFIG_GIT_TOKEN", () -> "study-only-git-token");
        properties.add("CONFIG_GIT_DEFAULT_LABEL", () -> "main");
        properties.add("CONFIG_ADMIN_USERNAME", () -> ADMIN);
        properties.add("CONFIG_ADMIN_PASSWORD", () -> PASSWORD);
        properties.add("spring.cloud.config.server.git.basedir", REPOSITORY::cacheDirectory);
    }

    @AfterAll
    static void stopGit() throws Exception {
        REPOSITORY.close();
    }

    @Test
    void startsWithTheOfficialGitBackendAndAWritableClone() {
        var key = context.getBean(org.springframework.cloud.bootstrap.encrypt.KeyProperties.class);
        assertThat(key.getKey()).isEqualTo(LocalConfigRepository.ENCRYPTION_KEY);
        assertThat(key.getSalt()).isEqualTo("deadbeef");
        assertThat(context.getBean("defaultEnvironmentRepository"))
                .isInstanceOf(MultipleJGitEnvironmentRepository.class);
        assertThat(context.getEnvironment().getActiveProfiles()).doesNotContain("native", "dev", "prod");
        assertThat(Path.of(REPOSITORY.cacheDirectory(), ".git")).isDirectory();
    }

    @ParameterizedTest
    @CsvSource({"identity,dev", "identity,prod", "gateway,dev", "gateway,prod",
            "finance-service,dev", "finance-service,prod"})
    void servesAllApplicationsAndEnvironmentsWithoutAuthentication(String application, String profile) throws Exception {
        var response = get("/" + application + "/" + profile);
        assertThat(response.statusCode()).isEqualTo(200);
        Environment environment = new JsonMapper().readValue(response.body(), Environment.class);
        assertThat(environment.getProfiles()).containsExactly(profile);
        assertThat(environment.getVersion()).isNotBlank();
        Map<String, Object> properties = merge(environment);
        assertThat(properties).containsEntry("universum.organization", "Universum Digitalis")
                .containsEntry("universum.message", application + "-" + profile);
        if (application.equals("gateway")) {
            assertThat(properties.keySet()).noneMatch(key -> key.startsWith("spring.datasource."));
        } else {
            assertThat(properties).containsEntry("spring.datasource.password",
                    "fictitious-" + application + "-" + profile + "-password");
        }
        assertThat(response.body()).doesNotContain("{cipher}", PASSWORD, "study-only-git-token",
                LocalConfigRepository.ENCRYPTION_KEY, "spring.security.user.password");
    }

    @Test
    void acceptsDefaultProfileDuringConfigDataBootstrap() throws Exception {
        var response = get("/identity/default");
        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> properties = merge(new JsonMapper().readValue(response.body(), Environment.class));
        assertThat(properties).containsEntry("universum.organization", "Universum Digitalis")
                .doesNotContainKey("spring.datasource.password");
    }

    @Test
    void usesOfficialLabelProfileAndPrecedenceRules() throws Exception {
        var response = get("/identity/dev,prod/main");
        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> properties = merge(new JsonMapper().readValue(response.body(), Environment.class));
        assertThat(properties).containsEntry("universum.message", "identity-prod");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/identity-dev.yml", "/identity-prod.properties"})
    void supportsOfficialAlternativeRepresentations(String path) throws Exception {
        assertThat(get(path).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/encrypt", "/decrypt", "/encrypt/identity/dev", "/decrypt/identity/prod"})
    void protectsEveryAdministrativeVariant(String path) throws Exception {
        assertThat(post(path, "fictitious-input", null).statusCode()).isEqualTo(401);
        assertThat(post(path, "fictitious-input", "incorrect-password").statusCode()).isEqualTo(401);
    }

    @Test
    void protectsEncryptionStatusAndKeyEndpoints() throws Exception {
        assertThat(get("/encrypt/status").statusCode()).isEqualTo(401);
        assertThat(get("/key").statusCode()).isEqualTo(401);
        assertThat(get("/key/identity/dev").statusCode()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/identity/dev"})
    void encryptsAndDecryptsUsingOfficialEndpoints(String suffix) throws Exception {
        String plain = "fictitious-value-with-+-$-and-spaces";
        var encrypted = post("/encrypt" + suffix, plain, PASSWORD);
        assertThat(encrypted.statusCode()).isEqualTo(200);
        assertThat(encrypted.body()).isNotBlank().doesNotContain(plain);
        var decrypted = post("/decrypt" + suffix, encrypted.body(), PASSWORD);
        assertThat(decrypted.statusCode()).isEqualTo(200);
        assertThat(decrypted.body()).isEqualTo(plain);
    }

    @Test
    void fetchesPublishedGitChangesWithoutRestartingServer() throws Exception {
        var before = new JsonMapper().readValue(get("/identity/dev").body(), Environment.class);
        REPOSITORY.update();
        var after = new JsonMapper().readValue(get("/identity/dev").body(), Environment.class);
        assertThat(after.getVersion()).isNotEqualTo(before.getVersion());
        assertThat(merge(after)).containsEntry("study-update", "published");
    }

    @Test
    void exposesHealthWithoutAdministrativeCredentials() throws Exception {
        var response = get("/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void doesNotLogCredentialsOrDecryptedValues(CapturedOutput output) throws Exception {
        assertThat(get("/identity/prod").statusCode()).isEqualTo(200);
        assertThat(output.getAll()).doesNotContain(PASSWORD, LocalConfigRepository.ENCRYPTION_KEY,
                "study-only-git-token", "fictitious-identity-prod-password");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void realClientNeedsOnlyThreeEnvironmentVariables(String profile) throws Exception {
        var result = runClient(profile, "http://127.0.0.1:" + port);
        assertThat(result.exitCode()).as("Veja o log do cliente em %s", result.log()).isZero();
        assertThat(Files.readString(result.log())).contains("CONFIG_CLIENT_OK application=identity profile=" + profile);
    }

    @Test
    void mandatoryImportStopsClientWhenServerIsUnavailable() throws Exception {
        int unavailablePort;
        try (var socket = new ServerSocket(0)) {
            unavailablePort = socket.getLocalPort();
        }
        var result = runClient("dev", "http://127.0.0.1:" + unavailablePort);
        assertThat(result.exitCode()).isNotZero();
        assertThat(Files.readString(result.log())).contains("ConfigClientFailFastException")
                .doesNotContain("CONFIG_CLIENT_OK");
    }

    private ClientResult runClient(String profile, String url) throws Exception {
        Path log = Files.createTempFile(Path.of("target"), "config-client-", ".log");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        // Um cliente real não depende do Config Server, cujo postprocessor desabilita o próprio Config Client.
        classpath = java.util.Arrays.stream(classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .filter(entry -> !Path.of(entry).getFileName().toString().startsWith("spring-cloud-config-server-"))
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        var builder = new ProcessBuilder(javaExecutable, "-cp", classpath, "br.api.universum.configclient.ConfigClientProbe")
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("SPRING_APPLICATION_NAME", "identity");
        builder.environment().put("SPRING_PROFILES_ACTIVE", profile);
        builder.environment().put("SPRING_CONFIG_IMPORT", "configserver:" + url);
        var process = builder.start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Timeout do Config Client; veja " + log);
        }
        return new ClientResult(process.exitValue(), log);
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String password) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "text/plain");
        if (password != null) {
            request.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((ADMIN + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> merge(Environment environment) {
        Map<String, Object> properties = new HashMap<>();
        environment.getPropertySources().reversed().forEach(source ->
                source.getSource().forEach((key, value) -> properties.put(key.toString(), value)));
        return properties;
    }

    private record ClientResult(int exitCode, Path log) { }
}
