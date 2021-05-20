package io.quarkus.gradle.builder;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.UnknownDomainObjectException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.attributes.Category;
import org.gradle.api.initialization.IncludedBuild;
import org.gradle.api.internal.artifacts.dependencies.DefaultDependencyArtifact;
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency;
import org.gradle.api.plugins.Convention;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.tooling.provider.model.ParameterizedToolingModelBuilder;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.model.PlatformImports;
import io.quarkus.bootstrap.model.PlatformImportsImpl;
import io.quarkus.bootstrap.model.gradle.ArtifactCoords;
import io.quarkus.bootstrap.model.gradle.Dependency;
import io.quarkus.bootstrap.model.gradle.ModelParameter;
import io.quarkus.bootstrap.model.gradle.QuarkusModel;
import io.quarkus.bootstrap.model.gradle.WorkspaceModule;
import io.quarkus.bootstrap.model.gradle.impl.ArtifactCoordsImpl;
import io.quarkus.bootstrap.model.gradle.impl.DependencyImpl;
import io.quarkus.bootstrap.model.gradle.impl.ModelParameterImpl;
import io.quarkus.bootstrap.model.gradle.impl.QuarkusModelImpl;
import io.quarkus.bootstrap.model.gradle.impl.SourceSetImpl;
import io.quarkus.bootstrap.model.gradle.impl.WorkspaceImpl;
import io.quarkus.bootstrap.model.gradle.impl.WorkspaceModuleImpl;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.util.QuarkusModelHelper;
import io.quarkus.gradle.QuarkusPlugin;
import io.quarkus.gradle.tasks.QuarkusGradleUtils;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.util.HashUtil;

public class QuarkusModelBuilder implements ParameterizedToolingModelBuilder<ModelParameter> {

    private static final String MAIN_RESOURCES_OUTPUT = "build/resources/main";
    private static final String CLASSES_OUTPUT = "build/classes";

    private static Configuration classpathConfig(Project project, LaunchMode mode) {
        if (LaunchMode.TEST.equals(mode)) {
            return project.getConfigurations().getByName(JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME);
        }
        if (LaunchMode.DEVELOPMENT.equals(mode)) {
            return project.getConfigurations().getByName(QuarkusPlugin.DEV_MODE_CONFIGURATION_NAME);
        }
        return project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);
    }

    @Override
    public boolean canBuild(String modelName) {
        return modelName.equals(QuarkusModel.class.getName());
    }

    @Override
    public Class<ModelParameter> getParameterType() {
        return ModelParameter.class;
    }

    @Override
    public Object buildAll(String modelName, Project project) {
        final ModelParameterImpl modelParameter = new ModelParameterImpl();
        modelParameter.setMode(LaunchMode.DEVELOPMENT.toString());
        return buildAll(modelName, modelParameter, project);
    }

    @Override
    public Object buildAll(String modelName, ModelParameter parameter, Project project) {
        LaunchMode mode = LaunchMode.valueOf(parameter.getMode());

        final List<org.gradle.api.artifacts.Dependency> deploymentDeps = getEnforcedPlatforms(project);

        final PlatformImports platformImports = resolvePlatformImports(project, deploymentDeps);

        final Map<ArtifactCoords, Dependency> appDependencies = new LinkedHashMap<>();
        final Set<ArtifactCoords> visitedDeps = new HashSet<>();

        final ResolvedConfiguration resolvedConfiguration = classpathConfig(project, mode).getResolvedConfiguration();
        collectDependencies(resolvedConfiguration, mode, project, appDependencies);
        collectFirstMetDeploymentDeps(resolvedConfiguration.getFirstLevelModuleDependencies(), appDependencies,
                deploymentDeps, visitedDeps);

        final List<Dependency> extensionDependencies = collectExtensionDependencies(project, deploymentDeps);

        ArtifactCoords appArtifactCoords = new ArtifactCoordsImpl(project.getGroup().toString(), project.getName(),
                project.getVersion().toString());

        return new QuarkusModelImpl(
                new WorkspaceImpl(appArtifactCoords, getWorkspace(project.getRootProject(), mode, appArtifactCoords)),
                new LinkedList<>(appDependencies.values()),
                extensionDependencies,
                deploymentDeps.stream().map(QuarkusModelBuilder::toEnforcedPlatformDependency)
                        .filter(Objects::nonNull).collect(Collectors.toList()),
                platformImports);
    }

    private PlatformImports resolvePlatformImports(Project project,
            List<org.gradle.api.artifacts.Dependency> deploymentDeps) {
        final Configuration boms = project.getConfigurations()
                .detachedConfiguration(deploymentDeps.toArray(new org.gradle.api.artifacts.Dependency[0]));
        final PlatformImportsImpl platformImports = new PlatformImportsImpl();
        boms.getResolutionStrategy().eachDependency(d -> {
            final String group = d.getTarget().getGroup();
            final String name = d.getTarget().getName();
            if (name.endsWith(BootstrapConstants.PLATFORM_DESCRIPTOR_ARTIFACT_ID_SUFFIX)) {
                platformImports.addPlatformDescriptor(group, name, d.getTarget().getVersion(), "json",
                        d.getTarget().getVersion());
            } else if (name.endsWith(BootstrapConstants.PLATFORM_PROPERTIES_ARTIFACT_ID_SUFFIX)) {
                final DefaultDependencyArtifact dep = new DefaultDependencyArtifact();
                dep.setExtension("properties");
                dep.setType("properties");
                dep.setName(name);

                final DefaultExternalModuleDependency gradleDep = new DefaultExternalModuleDependency(
                        group, name, d.getTarget().getVersion(), null);
                gradleDep.addArtifact(dep);

                for (ResolvedArtifact a : project.getConfigurations().detachedConfiguration(gradleDep)
                        .getResolvedConfiguration().getResolvedArtifacts()) {
                    if (a.getName().equals(name)) {
                        try {
                            platformImports.addPlatformProperties(group, name, null, "properties", d.getTarget().getVersion(),
                                    a.getFile().toPath());
                        } catch (AppModelResolverException e) {
                            throw new GradleException("Failed to import platform properties " + a.getFile(), e);
                        }
                        break;
                    }
                }
            }

        });
        boms.getResolvedConfiguration();
        return platformImports;
    }

    public Set<WorkspaceModule> getWorkspace(Project project, LaunchMode mode, ArtifactCoords mainModuleCoord) {
        Set<WorkspaceModule> modules = new HashSet<>();
        for (Project subproject : project.getAllprojects()) {
            final Convention convention = subproject.getConvention();
            JavaPluginConvention javaConvention = convention.findPlugin(JavaPluginConvention.class);
            if (javaConvention == null || !javaConvention.getSourceSets().getNames().contains(SourceSet.MAIN_SOURCE_SET_NAME)) {
                continue;
            }
            if (subproject.getName().equals(mainModuleCoord.getArtifactId())
                    && subproject.getGroup().equals(mainModuleCoord.getGroupId())) {
                modules.add(getWorkspaceModule(subproject, mode, true));
            } else {
                modules.add(getWorkspaceModule(subproject, mode, false));
            }

        }
        return modules;
    }

    private WorkspaceModule getWorkspaceModule(Project project, LaunchMode mode, boolean isMainModule) {
        ArtifactCoords appArtifactCoords = new ArtifactCoordsImpl(project.getGroup().toString(), project.getName(),
                project.getVersion().toString());
        final SourceSet mainSourceSet = QuarkusGradleUtils.getSourceSet(project, SourceSet.MAIN_SOURCE_SET_NAME);
        final SourceSetImpl modelSourceSet;
        if (isMainModule && mode == LaunchMode.TEST) {
            final SourceSet testSourceSet = QuarkusGradleUtils.getSourceSet(project, SourceSet.TEST_SOURCE_SET_NAME);
            modelSourceSet = convert(mainSourceSet, testSourceSet.getOutput().getClassesDirs().getFiles());
        } else {
            modelSourceSet = convert(mainSourceSet, Collections.emptySet());
        }
        return new WorkspaceModuleImpl(appArtifactCoords,
                project.getProjectDir().getAbsoluteFile(),
                project.getBuildDir().getAbsoluteFile(), getSourceSourceSet(mainSourceSet), modelSourceSet);
    }

    private List<org.gradle.api.artifacts.Dependency> getEnforcedPlatforms(Project project) {
        final List<org.gradle.api.artifacts.Dependency> directExtension = new ArrayList<>();
        // collect enforced platforms
        final Configuration impl = project.getConfigurations()
                .getByName(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME);

        for (org.gradle.api.artifacts.Dependency d : impl.getAllDependencies()) {
            if (!(d instanceof ModuleDependency)) {
                continue;
            }
            final ModuleDependency module = (ModuleDependency) d;
            final Category category = module.getAttributes().getAttribute(Category.CATEGORY_ATTRIBUTE);
            if (category != null && (Category.ENFORCED_PLATFORM.equals(category.getName())
                    || Category.REGULAR_PLATFORM.equals(category.getName()))) {
                directExtension.add(d);
            }
        }
        return directExtension;
    }

    private void collectFirstMetDeploymentDeps(Set<ResolvedDependency> dependencies,
            Map<ArtifactCoords, Dependency> appDependencies, List<org.gradle.api.artifacts.Dependency> extensionDeps,
            Set<ArtifactCoords> visited) {
        for (ResolvedDependency d : dependencies) {
            boolean addChildExtension = true;
            boolean wasDependencyVisited = false;
            for (ResolvedArtifact artifact : d.getModuleArtifacts()) {
                ModuleVersionIdentifier moduleIdentifier = artifact.getModuleVersion().getId();
                ArtifactCoords key = toAppDependenciesKey(moduleIdentifier.getGroup(), moduleIdentifier.getName(),
                        artifact.getClassifier());
                if (!visited.add(key)) {
                    continue;
                }

                Dependency appDep = appDependencies.get(key);
                if (appDep == null) {
                    continue;
                }

                wasDependencyVisited = true;
                final org.gradle.api.artifacts.Dependency deploymentArtifact = getDeploymentArtifact(appDep);
                if (deploymentArtifact != null) {
                    extensionDeps.add(deploymentArtifact);
                    addChildExtension = false;
                }
            }
            final Set<ResolvedDependency> resolvedChildren = d.getChildren();
            if (wasDependencyVisited && addChildExtension && !resolvedChildren.isEmpty()) {
                collectFirstMetDeploymentDeps(resolvedChildren, appDependencies, extensionDeps, visited);
            }
        }
    }

    private org.gradle.api.artifacts.Dependency getDeploymentArtifact(Dependency dependency) {
        for (File file : dependency.getPaths()) {
            if (!file.exists()) {
                continue;
            }
            Properties depsProperties;
            if (file.isDirectory()) {
                Path quarkusDescr = file.toPath()
                        .resolve(BootstrapConstants.META_INF)
                        .resolve(BootstrapConstants.DESCRIPTOR_FILE_NAME);
                if (!Files.exists(quarkusDescr)) {
                    continue;
                }
                depsProperties = QuarkusModelHelper.resolveDescriptor(quarkusDescr);
            } else {
                try (FileSystem artifactFs = FileSystems.newFileSystem(file.toPath(), getClass().getClassLoader())) {
                    Path quarkusDescr = artifactFs.getPath(BootstrapConstants.META_INF)
                            .resolve(BootstrapConstants.DESCRIPTOR_FILE_NAME);
                    if (!Files.exists(quarkusDescr)) {
                        continue;
                    }
                    depsProperties = QuarkusModelHelper.resolveDescriptor(quarkusDescr);
                } catch (IOException e) {
                    throw new GradleException("Failed to process " + file, e);
                }
            }
            String value = depsProperties.getProperty(BootstrapConstants.PROP_DEPLOYMENT_ARTIFACT);
            String[] split = value.split(":");
            return new DefaultExternalModuleDependency(split[0], split[1], split[2], null);
        }
        return null;
    }

    private List<Dependency> collectExtensionDependencies(Project project,
            Collection<org.gradle.api.artifacts.Dependency> extensions) {
        final List<Dependency> platformDependencies = new LinkedList<>();

        final Configuration deploymentConfig = project.getConfigurations()
                .detachedConfiguration(extensions.toArray(new org.gradle.api.artifacts.Dependency[0]));
        final ResolvedConfiguration rc = deploymentConfig.getResolvedConfiguration();
        for (ResolvedArtifact a : rc.getResolvedArtifacts()) {
            if (!isDependency(a)) {
                continue;
            }

            final Dependency dependency = toDependency(a);
            platformDependencies.add(dependency);
        }

        return platformDependencies;
    }

    private void collectDependencies(ResolvedConfiguration configuration,
            LaunchMode mode, Project project, Map<ArtifactCoords, Dependency> appDependencies) {

        final Set<ResolvedArtifact> artifacts = configuration.getResolvedArtifacts();
        Set<File> artifactFiles = null;

        // if the number of artifacts is less than the number of files then probably
        // the project includes direct file dependencies
        if (artifacts.size() < configuration.getFiles().size()) {
            artifactFiles = new HashSet<>(artifacts.size());
        }
        for (ResolvedArtifact a : artifacts) {
            if (!isDependency(a)) {
                continue;
            }
            final DependencyImpl dep = initDependency(a);
            if ((LaunchMode.DEVELOPMENT.equals(mode) || LaunchMode.TEST.equals(mode)) &&
                    a.getId().getComponentIdentifier() instanceof ProjectComponentIdentifier) {
                IncludedBuild includedBuild = includedBuild(project, a.getName());
                if ("test-fixtures".equals(a.getClassifier()) || "test".equals(a.getClassifier())) {
                    //TODO: test-fixtures are broken under the new ClassLoading model
                    dep.addPath(a.getFile());
                } else {
                    if (includedBuild != null) {
                        addSubstitutedProject(dep, includedBuild.getProjectDir());
                    } else {
                        Project projectDep = project.getRootProject()
                                .findProject(
                                        ((ProjectComponentIdentifier) a.getId().getComponentIdentifier()).getProjectPath());
                        addDevModePaths(dep, a, projectDep);
                    }
                }
            } else {
                dep.addPath(a.getFile());
            }
            appDependencies.put(toAppDependenciesKey(dep.getGroupId(), dep.getName(), dep.getClassifier()), dep);
            if (artifactFiles != null) {
                artifactFiles.add(a.getFile());
            }
        }

        if (artifactFiles != null) {
            // detect FS paths that aren't provided by the resolved artifacts
            for (File f : configuration.getFiles()) {
                if (artifactFiles.contains(f)) {
                    continue;
                }
                // here we are trying to represent a direct FS path dependency
                // as an artifact dependency
                // SHA1 hash is used to avoid long file names in the lib dir
                final String parentPath = f.getParent();
                final String group = HashUtil.sha1(parentPath == null ? f.getName() : parentPath);
                String name = f.getName();
                String type = "jar";
                if (!f.isDirectory()) {
                    final int dot = f.getName().lastIndexOf('.');
                    if (dot > 0) {
                        name = f.getName().substring(0, dot);
                        type = f.getName().substring(dot + 1);
                    }
                }
                // hash could be a better way to represent the version
                final String version = String.valueOf(f.lastModified());
                final ArtifactCoords key = toAppDependenciesKey(group, name, "");
                final DependencyImpl dep = new DependencyImpl(name, group, version, "compile", type, null);
                dep.addPath(f);
                appDependencies.put(key, dep);
            }
        }
    }

    private void addDevModePaths(final DependencyImpl dep, ResolvedArtifact a, Project project) {
        final JavaPluginConvention javaConvention = project.getConvention().findPlugin(JavaPluginConvention.class);
        if (javaConvention == null) {
            dep.addPath(a.getFile());
            return;
        }
        final SourceSet mainSourceSet = javaConvention.getSourceSets().findByName(SourceSet.MAIN_SOURCE_SET_NAME);
        if (mainSourceSet == null) {
            dep.addPath(a.getFile());
            return;
        }
        final String classes = QuarkusGradleUtils.getClassesDir(mainSourceSet, project.getBuildDir(), false);
        if (classes == null) {
            dep.addPath(a.getFile());
        } else {
            final File classesDir = new File(classes);
            if (classesDir.exists()) {
                dep.addPath(classesDir);
            } else {
                dep.addPath(a.getFile());
            }
        }
        for (File resourcesDir : mainSourceSet.getResources().getSourceDirectories()) {
            if (resourcesDir.exists()) {
                dep.addPath(resourcesDir);
            }
        }
        final Task resourcesTask = project.getTasks().findByName(JavaPlugin.PROCESS_RESOURCES_TASK_NAME);
        for (File outputDir : resourcesTask.getOutputs().getFiles()) {
            if (outputDir.exists()) {
                dep.addPath(outputDir);
            }
        }
    }

    private void addSubstitutedProject(final DependencyImpl dep, File projectFile) {
        File mainResourceDirectory = new File(projectFile, MAIN_RESOURCES_OUTPUT);
        if (mainResourceDirectory.exists()) {
            dep.addPath(mainResourceDirectory);
        }
        File classesOutput = new File(projectFile, CLASSES_OUTPUT);
        File[] languageDirectories = classesOutput.listFiles();
        if (languageDirectories == null) {
            throw new GradleException(
                    "The project does not contain a class output directory. " + classesOutput.getPath() + " must exist.");
        }
        for (File languageDirectory : languageDirectories) {
            if (languageDirectory.isDirectory()) {
                for (File sourceSet : languageDirectory.listFiles()) {
                    if (sourceSet.isDirectory() && sourceSet.getName().equals(SourceSet.MAIN_SOURCE_SET_NAME)) {
                        dep.addPath(sourceSet);
                    }
                }
            }
        }
    }

    private IncludedBuild includedBuild(final Project project, final String projectName) {
        try {
            return project.getGradle().includedBuild(projectName);
        } catch (UnknownDomainObjectException ignore) {
            return null;
        }
    }

    private SourceSetImpl convert(SourceSet sourceSet, Set<File> additionalSourceDirs) {
        final Set<File> existingSrcDirs = new LinkedHashSet<>();
        for (File srcDir : sourceSet.getOutput().getClassesDirs().getFiles()) {
            if (srcDir.exists()) {
                existingSrcDirs.add(srcDir);
            }
        }
        existingSrcDirs.addAll(additionalSourceDirs);
        if (sourceSet.getOutput().getResourcesDir().exists()) {
            return new SourceSetImpl(
                    existingSrcDirs,
                    Collections.singleton(sourceSet.getOutput().getResourcesDir()));
        }
        return new SourceSetImpl(existingSrcDirs);
    }

    private io.quarkus.bootstrap.model.gradle.SourceSet getSourceSourceSet(SourceSet sourceSet) {
        return new SourceSetImpl(sourceSet.getAllJava().getSrcDirs(),
                sourceSet.getResources().getSourceDirectories().getFiles());
    }

    private static boolean isDependency(ResolvedArtifact a) {
        return BootstrapConstants.JAR.equalsIgnoreCase(a.getExtension()) || "exe".equalsIgnoreCase(a.getExtension()) ||
                a.getFile().isDirectory();
    }

    /**
     * Creates an instance of Dependency and associates it with the ResolvedArtifact's path
     */
    static Dependency toDependency(ResolvedArtifact a) {
        final DependencyImpl dependency = initDependency(a);
        dependency.addPath(a.getFile());
        return dependency;
    }

    static Dependency toEnforcedPlatformDependency(org.gradle.api.artifacts.Dependency dependency) {
        if (dependency instanceof ExternalModuleDependency) {
            ExternalModuleDependency emd = (ExternalModuleDependency) dependency;
            Category category = emd.getAttributes().getAttribute(Category.CATEGORY_ATTRIBUTE);
            if (category != null && Category.ENFORCED_PLATFORM.equals(category.getName())) {
                return new DependencyImpl(emd.getName(), emd.getGroup(), emd.getVersion(),
                        "compile", "pom", null);
            }
        }
        return null;
    }

    /**
     * Creates an instance of DependencyImpl but does not associates it with a path
     */
    private static DependencyImpl initDependency(ResolvedArtifact a) {
        final String[] split = a.getModuleVersion().toString().split(":");
        return new DependencyImpl(split[1], split[0], split.length > 2 ? split[2] : null,
                "compile", a.getType(), a.getClassifier());
    }

    private static ArtifactCoords toAppDependenciesKey(String groupId, String artifactId, String classifier) {
        // Default classifier is empty string and not null value, lets keep it that way
        classifier = classifier == null ? "" : classifier;
        return new ArtifactCoordsImpl(groupId, artifactId, classifier, "", ArtifactCoordsImpl.TYPE_JAR);
    }
}
