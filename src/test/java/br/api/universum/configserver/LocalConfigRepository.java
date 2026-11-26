package br.api.universum.configserver;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.Daemon;
import org.eclipse.jgit.transport.DaemonClient;
import org.eclipse.jgit.transport.resolver.FileResolver;
import org.springframework.cloud.context.encrypt.EncryptorFactory;

/** Fixture de teste: Git real por TCP local, para exercitar clone, fetch e cache do framework. */
final class LocalConfigRepository implements AutoCloseable {
    static final String ENCRYPTION_KEY = "study-only-encryption-key-not-for-production";
    private final Path directory;
    private final Path cache;
    private final Git git;
    private final Daemon daemon;

    LocalConfigRepository() throws Exception {
        Path target = Path.of("target").toAbsolutePath();
        Files.createDirectories(target);
        directory = Files.createTempDirectory(target, "config-git-origin-");
        cache = Files.createTempDirectory(target, "config-git-cache-");
        git = Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call();
        var encryptor = new EncryptorFactory().create(ENCRYPTION_KEY);
        try (var examples = Files.list(Path.of("examples/config-repo"))) {
            for (Path example : examples.toList()) {
                String content = Files.readString(example);
                String name = example.getFileName().toString();
                if (content.contains("  datasource:")) {
                    String applicationAndProfile = name.substring(0, name.length() - ".yml".length());
                    String encrypted = encryptor.encrypt("fictitious-" + applicationAndProfile + "-password");
                    content += "\nspring.datasource.password: '{cipher}" + encrypted + "'\n";
                }
                Files.writeString(directory.resolve(name), content);
            }
        }
        commit("Configurações fictícias iniciais");
        var resolver = new FileResolver<DaemonClient>();
        resolver.exportRepository("config.git", git.getRepository());
        daemon = new Daemon(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        daemon.setRepositoryResolver(resolver);
        daemon.start();
    }

    String uri() {
        return "git://127.0.0.1:" + daemon.getAddress().getPort() + "/config.git";
    }

    String cacheDirectory() {
        return cache.toString();
    }

    void update() throws Exception {
        Files.writeString(directory.resolve("application.yml"),
                Files.readString(directory.resolve("application.yml")) + "\nstudy-update: published\n");
        commit("Atualização fictícia");
    }

    private void commit(String message) throws Exception {
        git.add().addFilepattern(".").call();
        git.commit().setMessage(message).setAuthor("Study fixture", "study@example.invalid")
                .setCommitter("Study fixture", "study@example.invalid").call();
    }

    @Override
    public void close() throws IOException {
        daemon.stop();
        git.close();
        // JGit marca packfiles como somente leitura; libere os artefatos de teste para mvn clean no Windows.
        for (Path repository : new Path[] {directory, cache}) {
            try (var files = Files.walk(repository)) {
                files.filter(Files::isRegularFile).forEach(file -> file.toFile().setWritable(true));
            }
        }
    }
}
