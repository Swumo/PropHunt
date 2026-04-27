package me.swumo.prophunt;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

/**
 * Resolves PropHunt's runtime libraries (Cloud command framework + InvUI) at
 * server startup using Paper's library loader. Declared in
 * {@code paper-plugin.yml} via the {@code loader:} key.
 *
 * <p>This replaces shading: dependencies are downloaded once into Paper's
 * shared library cache and added to the plugin's isolated classloader, so
 * other plugins shipping different Cloud / InvUI versions cannot conflict.
 */
@SuppressWarnings("UnstableApiUsage")
public class PropHuntPluginLoader implements PluginLoader {

    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();

        resolver.addRepository(new RemoteRepository.Builder(
            "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
        resolver.addRepository(new RemoteRepository.Builder(
            "xenondevs", "default", "https://repo.xenondevs.xyz/releases").build());

        addDep(resolver, "org.incendo:cloud-paper:2.0.0-beta.10");
        addDep(resolver, "org.incendo:cloud-annotations:2.0.0");
        addDep(resolver, "org.incendo:cloud-minecraft-extras:2.0.0-beta.10");
        addDep(resolver, "xyz.xenondevs.invui:invui:pom:1.49");

        classpathBuilder.addLibrary(resolver);
    }

    private static void addDep(MavenLibraryResolver resolver, String coordinates) {
        resolver.addDependency(new Dependency(new DefaultArtifact(coordinates), null));
    }
}
