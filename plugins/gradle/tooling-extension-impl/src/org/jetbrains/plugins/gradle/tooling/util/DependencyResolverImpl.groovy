/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.jetbrains.plugins.gradle.tooling.util

import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.ListMultimap
import com.google.common.collect.Multimap
import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.TupleConstructor
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ComponentArtifactsResult
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.component.Artifact
import org.gradle.api.component.Component
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.plugins.WarPlugin
import org.gradle.api.specs.Specs
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.language.base.artifact.SourcesArtifact
import org.gradle.language.java.artifact.JavadocArtifact
import org.gradle.plugins.ide.idea.IdeaPlugin
import org.gradle.util.GradleVersion
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import org.jetbrains.plugins.gradle.model.*

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * @author Vladislav.Soroka
 * @since 8/19/2015
 */
@CompileStatic
class DependencyResolverImpl implements DependencyResolver {
  static boolean isArtifactResolutionQuerySupported = GradleVersion.current() >= GradleVersion.version("2.0")
  static boolean isDependencySubstitutionsSupported = GradleVersion.current() > GradleVersion.version("2.5")

  @NotNull
  private final Project myProject
  private final boolean myIsPreview
  private final boolean myDownloadJavadoc
  private final boolean myDownloadSources
  private final SourceSetCachedFinder mySourceSetFinder

  @SuppressWarnings("GroovyUnusedDeclaration")
  DependencyResolverImpl(@NotNull Project project, boolean isPreview) {
    myProject = project
    myIsPreview = isPreview
    myDownloadJavadoc = false
    myDownloadSources = false
    mySourceSetFinder = new SourceSetCachedFinder(project)
  }

  DependencyResolverImpl(
    @NotNull Project project,
    boolean isPreview,
    boolean downloadJavadoc,
    boolean downloadSources,
    SourceSetCachedFinder sourceSetFinder) {
    myProject = project
    myIsPreview = isPreview
    myDownloadJavadoc = downloadJavadoc
    myDownloadSources = downloadSources
    mySourceSetFinder = sourceSetFinder
  }

  @Override
  Collection<org.jetbrains.plugins.gradle.model.ExternalDependency> resolveDependencies(@Nullable String configurationName) {
    return resolveDependencies(configurationName, null)
  }

  Collection<org.jetbrains.plugins.gradle.model.ExternalDependency> resolveDependencies(
    @Nullable String configurationName,
    @Nullable String scope) {
    if (configurationName == null) return Collections.emptyList()
    resolveDependencies(myProject.configurations.findByName(configurationName), scope).dependencies
  }

  @Override
  Collection<org.jetbrains.plugins.gradle.model.ExternalDependency> resolveDependencies(@Nullable Configuration configuration) {
    resolveDependencies(configuration, null).dependencies
  }

  @CompileStatic
  @TupleConstructor
  static class ResolveDependenciesResult {
    Collection<org.jetbrains.plugins.gradle.model.ExternalDependency> dependencies
    Collection<File> files
  }

  ResolveDependenciesResult resolveDependencies(@Nullable Configuration configuration, @Nullable String scope) {
    if (configuration == null || configuration.allDependencies.isEmpty()) {
      return new ResolveDependenciesResult((List<org.jetbrains.plugins.gradle.model.ExternalDependency>)Collections.emptyList(),
                                           (List<File>)Collections.emptyList())
    }

    final Collection<org.jetbrains.plugins.gradle.model.ExternalDependency> result = new LinkedHashSet<>()

    List<File> resolvedFileDependencies = []
    if (!myIsPreview && isArtifactResolutionQuerySupported) {
      Class<? extends Component> jvmLibrary = null
      try {
        jvmLibrary = Class.forName('org.gradle.jvm.JvmLibrary')
      }
      catch (ClassNotFoundException ignored) {
      }
      if (jvmLibrary == null) {
        try {
          jvmLibrary = Class.forName('org.gradle.runtime.jvm.JvmLibrary')
        }
        catch (ClassNotFoundException ignored) {
        }
      }
      if (jvmLibrary != null) {
        Class<? extends Artifact>[] artifactTypes = ([myDownloadSources ? SourcesArtifact : null, myDownloadJavadoc ? JavadocArtifact :
                                                                                                  null].findAll { it != null }) as Class[]
        Set<ResolvedArtifact> resolvedArtifacts = configuration.resolvedConfiguration.lenientConfiguration.getArtifacts(Specs.SATISFIES_ALL)

        Multimap<ModuleVersionIdentifier, ResolvedArtifact> artifactMap = ArrayListMultimap.create()
        for (ResolvedArtifact resolvedArtifact : resolvedArtifacts) {
          artifactMap.put(resolvedArtifact.moduleVersion.id, resolvedArtifact)
        }
        //noinspection GroovyAssignabilityCheck
        Set<ComponentArtifactsResult> componentResults = myProject.dependencies.createArtifactResolutionQuery()
          .forComponents(resolvedArtifacts.collect { toComponentIdentifier(it.moduleVersion.id) })
          .withArtifacts((Class<? extends Component>)jvmLibrary, (Class<? extends Artifact>[])artifactTypes)
          .execute()
          .getResolvedComponents()

        Map<ComponentIdentifier, ComponentArtifactsResult> componentResultsMap = [:]
        for (ComponentArtifactsResult componentResult : componentResults) {
          componentResultsMap.put(componentResult.id, componentResult)
        }

        ListMultimap<ModuleComponentIdentifier, ProjectDependency> configurationProjectDependencies =
          resolveConfigurationProjectDependencies(configuration)

        ResolutionResult resolutionResult = configuration.incoming.resolutionResult
        if(!configuration.resolvedConfiguration.hasError()) {
          Collection<File> fileDeps = new LinkedHashSet<File>(configuration.incoming.files.files)
          fileDeps.removeAll(artifactMap.values())
          for (ProjectDependency projectDependency : configurationProjectDependencies.values()) {
            def intersect = fileDeps.intersect(projectDependency.resolve())
            if(!intersect.isEmpty()) {
              def fileCollectionDependency = new DefaultFileCollectionDependency(intersect)
              fileCollectionDependency.scope = scope
              result.add(fileCollectionDependency)
              fileDeps.removeAll(intersect)
            }
          }
          for (File file : fileDeps) {
            def fileCollectionDependency = new DefaultFileCollectionDependency([file])
            fileCollectionDependency.scope = scope
            result.add(fileCollectionDependency)
          }
        }

        def dependencyResultsTransformer = new DependencyResultsTransformer(myProject, artifactMap, componentResultsMap, configurationProjectDependencies, scope)
        result.addAll(dependencyResultsTransformer.transform(Collection.cast(resolutionResult.root.dependencies)))

        resolvedFileDependencies.addAll(dependencyResultsTransformer.resolvedDepsFiles)
      }
    }

    if (myIsPreview || !isArtifactResolutionQuerySupported) {
      def projectDependencies = findDependencies(configuration, configuration.allDependencies, scope)
      result.addAll(projectDependencies)
    }
    def fileDependencies = findAllFileDependencies(configuration.allDependencies, scope)
    result.addAll(fileDependencies - resolvedFileDependencies)

    return new ResolveDependenciesResult(new ArrayList(result), resolvedFileDependencies)
  }

  private ListMultimap<ModuleComponentIdentifier, ProjectDependency> resolveConfigurationProjectDependencies(Configuration configuration) {
    resolveConfigurationProjectDependencies(new HashSet<Configuration>(), configuration, ArrayListMultimap.create())
  }

  private ListMultimap<ModuleComponentIdentifier, ProjectDependency> resolveConfigurationProjectDependencies(Set<Configuration> processedConfigurations, Configuration conf, ListMultimap<ModuleComponentIdentifier, ProjectDependency> map) {
    if (!processedConfigurations.add(conf)) return map
    for (Dependency dependency : conf.incoming.dependencies) {
      if (dependency instanceof ProjectDependency) {
        ProjectDependency projectDependency = (ProjectDependency)dependency
        map.put(toComponentIdentifier(dependency.group, dependency.name, dependency.version), projectDependency)
        resolveConfigurationProjectDependencies(processedConfigurations, projectDependency.projectConfiguration, map)
      }
    }
    map
  }

  @Override
  Collection<org.jetbrains.plugins.gradle.model.ExternalDependency> resolveDependencies(@NotNull SourceSet sourceSet) {
    Collection<org.jetbrains.plugins.gradle.model.ExternalDependency> result = new ArrayList<>()

    // resolve compile dependencies
    def compileConfigurationName = sourceSet.compileConfigurationName
    def compileClasspathConfiguration = myProject.configurations.findByName(compileConfigurationName + 'Classpath')
    def originCompileConfiguration = myProject.configurations.findByName(compileConfigurationName)
    def compileConfiguration = compileClasspathConfiguration ?: originCompileConfiguration

    def compileScope = 'COMPILE'
    Collection<org.jetbrains.plugins.gradle.model.ExternalDependency> compileDependencies
    Collection<File> resolvedCompileFileDependencies
    resolveDependencies(compileConfiguration, compileScope).with {
      compileDependencies = dependencies
      resolvedCompileFileDependencies = files
    }
    // resolve runtime dependencies
    def runtimeConfigurationName = sourceSet.runtimeConfigurationName
    def runtimeConfiguration = myProject.configurations.findByName(runtimeConfigurationName)

    def runtimeScope = 'RUNTIME'
    Collection<org.jetbrains.plugins.gradle.model.ExternalDependency> runtimeDependencies
    Collection<File> resolvedRuntimeFileDependencies
    resolveDependencies(runtimeConfiguration, runtimeScope).with {
      runtimeDependencies = dependencies
      resolvedRuntimeFileDependencies = files
    }

    def providedScope = 'PROVIDED'

    ListMultimap<Object, org.jetbrains.plugins.gradle.model.ExternalDependency> resolvedMap = ArrayListMultimap.create()

    boolean checkCompileOnlyDeps = compileClasspathConfiguration && !originCompileConfiguration.resolvedConfiguration.hasError()
    for (org.jetbrains.plugins.gradle.model.ExternalDependency compileDependency : new DependencyTraverser(compileDependencies)) {
      def resolvedObj = resolve(compileDependency)
      resolvedMap.put(resolvedObj, compileDependency)

      if (checkCompileOnlyDeps &&
          (resolvedObj instanceof Collection ? !originCompileConfiguration.containsAll(((Collection)resolvedObj).toArray()) :
           !originCompileConfiguration.contains(resolvedObj))) {
        ((AbstractExternalDependency)compileDependency).scope = providedScope
      }
    }

    for (org.jetbrains.plugins.gradle.model.ExternalDependency runtimeDependency : new DependencyTraverser(runtimeDependencies)) {
      Collection<org.jetbrains.plugins.gradle.model.ExternalDependency> dependencies = resolvedMap.get(resolve(runtimeDependency))
      if (dependencies && !dependencies.isEmpty() && runtimeDependency.dependencies.isEmpty()) {
        runtimeDependencies.remove(runtimeDependency)
        ((AbstractExternalDependency)runtimeDependency).scope = dependencies.first().scope
      }
      else {
        resolvedMap.put(resolve(runtimeDependency), runtimeDependency)
      }
    }

    result.addAll(compileDependencies)
    result.addAll(runtimeDependencies)
    result.unique()

    // merge file dependencies
    def jvmLanguages = ['Java', 'Groovy', 'Scala']
    def sourceSetCompileTaskPrefix = sourceSet.name == 'main' ? '' : sourceSet.name
    def compileTasks = jvmLanguages.collect { 'compile' + sourceSetCompileTaskPrefix.capitalize() + it }

    Map<File, Integer> compileClasspathOrder = new LinkedHashMap()
    Set<File> compileClasspathFiles = new LinkedHashSet<>()

    for (String compileTaskName : compileTasks) {
      def compileTask = myProject.tasks.findByName(compileTaskName)
      if (compileTask instanceof AbstractCompile) {
        try {
          def files = new ArrayList<>(compileTask.getClasspath().files)
          files.removeAll(compileClasspathFiles)
          compileClasspathFiles.addAll(files)
        }
        catch (ignore) {
        }
      }
    }

    try {
      compileClasspathFiles = compileClasspathFiles.isEmpty() ? sourceSet.compileClasspath.files : compileClasspathFiles
    }
    catch (ignore) {
    }

    int order = 0
    for (File file : compileClasspathFiles) {
      compileClasspathOrder.put(file, order++)
    }
    Map<File, Integer> runtimeClasspathOrder = new LinkedHashMap()
    order = 0
    Set<File> runtimeClasspathFiles = new LinkedHashSet<File>()
    try {
      def files = sourceSet.runtimeClasspath.files
      for (File file : files) {
        runtimeClasspathOrder.put(file, order++)
      }
      runtimeClasspathFiles.addAll(files)
    }
    catch (ignore) {
    }

    runtimeClasspathFiles -= compileClasspathFiles
    runtimeClasspathFiles -= sourceSet.output.files
    compileClasspathFiles -= sourceSet.output.files

    ListMultimap<String, File> resolvedDependenciesMap = ArrayListMultimap.create()
    resolvedDependenciesMap.putAll(compileScope, resolvedCompileFileDependencies)
    resolvedDependenciesMap.putAll(runtimeScope, resolvedRuntimeFileDependencies)
    Project rootProject = myProject.rootProject

    for (org.jetbrains.plugins.gradle.model.ExternalDependency dependency : new DependencyTraverser(result)) {
      def scope = dependency.scope
      order = -1
      if (dependency instanceof ExternalProjectDependency) {
        ExternalProjectDependency projectDependency = (ExternalProjectDependency)dependency
        Project project = rootProject.findProject(projectDependency.projectPath)
        def configuration = project?.configurations?.findByName(projectDependency.configurationName)
        for (File artifactFile : configuration?.allArtifacts?.files?.files ?: [] as Collection<File>) {
          resolvedDependenciesMap.put(scope, artifactFile)
          def classpathOrderMap = scope == compileScope ? compileClasspathOrder :
                                  scope == runtimeScope ? runtimeClasspathOrder : null
          if (classpathOrderMap) {
            Integer fileOrder = classpathOrderMap.get(artifactFile)
            if (fileOrder != null && (order == -1 || fileOrder < order)) {
              order = fileOrder
            }
          }
        }

        //noinspection GrUnresolvedAccess
        JavaPluginConvention javaPluginConvention = getJavaPluginConvention(project)
        if (javaPluginConvention?.sourceSets?.getByName('main')) {
          //noinspection GrUnresolvedAccess
          addSourceSetOutputDirsAsSingleEntryLibraries(result, javaPluginConvention.sourceSets.getByName('main'), runtimeClasspathOrder,
                                                       scope)
        }
      }
      else if (dependency instanceof ExternalLibraryDependency) {
        ExternalLibraryDependency libDependency = ExternalLibraryDependency.cast(dependency)
        resolvedDependenciesMap.put(scope, libDependency.file)
        def classpathOrderMap = scope == compileScope ? compileClasspathOrder :
                                scope == runtimeScope ? runtimeClasspathOrder : null
        if (classpathOrderMap) {
          Integer fileOrder = classpathOrderMap.get(libDependency.file)
          order = fileOrder != null ? fileOrder : -1
        }
      }
      else if (dependency instanceof org.jetbrains.plugins.gradle.model.FileCollectionDependency) {
        org.jetbrains.plugins.gradle.model.FileCollectionDependency filesDependency = org.jetbrains.plugins.gradle.model.FileCollectionDependency.cast(dependency)
        for (File file : filesDependency.files) {
          resolvedDependenciesMap.put(scope, file)
          def classpathOrderMap = scope == compileScope ? compileClasspathOrder :
                                  scope == runtimeScope ? runtimeClasspathOrder : null
          if (classpathOrderMap) {
            Integer fileOrder = classpathOrderMap.get(file)
            if (fileOrder != null && (order == -1 || fileOrder < order)) {
              order = fileOrder
            }
            if (order == 0) break
          }
        }
      }

      if (dependency instanceof AbstractExternalDependency) {
        ((AbstractExternalDependency)dependency).classpathOrder = (int)order
      }
    }

    compileClasspathFiles.removeAll(resolvedDependenciesMap.get(compileScope))
    compileClasspathFiles.removeAll(resolvedDependenciesMap.get(providedScope))
    runtimeClasspathFiles.removeAll(resolvedDependenciesMap.get(runtimeScope))
    runtimeClasspathFiles.removeAll(resolvedDependenciesMap.get(compileScope))
    runtimeClasspathFiles.removeAll(resolvedDependenciesMap.get(providedScope))

    Collection<org.jetbrains.plugins.gradle.model.ExternalDependency> fileDependencies = new ArrayList<>()
    mapFileDependencies(runtimeClasspathFiles, runtimeScope, fileDependencies)
    mapFileDependencies(compileClasspathFiles, compileScope, fileDependencies)

    for (org.jetbrains.plugins.gradle.model.ExternalDependency dependency : fileDependencies) {
      def scope = dependency.scope
      order = -1
      if (dependency instanceof ExternalLibraryDependency) {
        def classpathOrderMap = scope == compileScope ? compileClasspathOrder :
                                scope == runtimeScope ? runtimeClasspathOrder : null
        if (classpathOrderMap) {
          Integer fileOrder = classpathOrderMap.get(((ExternalLibraryDependency)dependency).file)
          order = fileOrder != null ? fileOrder : -1
        }
      }
      if (dependency instanceof AbstractExternalDependency) {
        ((AbstractExternalDependency)dependency).classpathOrder = (int)order
      }
    }
    result.addAll(fileDependencies)

    if (!compileClasspathFiles.isEmpty()) {
      final compileClasspathFilesDependency = new DefaultFileCollectionDependency(compileClasspathFiles)
      compileClasspathFilesDependency.scope = compileScope

      order = -1
      for (File file : compileClasspathFiles) {
        Integer fileOrder = compileClasspathOrder.get(file)
        if (fileOrder != null && (order == -1 || fileOrder < order)) {
          order = fileOrder
        }
        if (order == 0) break
      }

      if (order != -1) {
        compileClasspathFilesDependency.classpathOrder = (int)order
      }
      result.add(compileClasspathFilesDependency)
      for (File file : compileClasspathFiles) {
        def outputDirSourceSet = mySourceSetFinder.findByArtifact(file.path)
        if(outputDirSourceSet) {
          addSourceSetOutputDirsAsSingleEntryLibraries(result, outputDirSourceSet, compileClasspathOrder, compileScope)
        }
      }
    }

    if (!runtimeClasspathFiles.isEmpty()) {
      final runtimeClasspathFilesDependency = new DefaultFileCollectionDependency(runtimeClasspathFiles)
      runtimeClasspathFilesDependency.scope = runtimeScope

      order = -1
      for (File file : runtimeClasspathFiles) {
        Integer fileOrder = runtimeClasspathOrder.get(file)
        if (fileOrder != null && (order == -1 || fileOrder < order)) {
          order = fileOrder
        }
        if (order == 0) break
      }

      runtimeClasspathFilesDependency.classpathOrder = (int)order
      result.add(runtimeClasspathFilesDependency)

      for (File file : runtimeClasspathFiles) {
        def outputDirSourceSet = mySourceSetFinder.findByArtifact(file.path)
        if(outputDirSourceSet) {
          addSourceSetOutputDirsAsSingleEntryLibraries(result, outputDirSourceSet, runtimeClasspathOrder, runtimeScope)
        }
      }
    }

    addSourceSetOutputDirsAsSingleEntryLibraries(result, sourceSet, runtimeClasspathOrder, runtimeScope)

    // handle provided dependencies
    def providedConfigurations = new LinkedHashSet<Configuration>()
    resolvedMap = ArrayListMultimap.create()
    for (org.jetbrains.plugins.gradle.model.ExternalDependency externalDependency : new DependencyTraverser(result)) {
      resolvedMap.put(resolve(externalDependency), externalDependency)
    }
    final IdeaPlugin ideaPlugin = myProject.getPlugins().findPlugin(IdeaPlugin.class)
    if (ideaPlugin) {
      def scopes = ideaPlugin.model.module.scopes
      def providedPlusScopes = scopes.get(providedScope)
      if (providedPlusScopes && providedPlusScopes.get("plus")) {
        providedConfigurations.addAll(providedPlusScopes.get("plus"))
      }
    }
    if (sourceSet.name == 'main' && myProject.plugins.findPlugin(WarPlugin)) {
      providedConfigurations.add(myProject.configurations.findByName('providedCompile'))
      providedConfigurations.add(myProject.configurations.findByName('providedRuntime'))
    }
    for (Configuration providedConfig : providedConfigurations) {
      def providedDependencies = resolveDependencies(providedConfig, providedScope).dependencies
      for (org.jetbrains.plugins.gradle.model.ExternalDependency dependency : new DependencyTraverser(providedDependencies)) {
        Collection<org.jetbrains.plugins.gradle.model.ExternalDependency> dependencies = resolvedMap.get(resolve(dependency))
        if (!dependencies.isEmpty()) {
          if (providedConfig.dependencies.isEmpty()) {
            providedDependencies.remove(dependency)
          }
          for (org.jetbrains.plugins.gradle.model.ExternalDependency mappedDependency : dependencies) {
            ((AbstractExternalDependency)mappedDependency).scope = providedScope
          }
        }
        else {
          resolvedMap.put(resolve(dependency), dependency)
        }
      }
      result.addAll(providedDependencies)
    }

    return removeDuplicates(resolvedMap, result)
  }

  private static List<org.jetbrains.plugins.gradle.model.ExternalDependency> removeDuplicates(
    ListMultimap<Object, org.jetbrains.plugins.gradle.model.ExternalDependency> resolvedMap, List<org.jetbrains.plugins.gradle.model.ExternalDependency> result) {
    for (Collection<org.jetbrains.plugins.gradle.model.ExternalDependency> collectionOfDependencies : resolvedMap.asMap().values()) {
      def toRemove = []
      def isCompileScope = false
      def isProvidedScope = false
      for (org.jetbrains.plugins.gradle.model.ExternalDependency externalDependency : collectionOfDependencies) {
        if (externalDependency.dependencies.isEmpty()) {
          toRemove.add(externalDependency)
          if (externalDependency.scope == 'COMPILE') {
            isCompileScope = true
          }
          else if (externalDependency.scope == 'PROVIDED') isProvidedScope = true
        }
      }
      if (toRemove.size() != collectionOfDependencies.size()) {
        result.removeAll(toRemove)
      }
      else if (toRemove.size() > 1) {
        toRemove.drop(1)
        result.removeAll(toRemove)
      }
      if(!toRemove.isEmpty()) {
        def retained = collectionOfDependencies - toRemove
        if(!retained.isEmpty()) {
          def retainedDependency = retained.first() as AbstractExternalDependency
          if(retainedDependency instanceof AbstractExternalDependency && retainedDependency.scope != 'COMPILE') {
            if(isCompileScope) retainedDependency.scope = 'COMPILE'
            else if(isProvidedScope) retainedDependency.scope = 'PROVIDED'
          }
        }
      }
    }

    return result.unique()
  }

  JavaPluginConvention getJavaPluginConvention(Project p) {
    p?.convention?.findPlugin(JavaPluginConvention)
  }

  static def resolve(org.jetbrains.plugins.gradle.model.ExternalDependency dependency) {
    if (dependency instanceof ExternalLibraryDependency) {
      return dependency.file
    } else if (dependency instanceof org.jetbrains.plugins.gradle.model.FileCollectionDependency) {
      return dependency.files
    } else if (dependency instanceof ExternalMultiLibraryDependency) {
      return dependency.files
    } else if (dependency instanceof ExternalProjectDependency) {
      return dependency.projectDependencyArtifacts
    }
    null
  }

  private static void addSourceSetOutputDirsAsSingleEntryLibraries(
    Collection<org.jetbrains.plugins.gradle.model.ExternalDependency> dependencies,
    SourceSet sourceSet,
    Map<File, Integer> classpathOrder,
    String scope) {
    Set<File> runtimeOutputDirs = sourceSet.output.dirs.files
    for (File outputDir : runtimeOutputDirs) {
      final runtimeOutputDirsDependency = new DefaultFileCollectionDependency([outputDir])
      runtimeOutputDirsDependency.scope = scope
      def fileOrder = classpathOrder.get(outputDir)
      runtimeOutputDirsDependency.classpathOrder = fileOrder != null ? fileOrder : -1
      dependencies.add(runtimeOutputDirsDependency)
    }
  }


  @Nullable
  ExternalLibraryDependency resolveLibraryByPath(File file, String scope) {
    File modules2Dir = new File(myProject.gradle.gradleUserHomeDir, "caches/modules-2/files-2.1")
    return resolveLibraryByPath(file, modules2Dir, scope)
  }

  @Nullable
  static ExternalLibraryDependency resolveLibraryByPath(File file, File modules2Dir, String scope) {
    File sourcesFile = null
    def modules2Path = modules2Dir.canonicalPath
    def filePath = file.canonicalPath
    if (filePath.startsWith(modules2Path)) {
      List<File> parents = new ArrayList<>()
      File parent = file.parentFile
      while(parent && !parent.name.equals(modules2Dir.name)) {
        parents.add(parent)
        parent = parent.parentFile
      }

      def groupDir = parents.get(parents.size() - 1)
      def artifactDir = parents.get(parents.size() - 2)
      def versionDir = parents.get(parents.size() - 3)

      def parentFile = versionDir
      if (parentFile != null) {
        def hashDirs = parentFile.listFiles()
        if (hashDirs != null) {
          for (File hashDir : hashDirs) {
            def sourcesJars = hashDir.listFiles(new FilenameFilter() {
              @Override
              boolean accept(File dir, String name) {
                return name.endsWith("sources.jar")
              }
            })

            if (sourcesJars != null && sourcesJars.length > 0) {
              sourcesFile = sourcesJars[0]
              break
            }
          }

          def packaging = resolvePackagingType(file)
          def classifier = resolveClassifier(artifactDir.name, versionDir.name, file)
          return new DefaultExternalLibraryDependency(
            name: artifactDir.name,
            group: groupDir.name,
            packaging: packaging,
            classifier: classifier,
            version: versionDir.name,
            file: file,
            source: sourcesFile,
            scope: scope
          )
        }
      }
    }

    null
  }

  def mapFileDependencies(Set<File> fileDependencies, String scope, Collection<org.jetbrains.plugins.gradle.model.ExternalDependency> dependencies) {
    File modules2Dir = new File(myProject.gradle.gradleUserHomeDir, "caches/modules-2/files-2.1")
    List toRemove = new ArrayList()
    for (File file : fileDependencies) {
      def libraryDependency = resolveLibraryByPath(file, modules2Dir, scope)
      if (libraryDependency) {
        dependencies.add(libraryDependency)
        toRemove.add(file)
      }
      else {
        //noinspection GrUnresolvedAccess
        def name = (file.name.lastIndexOf('.') as Integer).with { it != -1 ? file.name[0..<it] : file.name }
        def sourcesFile = new File(file.parentFile, name + '-sources.jar')
        if (sourcesFile.exists()) {
          libraryDependency = new DefaultExternalLibraryDependency(
            file: file,
            source: sourcesFile,
            scope: scope
          )
          if (libraryDependency) {
            dependencies.add(libraryDependency)
            toRemove.add(file)
          }
        }
      }
    }

    fileDependencies.removeAll(toRemove)
  }

  @Nullable
  static String resolvePackagingType(File file) {
    if (file == null) return 'jar'
    def path = file.getPath()
    int index = path.lastIndexOf('.')
    if (index < 0) return 'jar'
    return path.substring(index + 1)
  }

  @Nullable
  static String resolveClassifier(String name, String version, File file) {
    String libraryFileName = getNameWithoutExtension(file)
    final String mavenLibraryFileName = "$name-$version"
    if (!mavenLibraryFileName.equals(libraryFileName)) {
      Matcher matcher = Pattern.compile("$name-$version-(.*)").matcher(libraryFileName)
      if (matcher.matches()) {
        return matcher.group(1)
      }
    }
    return null
  }

  static String getNameWithoutExtension(File file) {
    if (file == null) return null
    def name = file.name
    int i = name.lastIndexOf('.')
    if (i != -1) {
      name = name.substring(0, i)
    }
    return name
  }

  static ModuleComponentIdentifier toComponentIdentifier(ModuleVersionIdentifier id) {
    return new ModuleComponentIdentifierImpl(id.getGroup(), id.getName(), id.getVersion())
  }

  static ModuleComponentIdentifier toComponentIdentifier(@NotNull String group, @NotNull String module, @NotNull String version) {
    return new ModuleComponentIdentifierImpl(group, module, version)
  }

  private static Set<org.jetbrains.plugins.gradle.model.ExternalDependency> findAllFileDependencies(
    Collection<Dependency> dependencies, String scope) {
    Set<org.jetbrains.plugins.gradle.model.ExternalDependency> result = new LinkedHashSet<>()

    for (Dependency dep : dependencies) {
      try {
        if (dep instanceof SelfResolvingDependency && !(dep instanceof ProjectDependency)) {
          def files = ((SelfResolvingDependency)dep).resolve()
          if (files && !files.isEmpty()) {
            final dependency = new DefaultFileCollectionDependency(files)
            dependency.scope = scope
            result.add(dependency)
          }
        }
      }
      catch (ignore) {
      }
    }

    return result
  }

  private Set<org.jetbrains.plugins.gradle.model.ExternalDependency> findDependencies(
    Configuration configuration,
    Collection<Dependency> dependencies,
    String scope) {
    Set<org.jetbrains.plugins.gradle.model.ExternalDependency> result = new LinkedHashSet<>()

    Set<ResolvedArtifact> resolvedArtifacts = myIsPreview ? new HashSet<ResolvedArtifact>() :
                                              configuration.resolvedConfiguration.lenientConfiguration.getArtifacts(Specs.SATISFIES_ALL)

    ListMultimap<MyModuleIdentifier, ResolvedArtifact> artifactMap = ArrayListMultimap.create()
    for (ResolvedArtifact resolvedArtifact : resolvedArtifacts) {
      artifactMap.put(toMyModuleIdentifier(resolvedArtifact.moduleVersion.id), resolvedArtifact)
    }

    for (Dependency dependency : dependencies) {
      try {
        if (dependency instanceof ProjectDependency) {
          ProjectDependency projectDep = (ProjectDependency)dependency
          def project = projectDep.dependencyProject
          def projectConfiguration = projectDep.projectConfiguration
          final projectDependency = new DefaultExternalProjectDependency(
            name: project.name,
            group: project.group?.toString(),
            version: project.version?.toString(),
            scope: scope,
            projectPath: project.path,
            configurationName: projectConfiguration.name
          )
          projectDependency.projectDependencyArtifacts = projectConfiguration.allArtifacts.files.files
          result.add(projectDependency)
        }
        else if (dependency instanceof Dependency) {
          List<ResolvedArtifact> artifactsResult = artifactMap.get(toMyModuleIdentifier(dependency.name, dependency.group))
          if (artifactsResult && !artifactsResult.isEmpty()) {
            ResolvedArtifact artifact = artifactsResult.first()
            def packaging = artifact.extension ?: 'jar'
            def classifier = artifact.classifier
            File sourcesFile = resolveLibraryByPath(artifact.file, scope)?.source
            def libraryDependency = new DefaultExternalLibraryDependency(
              name: dependency.name,
              group: dependency.group,
              packaging: packaging,
              classifier: classifier,
              version: artifact.moduleVersion.id.version,
              scope: scope,
              file: artifact.file,
              source: sourcesFile
            )
            result.add(libraryDependency)
          }
          else {
            if (!(dependency instanceof SelfResolvingDependency) && !myIsPreview) {
              final unresolvedDependency = new DefaultUnresolvedExternalDependency(
                name: dependency.name,
                group: dependency.group,
                version: dependency.version,
                scope: scope,
                failureMessage: "Could not find " + dependency.group + ":" + dependency.name + ":" + dependency.version
              )
              result.add(unresolvedDependency)
            }
          }
        }
      }
      catch (ignore) {
      }
    }

    return result
  }

  private static toMyModuleIdentifier(ModuleVersionIdentifier id) {
    return new MyModuleIdentifier(name: id.getName(), group: id.getGroup())
  }

  private static toMyModuleIdentifier(String name, String group) {
    return new MyModuleIdentifier(name: name, group: group)
  }

  @CompileStatic
  @EqualsAndHashCode
  static class MyModuleIdentifier {
    String name
    String group

    @Override
    String toString() {
      return "$group:$name"
    }
  }
}
