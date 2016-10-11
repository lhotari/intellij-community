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

import com.google.common.collect.ListMultimap
import com.google.common.collect.Lists
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.component.*
import org.gradle.api.artifacts.result.*
import org.gradle.api.tasks.SourceSetOutput
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.language.base.artifact.SourcesArtifact
import org.gradle.language.java.artifact.JavadocArtifact
import org.jetbrains.plugins.gradle.model.DefaultExternalLibraryDependency
import org.jetbrains.plugins.gradle.model.DefaultExternalProjectDependency
import org.jetbrains.plugins.gradle.model.DefaultFileCollectionDependency
import org.jetbrains.plugins.gradle.model.DefaultUnresolvedExternalDependency

@CompileStatic
class DependencyResultsTransformer {
  Project myProject
  Collection<DependencyResult> handledDependencyResults
  ListMultimap<ModuleVersionIdentifier, ResolvedArtifact> artifactMap
  Map<ComponentIdentifier, ComponentArtifactsResult> componentResultsMap
  ListMultimap<ModuleComponentIdentifier, ProjectDependency> configurationProjectDependencies
  String scope
  Set<File> resolvedDepsFiles = []

  DependencyResultsTransformer(
    Project myProject,
    ListMultimap<ModuleVersionIdentifier, ResolvedArtifact> artifactMap,
    Map<ComponentIdentifier, ComponentArtifactsResult> componentResultsMap,
    ListMultimap<ModuleComponentIdentifier, ProjectDependency> configurationProjectDependencies,
    String scope) {
    this.myProject = myProject
    this.handledDependencyResults = Lists.newArrayList()
    this.artifactMap = artifactMap
    this.componentResultsMap = componentResultsMap
    this.configurationProjectDependencies = configurationProjectDependencies
    this.scope = scope
  }

  Set<org.jetbrains.plugins.gradle.model.ExternalDependency> transform(Collection<? extends DependencyResult> dependencyResults) {

    Set<org.jetbrains.plugins.gradle.model.ExternalDependency> dependencies = new LinkedHashSet<>()
    for (DependencyResult dependencyResult : dependencyResults) {

      // dependency cycles check
      if (!handledDependencyResults.contains(dependencyResult)) {
        handledDependencyResults.add(dependencyResult)

        if (dependencyResult instanceof ResolvedDependencyResult) {
          def componentResult = ((ResolvedDependencyResult)dependencyResult).selected
          def componentSelector = dependencyResult.requested
          def componentIdentifier = DependencyResolverImpl.toComponentIdentifier(componentResult.moduleVersion)
          def name = componentResult.moduleVersion.name
          def group = componentResult.moduleVersion.group
          def version = componentResult.moduleVersion.version
          def selectionReason = componentResult.selectionReason.description
          if (componentSelector instanceof ProjectComponentSelector) {
            def projectDependencies = configurationProjectDependencies.get(componentIdentifier)
            Collection<Configuration> dependencyConfigurations
            if (projectDependencies.isEmpty()) {
              def dependencyProject = myProject.findProject(componentSelector.getProjectPath())
              def dependencyProjectConfiguration = dependencyProject.getConfigurations().getByName(Dependency.DEFAULT_CONFIGURATION)
              dependencyConfigurations = [dependencyProjectConfiguration]
            }
            else {
              dependencyConfigurations = projectDependencies.collect { it.projectConfiguration }
            }

            for (def dependencyConfig : dependencyConfigurations) {
              if (dependencyConfig.name == Dependency.DEFAULT_CONFIGURATION) {
                final dependency = new DefaultExternalProjectDependency(
                  name: name,
                  group: group,
                  version: version,
                  scope: scope,
                  selectionReason: selectionReason,
                  projectPath: (componentSelector as ProjectComponentSelector).projectPath,
                  configurationName: dependencyConfig.name
                )
                dependency.projectDependencyArtifacts = dependencyConfig.allArtifacts.files.files
                for (File file : dependency.projectDependencyArtifacts) {
                  resolvedDepsFiles.add(file)
                }
                if (dependencyConfig.artifacts.size() == 1) {
                  def publishArtifact = dependencyConfig.allArtifacts.first()
                  dependency.classifier = publishArtifact.classifier
                  dependency.packaging = publishArtifact.extension ?: 'jar'
                }

                if (componentResult != dependencyResult.from) {
                  dependency.dependencies.addAll(
                    transform(Collection.cast(componentResult.dependencies))
                  )
                }
                dependencies.add(dependency)
              }
              else {
                final dependency = new DefaultExternalProjectDependency(
                  name: name,
                  group: group,
                  version: version,
                  scope: scope,
                  selectionReason: selectionReason,
                  projectPath: (componentSelector as ProjectComponentSelector).projectPath,
                  configurationName: dependencyConfig.name
                )
                dependency.projectDependencyArtifacts = dependencyConfig.allArtifacts.files.files
                for (File file : dependency.projectDependencyArtifacts) {
                  resolvedDepsFiles.add(file)
                }
                if (dependencyConfig.artifacts.size() == 1) {
                  def publishArtifact = dependencyConfig.allArtifacts.first()
                  dependency.classifier = publishArtifact.classifier
                  dependency.packaging = publishArtifact.extension ?: 'jar'
                }

                if (componentResult != dependencyResult.from) {
                  dependency.dependencies.addAll(
                    transform(Collection.cast(componentResult.dependencies))
                  )
                }
                dependencies.add(dependency)

                def files = []
                def artifacts = dependencyConfig.getArtifacts()
                if (artifacts && !artifacts.isEmpty()) {
                  files = resolveArtifactFiles(artifacts.first())
                }

                if (!files.isEmpty()) {
                  final fileCollectionDependency = new DefaultFileCollectionDependency(Collection.cast(files))
                  fileCollectionDependency.scope = scope
                  dependencies.add(fileCollectionDependency)
                  resolvedDepsFiles.addAll(files)
                }
              }
            }
          }
          if (componentSelector instanceof ModuleComponentSelector) {
            def artifacts = artifactMap.get(componentResult.moduleVersion) ?: [] as Collection<ResolvedArtifact>

            if (artifacts?.isEmpty()) {
              dependencies.addAll(
                transform(Collection.cast(componentResult.dependencies))
              )
            }
            boolean first = true
            for (ResolvedArtifact artifact : artifacts) {
              def packaging = artifact.extension ?: 'jar'
              def classifier = artifact.classifier
              final dependency
              if (DependencyResolverImpl.isDependencySubstitutionsSupported && artifact.id.componentIdentifier instanceof ProjectComponentIdentifier) {
                def artifactComponentIdentifier = artifact.id.componentIdentifier as ProjectComponentIdentifier
                dependency = new DefaultExternalProjectDependency(
                  name: name,
                  group: group,
                  version: version,
                  scope: scope,
                  selectionReason: selectionReason,
                  projectPath: artifactComponentIdentifier.projectPath,
                  configurationName: Dependency.DEFAULT_CONFIGURATION
                )
                dependency.projectDependencyArtifacts = artifactMap.get(componentResult.moduleVersion).collect { it.file }
                for (File file : dependency.getProjectDependencyArtifacts()) {
                  resolvedDepsFiles.add(file)
                }
              }
              else {
                dependency = new DefaultExternalLibraryDependency(
                  name: name,
                  group: group,
                  packaging: packaging,
                  classifier: classifier,
                  version: version,
                  scope: scope,
                  selectionReason: selectionReason,
                  file: artifact.file
                )

                def artifactsResult = componentResultsMap.get(componentIdentifier)
                if (artifactsResult) {
                  def sourcesResult = artifactsResult.getArtifacts(SourcesArtifact)?.find {
                    it instanceof ResolvedArtifactResult
                  }
                  if (sourcesResult) {
                    dependency.setSource(((ResolvedArtifactResult)sourcesResult).getFile())
                  }
                  def javadocResult = artifactsResult.getArtifacts(JavadocArtifact)?.find { it instanceof ResolvedArtifactResult }
                  if (javadocResult) {
                    dependency.setJavadoc(((ResolvedArtifactResult)javadocResult).getFile())
                  }
                }
              }
              if (first) {
                dependency.dependencies.addAll(
                  transform(Collection.cast(componentResult.dependencies))
                )
                first = false
              }

              dependencies.add(dependency)
              resolvedDepsFiles.add(artifact.file)
            }
          }
        }

        if (dependencyResult instanceof UnresolvedDependencyResult) {
          def unresolvedDependencyResult = (UnresolvedDependencyResult)dependencyResult
          def componentResult = unresolvedDependencyResult.attempted
          if (componentResult instanceof ModuleComponentSelector) {
            final dependency = new DefaultUnresolvedExternalDependency(
              name: componentResult.getModule(),
              group: componentResult.getGroup(),
              version: componentResult.getVersion(),
              scope: scope,
              failureMessage: unresolvedDependencyResult.failure.message
            )
            dependencies.add(dependency)
          }
        }
      }
    }

    return dependencies
  }

  @CompileDynamic
  private Collection<File> resolveArtifactFiles(PublishArtifact artifact) {
    def files = []
    if (artifact.hasProperty("archiveTask") &&
        (artifact.archiveTask instanceof AbstractArchiveTask)) {
      def archiveTask = artifact.archiveTask as AbstractArchiveTask
      resolvedDepsFiles.add(new File(archiveTask.destinationDir, archiveTask.archiveName))

      def mainSpec = archiveTask.mainSpec
      def sourcePaths
      if (mainSpec.metaClass.respondsTo(mainSpec, 'getSourcePaths')) {
        sourcePaths = mainSpec.getSourcePaths()
      }
      else if (mainSpec.hasProperty('sourcePaths')) {
        sourcePaths = mainSpec.sourcePaths
      }
      if (sourcePaths) {
        for (Object path : sourcePaths.flatten()) {
          if (path instanceof String) {
            def file = new File(path)
            if (file.isAbsolute()) {
              files.add(file)
            }
          }
          else if (path instanceof SourceSetOutput) {
            files.addAll(path.files)
          }
        }
      }
    }
  }
}