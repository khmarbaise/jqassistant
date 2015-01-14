package com.buschmais.jqassistant.plugin.m2repo.impl.scanner;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Date;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.MAVEN;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.buschmais.jqassistant.core.scanner.api.Scanner;
import com.buschmais.jqassistant.core.scanner.api.Scope;
import com.buschmais.jqassistant.core.store.api.Store;
import com.buschmais.jqassistant.core.store.api.model.Descriptor;
import com.buschmais.jqassistant.plugin.common.api.scanner.AbstractScannerPlugin;
import com.buschmais.jqassistant.plugin.common.api.scanner.filesystem.FileResource;
import com.buschmais.jqassistant.plugin.m2repo.api.model.ContainsArtifactDescriptor;
import com.buschmais.jqassistant.plugin.m2repo.api.model.MavenRepositoryDescriptor;
import com.buschmais.jqassistant.plugin.m2repo.api.model.RepositoryArtifactDescriptor;
import com.buschmais.jqassistant.plugin.maven3.api.scanner.MavenScope;

/**
 * A scanner for (remote) maven repositories.
 * 
 * @author pherklotz
 */
public class MavenRepositoryScannerPlugin extends AbstractScannerPlugin<URL, MavenRepositoryDescriptor> {

    private static final Logger LOGGER = LoggerFactory.getLogger(MavenRepositoryScannerPlugin.class);
    private MavenIndex mavenIndex;
    private ArtifactResolver artifactResolver;

    private static final String PROPERTY_NAME_DIRECTORY = "m2repo.directory";
    private static final String PROPERTY_NAME_DELETE_ARTIFACTS = "m2repo.delete.artifacts";

    private File localDirectory;

    private boolean deleteArtifactsAfterScan = false;

    /**
     * Creates a new Object.
     */
    public MavenRepositoryScannerPlugin() {
    }

    /**
     * Creates a new Object. For test purposes only.
     * 
     * @param mavenIndex
     *            MavenIndex
     * @param artifactResolver
     *            ArtifactResolver
     */
    public MavenRepositoryScannerPlugin(MavenIndex mavenIndex, ArtifactResolver artifactResolver) {
        this.mavenIndex = mavenIndex;
        this.artifactResolver = artifactResolver;
    }

    /** {@inheritDoc} */
    @Override
    public boolean accepts(URL item, String path, Scope scope) throws IOException {
        return MavenScope.REPOSITORY == scope;
    }

    /**
     * Finds or creates a repository descriptor for the given url.
     * 
     * @param store
     *            the {@link Store}
     * @param url
     *            the repository url
     * @return a {@link MavenRepositoryDescriptor} for the given url.
     */
    private MavenRepositoryDescriptor getRepositoryDescriptor(Store store, String url) {
        MavenRepositoryDescriptor repositoryDescriptor = store.find(MavenRepositoryDescriptor.class, url);
        if (repositoryDescriptor == null) {
            repositoryDescriptor = store.create(MavenRepositoryDescriptor.class);
            repositoryDescriptor.setUrl(url);
        }
        return repositoryDescriptor;
    }

    /** {@inheritDoc} */
    @Override
    protected void initialize() {
        super.initialize();
        localDirectory = new File("./target/m2repo-data");
        if (getProperties().containsKey(PROPERTY_NAME_DIRECTORY)) {
            localDirectory = new File(getProperties().get(PROPERTY_NAME_DIRECTORY).toString());
        }
        if (!localDirectory.exists()) {
            localDirectory.mkdirs();
        }

        if (getProperties().containsKey(PROPERTY_NAME_DELETE_ARTIFACTS)) {
            deleteArtifactsAfterScan = BooleanUtils.toBoolean(getProperties().get(PROPERTY_NAME_DELETE_ARTIFACTS).toString());
        }

    }

    /**
     * Migrates the descriptor to a {@link RepositoryArtifactDescriptor} and
     * creates a relation between the descriptor and the repoDescriptor.
     * 
     * @param store
     *            the {@link Store}
     * @param repoDescriptor
     *            the containing {@link MavenRepositoryDescriptor}
     * @param lastModified
     *            timestamp of last martifact modification
     * @param descriptor
     *            the artifact descriptor
     */
    private void migrateToArtifactAndSetRelation(Store store, MavenRepositoryDescriptor repoDescriptor, String lastModified, Descriptor descriptor) {
        RepositoryArtifactDescriptor artifactDescriptor = store.migrate(descriptor, RepositoryArtifactDescriptor.class);

        ContainsArtifactDescriptor containsDescriptor = store.create(repoDescriptor, ContainsArtifactDescriptor.class, artifactDescriptor);
        containsDescriptor.setLastModified(StringUtils.defaultString(lastModified));
    }

    /**
     * Resolves, scans and add the artifact to the
     * {@link MavenRepositoryDescriptor}.
     * 
     * @param scanner
     *            the {@link Scanner}
     * @param repoDescriptor
     *            the {@link MavenRepositoryDescriptor}
     * @param artifactResolver
     *            the {@link ArtifactResolver}
     * @param artifactInfo
     *            informations about the searches artifact
     * @throws IOException
     */
    private void resolveAndScan(Scanner scanner, MavenRepositoryDescriptor repoDescriptor, ArtifactResolver artifactResolver, ArtifactInfo artifactInfo)
            throws IOException {
        File artifactFile = null;
        try {
            String groupId = artifactInfo.getFieldValue(MAVEN.GROUP_ID);
            String artifactId = artifactInfo.getFieldValue(MAVEN.ARTIFACT_ID);
            String packaging = artifactInfo.getFieldValue(MAVEN.PACKAGING);
            String version = artifactInfo.getFieldValue(MAVEN.VERSION);

            artifactFile = artifactResolver.downloadArtifact(groupId, artifactId, packaging, version);
            try (FileResource fileResource = new DefaultFileResource(artifactFile)) {
                Descriptor descriptor = scanner.scan(fileResource, artifactFile.getAbsolutePath(), null);
                if (descriptor != null) {
                    String lastModified = new Date(artifactInfo.lastModified).toString();
                    Store store = scanner.getContext().getStore();
                    migrateToArtifactAndSetRelation(store, repoDescriptor, lastModified, descriptor);
                } else {
                    LOGGER.debug("Could not scan artifact: " + artifactFile.getAbsoluteFile());
                }
            }
        } catch (ArtifactResolutionException e) {
            LOGGER.warn(e.getMessage());
        } finally {
            if (artifactFile != null && deleteArtifactsAfterScan) {
                artifactFile.delete();
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public MavenRepositoryDescriptor scan(URL item, String path, Scope scope, Scanner scanner) throws IOException {
        String userInfo = item.getUserInfo();
        String username = StringUtils.substringBefore(userInfo, ":");
        String password = StringUtils.substringAfter(userInfo, ":");

        File localRepoDir = new File(localDirectory, DigestUtils.md5Hex(item.toString()));

        Store store = scanner.getContext().getStore();
        // handles the remote maven index
        if (mavenIndex == null) {
            mavenIndex = new MavenIndex(item, localRepoDir);
        }
        // the MavenRepositoryDescriptor
        MavenRepositoryDescriptor repoDescriptor = getRepositoryDescriptor(store, item.toString());
        // used to resolve (remote) artifacts
        if (artifactResolver == null) {
            artifactResolver = new ArtifactResolver(item, localRepoDir, username, password);
        }
        Date lastUpdateTime = mavenIndex.getLastUpdateLocalRepo();
        // if no index found
        if (lastUpdateTime == null) {
            lastUpdateTime = new Date(0L);
        }
        mavenIndex.updateIndex(username, password);

        // Search artifacts
        Iterable<ArtifactInfo> searchResponse = mavenIndex.getArtifactsSince(lastUpdateTime);
        int numberOfArtifacts = 0;
        for (ArtifactInfo ai : searchResponse) {
            resolveAndScan(scanner, repoDescriptor, artifactResolver, ai);
            numberOfArtifacts++;
            if (numberOfArtifacts > 10) {// TODO remove after plugin completion
                break;
            }
        }
        LOGGER.info("Scanned " + numberOfArtifacts + " new artifacts.");
        return repoDescriptor;
    }
}